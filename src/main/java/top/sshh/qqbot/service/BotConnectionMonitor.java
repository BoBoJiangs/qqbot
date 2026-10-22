package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.annotation.OnQQConnected;
import com.zhuangxv.bot.core.Bot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.service.BotConnectionGateway.Probe;
import top.sshh.qqbot.service.BotConnectionGateway.Status;
import top.sshh.qqbot.service.storage.LocalDataFiles;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** 上轮在线快照与本轮运行状态分开保存，支持正常重启、断电及通知失败后再次重启。 */
@Component
public class BotConnectionMonitor {
    private static final Logger logger = LoggerFactory.getLogger(BotConnectionMonitor.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final BotConnectionGateway gateway;
    private final Path statePath;
    private final Clock clock;
    private final ReentrantLock polling = new ReentrantLock();
    @Value("${bot-connection-monitor.enabled:true}")
    private boolean enabled = true;
    @Value("${bot-connection-monitor.startup-delay-ms:300000}")
    private long startupDelayMs = 300000L;
    @Value("${bot-connection-monitor.retry-interval-ms:60000}")
    private long retryIntervalMs = 60000L;
    private MonitorState state = new MonitorState();
    private volatile boolean ready;
    private volatile boolean stopping;
    private long readyAt;
    private long nextRestartAttemptAt;
    private long eventVersion;
    private final Map<Long, Long> eventVersions = new HashMap<>();

    @Autowired
    public BotConnectionMonitor(BotConnectionGateway gateway) {
        this(gateway, Paths.get("./config/bot-connection-state.json"), Clock.systemUTC());
    }

    BotConnectionMonitor(BotConnectionGateway gateway, Path statePath, Clock clock) {
        this.gateway = gateway;
        this.statePath = statePath;
        this.clock = clock;
    }

    @PostConstruct
    public synchronized void load() {
        if (!enabled) return;
        try {
            if (LocalDataFiles.exists(statePath)) {
                MonitorState saved = JSON.parseObject(LocalDataFiles.readString(statePath), MonitorState.class);
                if (saved == null || saved.latest == null || saved.latest.bots == null
                        || saved.restartBaseline == null || saved.offlineNotices == null || saved.offlineNotified == null) {
                    throw new IllegalStateException("连接监测记录结构不完整");
                }
                state = saved;
            }
            // 未送达的上轮异常继续保留；正常运行快照只取当时确认在线的账号。
            for (ConnectionRecord record : state.latest.bots.values()) {
                if (record.status == Status.ONLINE) state.restartBaseline.put(record.botId, record);
            }
            if (!state.latest.bots.isEmpty()) state.baselineCapturedAt = state.latest.capturedAt;
            state.latest = new Snapshot();
            persist();
            logger.info("连接监测已加载重启前在线账号 {} 个", state.restartBaseline.size());
        } catch (Exception e) {
            // 数据异常时不凭配置推断此前在线名单，也不覆盖损坏的原始记录。
            enabled = false;
            logger.error("连接监测记录加载失败，本轮停用监测以保留原始记录", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onApplicationReady() {
        if (!enabled || ready) return;
        readyAt = clock.millis();
        nextRestartAttemptAt = readyAt + startupDelayMs;
        ready = true;
        logger.info("连接监测启动，将在应用就绪 {} 秒后检查重启恢复情况", startupDelayMs / 1000L);
    }

    @OnQQConnected
    public synchronized void onConnected(Bot bot) {
        if (!enabled || stopping || bot.getBotId() <= 0L) return;
        ConnectionRecord record = record(bot, state.latest.bots.get(bot.getBotId()));
        record.status = Status.UNKNOWN;
        record.detail = "连接已建立，等待在线检查";
        state.latest.bots.put(record.botId, record);
        eventVersions.put(record.botId, ++eventVersion);
        saveSnapshot("连接建立");
    }

    /** 由原有掉线事件处理器调用，通知失败保留到后续检查，避免占用消息处理线程。 */
    public synchronized void onOffline(Bot bot, String reason) {
        if (!enabled || stopping) return;
        ConnectionRecord record = record(bot, state.latest.bots.get(bot.getBotId()));
        record.status = Status.OFFLINE;
        record.detail = reason == null ? "QQ 已离线" : reason;
        state.latest.bots.put(record.botId, record);
        eventVersions.put(record.botId, ++eventVersion);
        if (!state.restartBaseline.containsKey(record.botId) && !state.offlineNotified.contains(record.botId)
                && record.masterQQ > 0L) {
            state.offlineNotices.computeIfAbsent(record.botId, id -> {
                OfflineNotice notice = new OfflineNotice();
                notice.bot = record;
                notice.occurredAt = clock.millis();
                return notice;
            });
        }
        saveSnapshot("账号掉线");
    }

    @Scheduled(fixedDelayString = "${bot-connection-monitor.snapshot-interval-ms:30000}")
    public void poll() {
        if (!enabled || !ready || stopping || !polling.tryLock()) return;
        try {
            long pollVersion;
            synchronized (this) {
                pollVersion = eventVersion;
            }
            Map<Long, Bot> bots = gateway.bots();
            Map<Long, Probe> probes = gateway.probeAll(bots);
            List<NoticeAttempt> attempts;
            synchronized (this) {
                if (stopping) return;
                refresh(bots, probes, pollVersion);
                attempts = notices();
                saveSnapshot("定时检查");
            }
            List<Bot> online = bots.entrySet().stream()
                    .filter(entry -> probes.containsKey(entry.getKey())
                            && probes.get(entry.getKey()).status == Status.ONLINE)
                    .map(Map.Entry::getValue).collect(Collectors.toList());
            for (NoticeAttempt attempt : attempts) {
                if (stopping) break;
                boolean sent = gateway.sendToMaster(attempt.masterQQ, attempt.message, online, attempt.excludedBotId);
                synchronized (this) {
                    if (sent) acknowledge(attempt);
                    persist();
                }
                if (!sent) logger.warn("连接异常通知暂未送达，将重试 masterQQ={}", attempt.masterQQ);
            }
        } catch (Exception e) {
            logger.error("机器人连接检查失败，将在下次检查重试", e);
        } finally {
            polling.unlock();
        }
    }

    private void refresh(Map<Long, Bot> bots, Map<Long, Probe> probes, long pollVersion) {
        for (Bot bot : bots.values()) {
            if (bot.getBotId() <= 0L) continue;
            // API 查询期间到达的掉线/连接事件更新，不能被更早的查询结果覆盖。
            if (eventVersions.getOrDefault(bot.getBotId(), 0L) > pollVersion) continue;
            ConnectionRecord record = record(bot, state.latest.bots.get(bot.getBotId()));
            Probe probe = probes.get(bot.getBotId());
            record.status = probe == null ? Status.UNKNOWN : probe.status;
            record.detail = probe == null ? "在线状态无法确认" : probe.detail;
            if (record.status == Status.ONLINE) {
                record.lastOnlineAt = clock.millis();
                state.offlineNotices.remove(record.botId);
                state.offlineNotified.remove(record.botId);
            }
            state.latest.bots.put(record.botId, record);
        }
        // 某些断连场景会把 Bot 从工厂中移除，仍需保留 QQ、昵称和 masterQQ。
        for (ConnectionRecord baseline : state.restartBaseline.values()) {
            state.latest.bots.putIfAbsent(baseline.botId, copy(baseline));
        }
        for (ConnectionRecord record : state.latest.bots.values()) {
            if (!bots.containsKey(record.botId) && eventVersions.getOrDefault(record.botId, 0L) <= pollVersion) {
                record.status = Status.DISCONNECTED;
                record.detail = "尚未重新连接";
            }
        }
    }

    private List<NoticeAttempt> notices() {
        List<NoticeAttempt> attempts = new ArrayList<>();
        long now = clock.millis();
        if (!state.restartBaseline.isEmpty() && now >= nextRestartAttemptAt) {
            Map<Long, List<ConnectionRecord>> masters = state.restartBaseline.values().stream()
                    .collect(Collectors.groupingBy(record -> record.masterQQ, TreeMap::new, Collectors.toList()));
            for (Map.Entry<Long, List<ConnectionRecord>> entry : masters.entrySet()) {
                List<ConnectionRecord> missing = entry.getValue().stream()
                        .map(record -> state.latest.bots.get(record.botId))
                        .filter(record -> record.status != Status.ONLINE).collect(Collectors.toList());
                if (missing.isEmpty() || entry.getKey() <= 0L) {
                    entry.getValue().forEach(record -> state.restartBaseline.remove(record.botId));
                    logger.info("重启连接检查完成 masterQQ={}，异常账号数={}", entry.getKey(), missing.size());
                    continue;
                }
                NoticeAttempt attempt = new NoticeAttempt();
                attempt.masterQQ = entry.getKey();
                attempt.restart = true;
                entry.getValue().forEach(record -> attempt.checkedBotIds.add(record.botId));
                missing.forEach(record -> attempt.missingBotIds.add(record.botId));
                StringBuilder message = new StringBuilder("【JavaBot 重启后连接异常】\n\n")
                        .append("程序启动完成已超过 ").append(startupDelayMs / 60000L).append(" 分钟。\n")
                        .append("重启前在线：").append(entry.getValue().size()).append(" 个\n")
                        .append("当前已恢复：").append(entry.getValue().size() - missing.size()).append(" 个\n")
                        .append("仍未恢复：").append(missing.size()).append(" 个\n");
                for (ConnectionRecord record : missing) {
                    message.append("\n").append(record.botName).append("（").append(record.botId).append("）\n")
                            .append("状态：").append(record.detail).append("\n")
                            .append("最后确认在线：").append(format(record.lastOnlineAt)).append("\n");
                }
                message.append("\n重启前记录时间：").append(format(state.baselineCapturedAt))
                        .append("\n程序启动时间：").append(format(readyAt))
                        .append("\n检查时间：").append(format(now));
                attempt.message = message.toString();
                attempts.add(attempt);
            }
            nextRestartAttemptAt = now + retryIntervalMs;
        }
        for (OfflineNotice notice : state.offlineNotices.values()) {
            if (state.restartBaseline.containsKey(notice.bot.botId) || now < notice.nextAttemptAt) continue;
            NoticeAttempt attempt = new NoticeAttempt();
            attempt.masterQQ = notice.bot.masterQQ;
            attempt.excludedBotId = notice.bot.botId;
            attempt.missingBotIds.add(notice.bot.botId);
            attempt.message = "【机器人掉线通知】\n\n账号：" + notice.bot.botId + "（" + notice.bot.botName
                    + "）掉线啦\n状态：" + notice.bot.detail + "\n掉线时间：" + format(notice.occurredAt);
            notice.nextAttemptAt = now + retryIntervalMs;
            attempts.add(attempt);
        }
        return attempts;
    }

    private void acknowledge(NoticeAttempt attempt) {
        if (attempt.restart) attempt.checkedBotIds.forEach(state.restartBaseline::remove);
        for (Long id : attempt.missingBotIds) {
            state.offlineNotices.remove(id);
            state.offlineNotified.add(id);
        }
    }

    /** 在重启脚本及 Spring 销毁 Bot 连接之前冻结最近一次实际检测结果，不在关机阶段请求网络。 */
    public synchronized void prepareForRestart() {
        if (!enabled || stopping) return;
        stopping = true;
        saveSnapshot("程序停止前");
    }

    public synchronized void cancelRestart() {
        stopping = false;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        prepareForRestart();
    }

    private ConnectionRecord record(Bot bot, ConnectionRecord previous) {
        if (previous == null) previous = state.restartBaseline.get(bot.getBotId());
        ConnectionRecord result = new ConnectionRecord();
        result.botId = bot.getBotId();
        result.botName = bot.getBotName() == null ? "未知昵称" : bot.getBotName();
        result.masterQQ = bot.getBotConfig().getMasterQQ();
        result.lastOnlineAt = previous == null ? 0L : previous.lastOnlineAt;
        return result;
    }

    private ConnectionRecord copy(ConnectionRecord record) {
        return JSON.parseObject(JSON.toJSONString(record), ConnectionRecord.class);
    }

    private String format(long millis) {
        return millis <= 0L ? "未知" : TIME.format(Instant.ofEpochMilli(millis));
    }

    private void saveSnapshot(String reason) {
        state.latest.capturedAt = clock.millis();
        state.latest.reason = reason;
        persist();
    }

    private void persist() {
        try {
            LocalDataFiles.writeString(statePath, JSON.toJSONString(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("连接监测记录保存失败，异常重启后可能无法读取最新连接状态", e);
        }
    }

    public static class MonitorState {
        public Snapshot latest = new Snapshot();
        public Map<Long, ConnectionRecord> restartBaseline = new TreeMap<>();
        public long baselineCapturedAt;
        public Map<Long, OfflineNotice> offlineNotices = new TreeMap<>();
        public Set<Long> offlineNotified = new HashSet<>();
    }

    public static class Snapshot {
        public long capturedAt;
        public String reason;
        public Map<Long, ConnectionRecord> bots = new TreeMap<>();
    }

    public static class ConnectionRecord {
        public long botId;
        public String botName;
        public long masterQQ;
        public Status status = Status.UNKNOWN;
        public String detail;
        public long lastOnlineAt;
    }

    public static class OfflineNotice {
        public ConnectionRecord bot;
        public long occurredAt;
        public long nextAttemptAt;
    }

    private static class NoticeAttempt {
        long masterQQ;
        String message;
        boolean restart;
        Long excludedBotId;
        Set<Long> checkedBotIds = new HashSet<>();
        Set<Long> missingBotIds = new HashSet<>();
    }
}
