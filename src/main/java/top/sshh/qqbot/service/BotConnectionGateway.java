package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zhuangxv.bot.api.ApiResult;
import com.zhuangxv.bot.api.BaseApi;
import com.zhuangxv.bot.api.support.GetFriends;
import com.zhuangxv.bot.api.support.GetGroups;
import com.zhuangxv.bot.api.support.SendGroupMsg;
import com.zhuangxv.bot.api.support.SendPrivateMsg;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.core.network.ws.WsBotClient;
import com.zhuangxv.bot.message.MessageChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 连接监测专用的有界 API 调用，不使用好友缓存判断实际好友关系。 */
@Component
public class BotConnectionGateway {
    private static final Logger logger = LoggerFactory.getLogger(BotConnectionGateway.class);
    private final long timeoutMs;
    @Value("${xxGroupId:0}")
    private long xxGroupId;
    private final ExecutorService requests = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "bot-connection-api");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public BotConnectionGateway() {
        this(5000L);
    }

    BotConnectionGateway(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public enum Status { ONLINE, OFFLINE, DISCONNECTED, UNKNOWN }
    private enum PrivateResult { SENT, NOT_FRIEND, OFFLINE, FAILED }

    public static class Probe {
        public final Status status;
        public final String detail;

        public Probe(Status status, String detail) {
            this.status = status;
            this.detail = detail;
        }
    }

    public Map<Long, Bot> bots() {
        return new LinkedHashMap<>(BotFactory.getBots());
    }

    /** 所有账号共用一个等待截止时间，避免 N 个失联账号各阻塞 30 秒。 */
    public Map<Long, Probe> probeAll(Map<Long, Bot> bots) {
        Map<Long, Future<Probe>> futures = new LinkedHashMap<>();
        Map<Long, Probe> result = new LinkedHashMap<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        bots.forEach((id, bot) -> {
            try {
                futures.put(id, requests.submit(() -> probe(bot)));
            } catch (RejectedExecutionException e) {
                result.put(id, unknown());
            }
        });
        futures.forEach((id, future) -> {
            try {
                result.put(id, future.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.put(id, unknown());
            } catch (ExecutionException | TimeoutException e) {
                result.put(id, unknown());
            } finally {
                if (!future.isDone()) future.cancel(true);
            }
        });
        return result;
    }

    Probe probe(Bot bot) {
        try {
            if (bot.getBotClient() instanceof WsBotClient) {
                WsBotClient client = (WsBotClient) bot.getBotClient();
                if (client.getChannel() == null || !client.getChannel().isActive()) {
                    return new Probe(Status.DISCONNECTED, "WebSocket 连接已断开");
                }
            }
            ApiResult response = bot.invoke(new GetStatus());
            if (!successful(response)) return unknown();
            JSONObject data = object(response.getData());
            if (data == null) return unknown();
            Boolean online = data.getBoolean("online");
            if (Boolean.FALSE.equals(online)) return new Probe(Status.OFFLINE, "QQ 已离线");
            if (Boolean.TRUE.equals(online) && !Boolean.FALSE.equals(data.getBoolean("good"))) {
                return new Probe(Status.ONLINE, "在线");
            }
            return new Probe(Status.UNKNOWN, "在线状态无法确认或协议端状态异常");
        } catch (Exception e) {
            logger.debug("连接状态查询失败 botId={}", bot.getBotId(), e);
            return unknown();
        }
    }

    /** 优先私聊；确认所有在线候选账号均非好友后，改发配置的提醒群并 @master。 */
    public boolean sendToMaster(long masterQQ, String message, Collection<Bot> onlineBots, Long excludedBotId) {
        if (masterQQ <= 0L) return false;
        List<Bot> candidates = new ArrayList<>(onlineBots);
        candidates.sort(Comparator.<Bot>comparingInt(bot ->
                bot.getBotConfig().getMasterQQ() == masterQQ ? 0 : 1).thenComparingLong(Bot::getBotId));
        List<Bot> nonFriends = new ArrayList<>();
        boolean allNonFriends = true;
        for (Bot bot : candidates) {
            if (bot.getBotId() == masterQQ || (excludedBotId != null && bot.getBotId() == excludedBotId)) continue;
            Future<PrivateResult> attempt = null;
            AtomicBoolean active = new AtomicBoolean(true);
            try {
                attempt = requests.submit(() -> sendIfFriend(bot, masterQQ, message, active));
                PrivateResult result = attempt.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (result == PrivateResult.SENT) return true;
                if (result == PrivateResult.NOT_FRIEND) nonFriends.add(bot);
                if (result == PrivateResult.FAILED) allNonFriends = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
                allNonFriends = false;
                logger.warn("监测通知发送失败，尝试其他账号 botId={} masterQQ={}", bot.getBotId(), masterQQ);
            } finally {
                active.set(false);
                if (attempt != null && !attempt.isDone()) attempt.cancel(true);
            }
        }
        if (allNonFriends && !nonFriends.isEmpty() && xxGroupId > 0L) {
            return sendToReminderGroup(masterQQ, message, nonFriends, xxGroupId);
        }
        return false;
    }

    private PrivateResult sendIfFriend(Bot bot, long masterQQ, String message, AtomicBoolean active) {
        if (!active.get()) return PrivateResult.FAILED;
        Status status = probe(bot).status;
        if (status == Status.OFFLINE || status == Status.DISCONNECTED) return PrivateResult.OFFLINE;
        if (status != Status.ONLINE || !active.get() || Thread.currentThread().isInterrupted()) return PrivateResult.FAILED;
        ApiResult friends = bot.invoke(new GetFriends());
        if (!successful(friends) || friends.getData() == null) return PrivateResult.FAILED;
        JSONArray list = JSON.parseArray(json(friends.getData()));
        if (list == null) return PrivateResult.FAILED;
        boolean isFriend = list.stream().anyMatch(item -> {
            JSONObject friend = object(item);
            return friend != null && friend.getLongValue("user_id") == masterQQ;
        });
        if (!active.get() || Thread.currentThread().isInterrupted()) return PrivateResult.FAILED;
        if (!isFriend) return PrivateResult.NOT_FRIEND;
        ApiResult sent = bot.invoke(new SendPrivateMsg(masterQQ, new MessageChain().text(message)));
        if (!delivered(sent)) return PrivateResult.FAILED;
        logger.info("监测通知私聊发送成功 botId={} masterQQ={}", bot.getBotId(), masterQQ);
        return PrivateResult.SENT;
    }

    private boolean sendToReminderGroup(long masterQQ, String message, List<Bot> candidates, long groupId) {
        for (Bot bot : candidates) {
            Future<Boolean> attempt = null;
            AtomicBoolean active = new AtomicBoolean(true);
            try {
                attempt = requests.submit(() -> sendIfJoined(bot, masterQQ, message, groupId, active));
                if (attempt.get(timeoutMs, TimeUnit.MILLISECONDS)) return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
                logger.warn("监测通知群发送失败，尝试其他账号 botId={} groupId={} masterQQ={}",
                        bot.getBotId(), groupId, masterQQ);
            } finally {
                active.set(false);
                if (attempt != null && !attempt.isDone()) attempt.cancel(true);
            }
        }
        return false;
    }

    private boolean sendIfJoined(Bot bot, long masterQQ, String message, long groupId, AtomicBoolean active) {
        if (!active.get() || probe(bot).status != Status.ONLINE || !active.get()
                || Thread.currentThread().isInterrupted()) return false;
        ApiResult groups = bot.invoke(new GetGroups());
        if (!successful(groups) || groups.getData() == null) return false;
        JSONArray list = JSON.parseArray(json(groups.getData()));
        boolean joined = list != null && list.stream().anyMatch(item -> {
            JSONObject group = object(item);
            return group != null && group.getLongValue("group_id") == groupId;
        });
        if (!joined || !active.get() || Thread.currentThread().isInterrupted()) return false;
        ApiResult sent = bot.invoke(new SendGroupMsg(groupId,
                new MessageChain().at(Long.toString(masterQQ)).text("\n" + message)));
        if (!delivered(sent)) return false;
        logger.info("监测通知群发送成功 botId={} groupId={} masterQQ={}", bot.getBotId(), groupId, masterQQ);
        return true;
    }

    private static boolean delivered(ApiResult result) {
        if (!successful(result)) return false;
        JSONObject data = object(result.getData());
        return data != null && data.getLongValue("message_id") != 0L;
    }

    private static Probe unknown() {
        return new Probe(Status.UNKNOWN, "请求失败或超时，在线状态无法确认");
    }

    private static boolean successful(ApiResult result) {
        return result != null && "ok".equals(result.getStatus()) && result.getRetCode() == 0;
    }

    private static String json(Object data) {
        return data instanceof String ? (String) data : JSON.toJSONString(data);
    }

    private static JSONObject object(Object data) {
        return data == null ? null : JSON.parseObject(json(data));
    }

    @PreDestroy
    public void close() {
        requests.shutdownNow();
    }

    /** OneBot 11 get_status：online 表示 QQ 在线，good 表示协议端状态正常。 */
    public static class GetStatus extends BaseApi {
        @Override public String getAction() { return "get_status"; }
        @Override public Object getParams() { return Collections.emptyMap(); }
        @Override public boolean needSleep() { return false; }
    }
}
