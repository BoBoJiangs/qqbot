package top.sshh.qqbot.service.utils;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 给小小发送自动任务指令的简化调度器。
 *
 * <p>同一群内的指令统一排队，机器人编号决定首次等待时间：1号立即发送，
 * 2号等待2秒，3号等待4秒，以此类推。未设置编号（0或负数）的机器人不增加
 * 编号等待，按进入队列的先后发送。队列本身还会保证同一群的两条指令至少间隔
 * 2秒，避免宗门、秘境、悬赏等不同模块同时撞到小小。</p>
 *
 * <p>这是单进程调度：多台服务器需要使用连续且唯一的机器人编号，并尽量让
 * 同一批任务在相近时间触发。后续如果仍有跨服务器竞争，再升级为共享队列。</p>
 */
public final class XiaoXiaoCommandScheduler {
    private static final Logger logger = LoggerFactory.getLogger(XiaoXiaoCommandScheduler.class);
    private static final String XIAO_XIAO_QQ = "3889001741";
    private static final long COMMAND_INTERVAL_MS = 2000L;

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "xiao-xiao-command-scheduler");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    /** 队列维度：小小QQ + 群号；不同群之间不相互阻塞。 */
    private static final Map<String, Long> NEXT_AVAILABLE_TIME = new ConcurrentHashMap<>();
    /** 防止定时轮询在前一条指令尚未发出时重复排入同一条指令。 */
    private static final Set<String> PENDING_COMMANDS = ConcurrentHashMap.newKeySet();

    private XiaoXiaoCommandScheduler() {
    }

    /**
     * 如果消息是艾特小小的任务指令，则按编号入队；普通消息保持同步发送。
     */
    public static boolean send(Bot bot, long groupId, MessageChain messageChain) {
        if (bot == null || groupId <= 0L || messageChain == null) {
            return false;
        }

        Group group = bot.getGroup(groupId);
        if (group == null) {
            return false;
        }

        String commandText = Utils.getMessageText(messageChain).trim();
        if (!isQueuedXiaoXiaoCommand(messageChain, commandText)) {
            group.sendMessage(messageChain);
            return true;
        }

        BotConfig botConfig = bot.getBotConfig();
        String queueKey = XIAO_XIAO_QQ + ":" + groupId;
        String pendingKey = bot.getBotId() + ":" + groupId + ":" + commandText;
        if (!PENDING_COMMANDS.add(pendingKey)) {
            return true;
        }

        long now = System.currentTimeMillis();
        long numberDelay = getNumberDelay(botConfig);
        long earliestTime = now + numberDelay;
        long scheduledTime = Math.max(earliestTime, NEXT_AVAILABLE_TIME.getOrDefault(queueKey, now));
        NEXT_AVAILABLE_TIME.put(queueKey, scheduledTime + COMMAND_INTERVAL_MS);

        long delay = Math.max(0L, scheduledTime - now);
        EXECUTOR.schedule(() -> {
            try {
                Group targetGroup = bot.getGroup(groupId);
                if (targetGroup != null) {
                    targetGroup.sendMessage(messageChain);
                }
            } catch (Exception e) {
                logger.warn("小小任务指令发送失败: botId={}, groupId={}, command={}",
                        bot.getBotId(), groupId, commandText, e);
            } finally {
                PENDING_COMMANDS.remove(pendingKey);
            }
        }, delay, TimeUnit.MILLISECONDS);
        return true;
    }

    private static long getNumberDelay(BotConfig botConfig) {
        // 0/负数表示未设置编号：不额外延迟，但仍由统一队列控制发送间隔。
        if (botConfig == null || botConfig.getBotNumber() <= 1) {
            return 0L;
        }
        return Math.min((long) (botConfig.getBotNumber() - 1) * COMMAND_INTERVAL_MS,
                TimeUnit.MINUTES.toMillis(10));
    }

    private static boolean isQueuedXiaoXiaoCommand(MessageChain messageChain, String commandText) {
        if (!containsXiaoXiaoAt(messageChain)) {
            return false;
        }
        // 目前只有宗门闭关、宗门出关需要跨机器人错峰，其他指令保持立即发送。
        return commandText.contains("宗门闭关") || commandText.contains("宗门出关");
    }

    private static boolean containsXiaoXiaoAt(MessageChain messageChain) {
        List<AtMessage> atMessages = messageChain.getMessageByType(AtMessage.class);
        if (atMessages == null) {
            return false;
        }
        for (AtMessage atMessage : atMessages) {
            if (XIAO_XIAO_QQ.equals(atMessage.getQq())) {
                return true;
            }
        }
        return false;
    }
}
