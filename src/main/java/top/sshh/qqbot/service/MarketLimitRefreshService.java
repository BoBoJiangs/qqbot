package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.ProductLowPrice;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.Utils;

import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** 每个机器人各维护一轮查询；保存当前回复后等待两秒，再发送下一种药材。 */
@Component
public class MarketLimitRefreshService {
    private static final Logger logger = LoggerFactory.getLogger(MarketLimitRefreshService.class);
    private final ProductPriceResponse prices;
    private final ScheduledExecutorService scheduler;
    private final Map<Long, RefreshSession> sessions = new HashMap<>();

    @Autowired
    public MarketLimitRefreshService(ProductPriceResponse prices) {
        this(prices, Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "market-limit-refresh");
            thread.setDaemon(true);
            return thread;
        }));
    }

    MarketLimitRefreshService(ProductPriceResponse prices, ScheduledExecutorService scheduler) {
        this.prices = prices;
        this.scheduler = scheduler;
    }

    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.ONLY_ITSELF)
    public synchronized void handleCommand(Bot bot, Group group, String message, Integer messageId) {
        String command = StringUtils.trimToEmpty(message);
        if ("停止更新坊市限价".equals(command)) {
            RefreshSession previous = sessions.remove(bot.getBotId());
            if (previous == null) {
                reply(group, messageId, "当前没有正在更新的坊市限价任务。");
            } else {
                if (previous.nextQuery != null) previous.nextQuery.cancel(false);
                reply(group, messageId, "已停止更新坊市限价，已更新 " + previous.completed
                        + "/" + previous.names.size() + " 种药材。");
            }
            return;
        }
        if (!"开始更新坊市限价".equals(command)) return;
        RefreshSession previous = sessions.get(bot.getBotId());
        if (previous != null) {
            reply(group, messageId, "坊市限价正在更新，已更新 " + previous.completed
                    + "/" + previous.names.size() + " 种药材，请先停止后再开始。");
            return;
        }
        Set<String> names = new TreeSet<>();
        for (ProductPrice product : prices.findAll()) {
            if (isHerb(product)) names.add(product.getName().trim());
        }
        if (names.isEmpty()) {
            reply(group, messageId, "数据库中没有可更新限价的药材。");
            return;
        }
        RefreshSession session = new RefreshSession(bot, group, new ArrayList<>(names));
        sessions.put(bot.getBotId(), session);
        reply(group, messageId, "开始更新坊市限价，共 " + names.size() + " 种药材。");
        sendCurrent(session);
    }

    private boolean isHerb(ProductPrice product) {
        if (product == null || StringUtils.isBlank(product.getName())) return false;
        if (StringUtils.isNotBlank(product.getCategory())) {
            return "药材".equals(product.getCategory().trim());
        }
        // 旧数据尚未存分类时，使用游戏现有药材目录识别；不会额外添加目录中未入库的药材。
        String level = ProductLowPrice.PRODUCT_LEVEL_CACHE.get(product.getName().trim());
        return level != null && level.contains("药材");
    }

    public synchronized void onQuoteSaved(Bot bot, Group group, MessageChain messageChain, ProductPrice quote) {
        RefreshSession session = sessions.get(bot.getBotId());
        if (session == null || !session.waitingForReply || group == null
                || group.getGroupId() != session.group.getGroupId()
                || !session.names.get(session.completed).equals(quote.getName())
                || addressedToAnotherBot(bot, messageChain)) return;

        session.waitingForReply = false;
        session.completed++;
        logger.info("坊市限价已保存，botId={}, groupId={}, 药材={}, 进度={}/{}",
                bot.getBotId(), group.getGroupId(), quote.getName(), session.completed, session.names.size());
        if (session.completed == session.names.size()) {
            sessions.remove(bot.getBotId());
            session.group.sendMessage(new MessageChain().text(
                    "坊市限价更新完成，共更新 " + session.completed + " 种药材。"));
            return;
        }
        try {
            session.nextQuery = scheduler.schedule(() -> sendNext(session), 2L, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            fail(session, e);
        }
    }

    private boolean addressedToAnotherBot(Bot bot, MessageChain chain) {
        if (chain == null) return false;
        List<AtMessage> mentions = chain.getMessageByType(AtMessage.class);
        if (mentions == null || mentions.isEmpty()) return false;
        boolean hasRecipient = false;
        for (AtMessage mention : mentions) {
            if (String.valueOf(bot.getBotId()).equals(mention.getQq())) return false;
            if (StringUtils.isNumeric(mention.getQq())) hasRecipient = true;
        }
        return hasRecipient;
    }

    private synchronized void sendNext(RefreshSession session) {
        // 同时校验会话身份，停止后重新开始时，上一轮的延迟回调也不能继续发送。
        if (sessions.get(session.bot.getBotId()) != session) return;
        session.nextQuery = null;
        session.waitingForReply = true;
        sendCurrent(session);
    }

    private void sendCurrent(RefreshSession session) {
        try {
            boolean sent = Utils.sendGroupMessage(session.bot, session.group.getGroupId(), new MessageChain()
                    .at("3889001741").text("坊市数据" + session.names.get(session.completed)));
            if (!sent) {
                fail(session, null);
            } else {
                logger.info("已发送坊市限价查询，botId={}, groupId={}, 药材={}, 序号={}/{}",
                        session.bot.getBotId(), session.group.getGroupId(),
                        session.names.get(session.completed), session.completed + 1, session.names.size());
            }
        } catch (Exception e) {
            fail(session, e);
        }
    }

    private void fail(RefreshSession session, Exception cause) {
        if (sessions.get(session.bot.getBotId()) != session) return;
        sessions.remove(session.bot.getBotId());
        if (session.nextQuery != null) session.nextQuery.cancel(false);
        logger.warn("坊市限价查询发送失败，botId={}, groupId={}",
                session.bot.getBotId(), session.group.getGroupId(), cause);
        session.group.sendMessage(new MessageChain().text("坊市数据查询发送失败，已停止更新坊市限价。"));
    }

    private void reply(Group group, Integer messageId, String text) {
        group.sendMessage(new MessageChain().reply(messageId).text(text));
    }

    @PreDestroy
    public synchronized void shutdown() {
        for (RefreshSession session : sessions.values()) {
            if (session.nextQuery != null) session.nextQuery.cancel(false);
        }
        sessions.clear();
        scheduler.shutdownNow();
    }

    private static class RefreshSession {
        final Bot bot;
        final Group group;
        final List<String> names;
        int completed;
        boolean waitingForReply = true;
        ScheduledFuture<?> nextQuery;

        RefreshSession(Bot bot, Group group, List<String> names) {
            this.bot = bot;
            this.group = group;
            this.names = names;
        }
    }
}
