package top.sshh.qqbot.service.utils;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/** 宗门出关/闭关的回执与有限重试；每个机器人只保留当前一步。 */
public final class SectCultivationManager {
    private static final Logger log = LoggerFactory.getLogger(SectCultivationManager.class);
    private static final long[] RETRY_SECONDS = {5, 15, 30, 60, 120};
    private static final long REPLY_TIMEOUT_SECONDS = 30;
    private static final SectCultivationManager INSTANCE = new SectCultivationManager(
            createExecutor(), XiaoXiaoCommandScheduler::sendTracked,
            () -> ThreadLocalRandom.current().nextInt(6),
            (bot, message) -> Utils.sendGroupMessage(bot, bot.getBotConfig().getGroupId(),
                    new MessageChain().text(message)));

    @FunctionalInterface
    interface Transport {
        void send(Bot bot, long groupId, MessageChain message, BooleanSupplier beforeSend,
                  Consumer<Boolean> result);
    }

    private enum Phase { QUEUED, WAIT_REPLY, RETRY_WAIT, CONTINUING }

    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    // 消息处理器执行顺序不固定；即使最后一次繁忙已使流程退出，妖塔也不能再次处理同一回执。
    private final Map<Long, String> lastHandledReply = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor;
    private final Transport transport;
    private final IntSupplier jitterSeconds;
    private final BiConsumer<Bot, String> notifier;

    SectCultivationManager(ScheduledExecutorService executor, Transport transport,
                           IntSupplier jitterSeconds, BiConsumer<Bot, String> notifier) {
        this.executor = executor;
        this.transport = transport;
        this.jitterSeconds = jitterSeconds;
        this.notifier = notifier;
    }

    public static SectCultivationManager getInstance() {
        return INSTANCE;
    }

    private static ScheduledExecutorService createExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "sect-cultivation-retry");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public void exit(Bot bot, long groupId, String owner, BooleanSupplier valid,
                     Runnable success, Runnable failure) {
        request(new Pending(bot, groupId, false, owner, valid, success, failure));
    }

    public void enter(Bot bot, long groupId) {
        request(new Pending(bot, groupId, true, "恢复宗门闭关",
                () -> !bot.getBotConfig().isStop() && bot.getBotConfig().getCultivationMode() == 3
                        && bot.getBotConfig().getGroupId() == groupId,
                () -> { }, () -> { }));
    }

    private void request(Pending next) {
        synchronized (next.bot) {
            Pending previous = pending.get(next.bot.getBotId());
            if (previous != null && current(previous)) {
                // 新任务等待出关时，迟到的旧任务结算不能把它重新送回闭关。
                if (next.entering && !previous.entering) return;
                if (previous.entering == next.entering && previous.owner.equals(next.owner)) return;
            }
            cancel(next.bot);
            if (!next.valid.getAsBoolean()) return;
            pending.put(next.bot.getBotId(), next);
        }
        dispatch(next, 0);
    }

    public void cancel(Bot bot) {
        synchronized (bot) {
            Pending previous = pending.remove(bot.getBotId());
            if (previous != null) cancelTimer(previous);
        }
    }

    public boolean isTracking(Bot bot) {
        synchronized (bot) {
            Pending step = pending.get(bot.getBotId());
            return step != null && current(step);
        }
    }

    public boolean wasHandled(Bot bot, Group group, Integer messageId) {
        return group != null && messageId != null
                && (group.getGroupId() + ":" + messageId).equals(lastHandledReply.get(bot.getBotId()));
    }

    private boolean current(Pending step) {
        if (pending.get(step.bot.getBotId()) != step) return false;
        if (!step.valid.getAsBoolean()) {
            pending.remove(step.bot.getBotId(), step);
            cancelTimer(step);
            return false;
        }
        return true;
    }

    private void dispatch(Pending step, int previousAttempt) {
        final int attempt;
        synchronized (step.bot) {
            if (!current(step) || step.attempt != previousAttempt
                    || step.phase != null && step.phase != Phase.RETRY_WAIT) return;
            attempt = ++step.attempt;
            step.phase = Phase.QUEUED;
        }
        try {
            transport.send(step.bot, step.groupId,
                    new MessageChain().at("3889001741").text(step.command()),
                    () -> beforeSend(step, attempt), sent -> {
                        if (!sent) retry(step, attempt);
                    });
        } catch (Exception e) {
            log.warn("宗门指令发送异常 botId={}, command={}", step.bot.getBotId(), step.command(), e);
            retry(step, attempt);
        }
    }

    private boolean beforeSend(Pending step, int attempt) {
        synchronized (step.bot) {
            if (!current(step) || step.phase != Phase.QUEUED || step.attempt != attempt) return false;
            step.sent = true;
            step.phase = Phase.WAIT_REPLY;
            // 在错峰队列真正开始发送时计时，排队时间不计入回执超时。
            cancelTimer(step);
            step.timer = executor.schedule(() -> retry(step, attempt), REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        }
    }

    private void retry(Pending step, int attempt) {
        synchronized (step.bot) {
            if (!current(step) || step.attempt != attempt
                    || step.phase == Phase.RETRY_WAIT || step.phase == Phase.CONTINUING) return;
            cancelTimer(step);
            if (step.retries >= RETRY_SECONDS.length) {
                pending.remove(step.bot.getBotId(), step);
                step.failure.run();
                String message = "机器人" + step.bot.getBotId() + "：" + step.command()
                        + "重试5次仍未确认成功，已暂停本次流程（" + step.owner + "），请检查后重新启动。";
                log.warn(message);
                notifier.accept(step.bot, message);
                return;
            }
            long delay = RETRY_SECONDS[step.retries++] + jitterSeconds.getAsInt();
            step.phase = Phase.RETRY_WAIT;
            log.info("宗门指令等待重试 botId={}, command={}, retry={}, delaySeconds={}",
                    step.bot.getBotId(), step.command(), step.retries, delay);
            step.timer = executor.schedule(() -> dispatch(step, attempt), delay, TimeUnit.SECONDS);
        }
    }

    /** 仅由小小的消息处理器调用；目标账号和目标群均必须与正在等待的操作一致。 */
    public boolean handleReply(Bot bot, Group group, MessageChain chain, String message, Integer messageId) {
        String text = (message == null ? "" : message) + "\n" + Utils.getMessageText(chain);
        synchronized (bot) {
            Pending step = pending.get(bot.getBotId());
            if (step == null || !current(step) || group == null || group.getGroupId() != step.groupId
                    || !step.sent || !addressedTo(bot, chain, text)) return false;
            boolean busy = text.contains("宗门系统繁忙") || text.contains("上一条指令还没执行完");
            boolean success = step.entering ? entered(text) : exited(text);
            if (!busy && !success) return false;
            if (messageId != null && !step.replyIds.add(messageId)) return true;
            if (messageId != null) lastHandledReply.put(bot.getBotId(), step.groupId + ":" + messageId);
            if (busy) {
                retry(step, step.attempt);
            } else if (step.phase != Phase.CONTINUING) {
                cancelTimer(step);
                step.phase = Phase.CONTINUING;
                // 给游戏留出处理间隔；这段等待也受停止/切换任务的取消控制。
                step.timer = executor.schedule(() -> {
                    synchronized (bot) {
                        if (!current(step)) return;
                        pending.remove(bot.getBotId(), step);
                        step.success.run();
                    }
                }, step.entering ? 0 : 2, TimeUnit.SECONDS);
            }
            return true;
        }
    }

    private static boolean addressedTo(Bot bot, MessageChain chain, String text) {
        if (chain != null) {
            List<AtMessage> ats = chain.getMessageByType(AtMessage.class);
            if (ats != null && !ats.isEmpty()) {
                return ats.stream().anyMatch(at -> String.valueOf(bot.getBotId()).equals(at.getQq()));
            }
            List<ReplyMessage> replies = chain.getMessageByType(ReplyMessage.class);
            if (replies != null && replies.stream()
                    .anyMatch(reply -> String.valueOf(bot.getBotId()).equals(reply.getQq()))) return true;
        }
        return Pattern.compile("(?<!\\d)" + bot.getBotId() + "(?!\\d)").matcher(text).find()
                || bot.getBotName() != null && !bot.getBotName().isEmpty()
                && text.contains("@" + bot.getBotName());
    }

    private static boolean exited(String text) {
        return text.contains("现在什么都没干") || text.contains("未在宗门闭关")
                || text.contains("没有在宗门闭关") || text.contains("不在宗门闭关")
                || text.contains("宗门出关成功") || text.contains("闭关结束")
                || text.contains("闭关结算") || text.contains("出关捷报")
                || text.contains("闭关时长") && (text.contains("修为提升") || text.contains("修为突破"));
    }

    private static boolean entered(String text) {
        if (text.contains("失败") || text.contains("无法") || text.contains("不能")
                || text.contains("不足") || text.contains("未能")) return false;
        return text.contains("宗门闭关成功") || text.contains("开始宗门闭关")
                || (text.contains("宗门闭关") || text.contains("宗门闭关室"))
                && (text.contains("开始闭关") || text.contains("进入") || text.contains("已经在")
                || text.contains("正在") || text.contains("已在") || text.contains("在宗门闭关室中"));
    }

    private static void cancelTimer(Pending step) {
        if (step.timer != null) step.timer.cancel(false);
        step.timer = null;
    }

    private static final class Pending {
        final Bot bot;
        final long groupId;
        final boolean entering;
        final String owner;
        final BooleanSupplier valid;
        final Runnable success;
        final Runnable failure;
        final Set<Integer> replyIds = new HashSet<>();
        Phase phase;
        boolean sent;
        int retries;
        int attempt;
        ScheduledFuture<?> timer;

        Pending(Bot bot, long groupId, boolean entering, String owner, BooleanSupplier valid,
                Runnable success, Runnable failure) {
            this.bot = bot;
            this.groupId = groupId;
            this.entering = entering;
            this.owner = owner;
            this.valid = valid;
            this.success = success;
            this.failure = failure;
        }

        String command() {
            return entering ? "宗门闭关" : "宗门出关";
        }
    }
}
