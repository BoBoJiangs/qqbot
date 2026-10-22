package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import top.sshh.qqbot.service.BotConnectionGateway.Probe;
import top.sshh.qqbot.service.BotConnectionGateway.Status;
import top.sshh.qqbot.service.BotConnectionMonitor.MonitorState;
import top.sshh.qqbot.service.storage.LocalDataFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotConnectionMonitorTest {
    private static final long MASTER = 819463350L;
    private static final long START = 1792638000000L;
    @TempDir Path directory;
    private Path path;
    private final Clock clock = mock(Clock.class);
    private final BotConnectionGateway gateway = mock(BotConnectionGateway.class);
    private final Map<Long, Bot> bots = new TreeMap<>();
    private final Map<Long, Probe> probes = new TreeMap<>();

    @BeforeEach
    void setUp() {
        path = directory.resolve("connection-state.json");
        now(START);
        when(gateway.bots()).thenAnswer(invocation -> new TreeMap<>(bots));
        when(gateway.probeAll(anyMap())).thenAnswer(invocation -> new TreeMap<>(probes));
        when(gateway.sendToMaster(anyLong(), anyString(), anyCollection(), nullable(Long.class))).thenReturn(true);
    }

    @Test
    void firstInstallationOnlyBuildsBaselineWithoutSending() throws Exception {
        BotConnectionMonitor monitor = start();
        online(11L, "甲", MASTER);
        monitor.poll();
        now(START + 300000L);
        monitor.poll();

        verify(gateway, never()).sendToMaster(anyLong(), anyString(), anyCollection(), any());
        assertEquals(Status.ONLINE, saved().latest.bots.get(11L).status);
        assertTrue(saved().restartBaseline.isEmpty());
        assertFalse(java.nio.file.Files.exists(path), "状态应写入 SQLite，而非额外写 JSON 文件");
    }

    @Test
    void waitsFiveMinutesFromReadyAndBatchesOnlyPreviouslyOnlineBots() throws Exception {
        BotConnectionMonitor before = start();
        online(11L, "机器人甲", MASTER);
        Bot second = online(22L, "机器人乙", MASTER);
        online(33L, "重启前已离线", MASTER);
        probes.put(33L, new Probe(Status.OFFLINE, "QQ 已离线"));
        before.poll();
        before.prepareForRestart();

        now(START + 10000L);
        bots.remove(11L);
        probes.remove(11L);
        probes.put(22L, new Probe(Status.OFFLINE, "QQ 已离线"));
        BotConnectionMonitor after = start();
        after.onConnected(second);
        after.poll();
        assertEquals(2, saved().restartBaseline.size(), "启动期间不能覆盖上轮在线名单");

        now(START + 309999L);
        after.poll();
        verify(gateway, never()).sendToMaster(anyLong(), anyString(), anyCollection(), any());
        now(START + 310000L);
        after.poll();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(gateway).sendToMaster(eq(MASTER), message.capture(), anyCollection(), isNull());
        assertTrue(message.getValue().contains("重启前在线：2 个"));
        assertTrue(message.getValue().contains("机器人甲（11）"));
        assertTrue(message.getValue().contains("尚未重新连接"));
        assertTrue(message.getValue().contains("机器人乙（22）"));
        assertTrue(message.getValue().contains("QQ 已离线"));
        assertFalse(message.getValue().contains("重启前已离线"));
        assertTrue(saved().restartBaseline.isEmpty());
        after.poll();
        verify(gateway, times(1)).sendToMaster(anyLong(), anyString(), anyCollection(), any());
    }

    @Test
    void allRecoveredProducesNoSuccessMessage() throws Exception {
        online(11L, "甲", MASTER);
        start().poll();
        BotConnectionMonitor after = start();
        now(START + 300000L);
        after.poll();

        assertTrue(saved().restartBaseline.isEmpty());
        verify(gateway, never()).sendToMaster(anyLong(), anyString(), anyCollection(), any());
    }

    @Test
    void retryRechecksRecoveryAndSelectsOnlyOnlineSenders() throws Exception {
        online(11L, "甲", MASTER);
        online(22L, "乙", MASTER);
        start().poll();
        bots.clear();
        probes.clear();
        BotConnectionMonitor after = start();
        now(START + 300000L);
        when(gateway.sendToMaster(anyLong(), anyString(), anyCollection(), any())).thenReturn(false);
        after.poll();
        assertEquals(2, saved().restartBaseline.size());
        now(START + 330000L);
        after.poll();
        verify(gateway, times(1)).sendToMaster(anyLong(), anyString(), anyCollection(), any());

        Bot recovered = online(11L, "甲", MASTER);
        now(START + 360000L);
        when(gateway.sendToMaster(anyLong(), anyString(), anyCollection(), any())).thenReturn(true);
        after.poll();
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Collection<Bot>> senders = ArgumentCaptor.forClass(Collection.class);
        verify(gateway, times(2)).sendToMaster(eq(MASTER), messages.capture(), senders.capture(), isNull());
        assertTrue(senders.getAllValues().get(0).isEmpty());
        assertEquals(Collections.singletonList(recovered), new ArrayList<>(senders.getValue()));
        assertFalse(messages.getValue().contains("甲（11）"));
        assertTrue(messages.getValue().contains("乙（22）"));
    }

    @Test
    void undeliveredBaselineSurvivesRepeatedApplicationRestarts() throws Exception {
        online(11L, "不能丢失的账号", MASTER);
        start().poll();
        bots.clear();
        probes.clear();
        BotConnectionMonitor secondRun = start();
        secondRun.poll();
        secondRun.prepareForRestart();
        BotConnectionMonitor thirdRun = start();
        thirdRun.poll();
        now(START + 300000L);
        thirdRun.poll();

        verify(gateway).sendToMaster(eq(MASTER), contains("不能丢失的账号（11）"), anyCollection(), isNull());
    }

    @Test
    void notificationsAreGroupedByConfiguredMasterAndSuccessfulGroupsAreNotRetried() throws Exception {
        online(11L, "甲", MASTER);
        online(22L, "乙", 123456L);
        start().poll();
        bots.clear();
        probes.clear();
        BotConnectionMonitor after = start();
        now(START + 300000L);
        when(gateway.sendToMaster(eq(123456L), anyString(), anyCollection(), any())).thenReturn(false);
        after.poll();
        assertEquals(Collections.singleton(22L), saved().restartBaseline.keySet());
        now(START + 360000L);
        after.poll();

        verify(gateway, times(1)).sendToMaster(eq(MASTER), anyString(), anyCollection(), any());
        verify(gateway, times(2)).sendToMaster(eq(123456L), anyString(), anyCollection(), any());
    }

    @Test
    void offlineNotificationRetriesAndDeduplicatesUntilAccountActuallyRecovers() throws Exception {
        Bot lost = online(11L, "甲", MASTER);
        online(22L, "乙", MASTER);
        BotConnectionMonitor monitor = start();
        monitor.poll();
        probes.put(11L, new Probe(Status.OFFLINE, "QQ 已离线"));
        when(gateway.sendToMaster(anyLong(), anyString(), anyCollection(), any())).thenReturn(false, true, true);
        monitor.onOffline(lost, "账号在其他设备登录");
        monitor.poll();
        assertFalse(saved().offlineNotices.isEmpty());
        now(START + 60000L);
        monitor.poll();
        monitor.onOffline(lost, "重复掉线事件");
        now(START + 120000L);
        monitor.poll();
        verify(gateway, times(2)).sendToMaster(eq(MASTER), contains("掉线啦"), anyCollection(), eq(11L));

        probes.put(11L, new Probe(Status.ONLINE, "在线"));
        monitor.poll();
        probes.put(11L, new Probe(Status.OFFLINE, "QQ 已离线"));
        monitor.onOffline(lost, "再次掉线");
        monitor.poll();
        verify(gateway, times(3)).sendToMaster(eq(MASTER), contains("掉线啦"), anyCollection(), eq(11L));
    }

    @Test
    void startupOfflineEventsAreMergedIntoRestartNotice() throws Exception {
        Bot bot = online(11L, "甲", MASTER);
        start().poll();
        probes.put(11L, new Probe(Status.OFFLINE, "QQ 已离线"));
        BotConnectionMonitor after = start();
        after.onOffline(bot, "掉线事件");
        after.poll();
        verify(gateway, never()).sendToMaster(anyLong(), anyString(), anyCollection(), any());
        now(START + 300000L);
        after.poll();
        verify(gateway).sendToMaster(eq(MASTER), contains("重启后连接异常"), anyCollection(), isNull());
        assertTrue(saved().offlineNotices.isEmpty());
    }

    @Test
    void stoppingFreezesSnapshotBeforeBotsAreRemovedAndFailedRestartCanResume() throws Exception {
        online(11L, "甲", MASTER);
        BotConnectionMonitor monitor = start();
        monitor.poll();
        monitor.onContextClosed();
        bots.clear();
        probes.clear();
        monitor.poll();
        assertEquals(Status.ONLINE, saved().latest.bots.get(11L).status);
        monitor.cancelRestart();
        monitor.poll();
        assertEquals(Status.DISCONNECTED, saved().latest.bots.get(11L).status);
    }

    @Test
    void newerOfflineEventIsNotOverwrittenByAnEarlierOnlineProbe() throws Exception {
        Bot lost = online(11L, "甲", MASTER);
        online(22L, "乙", MASTER);
        BotConnectionMonitor monitor = start();
        monitor.poll();
        when(gateway.probeAll(anyMap())).thenAnswer(invocation -> {
            monitor.onOffline(lost, "查询过程中掉线");
            return new TreeMap<>(probes);
        });
        monitor.poll();

        assertEquals(Status.OFFLINE, saved().latest.bots.get(11L).status);
        verify(gateway).sendToMaster(eq(MASTER), contains("查询过程中掉线"), anyCollection(), eq(11L));
    }

    @Test
    void malformedSnapshotIsPreservedInsteadOfOverwritten() throws Exception {
        LocalDataFiles.writeString(path, "{broken-json", StandardCharsets.UTF_8);
        BotConnectionMonitor monitor = start();
        online(11L, "甲", MASTER);
        monitor.poll();
        assertEquals("{broken-json", LocalDataFiles.readString(path));
    }

    private BotConnectionMonitor start() {
        BotConnectionMonitor monitor = new BotConnectionMonitor(gateway, path, clock);
        monitor.load();
        monitor.onApplicationReady();
        return monitor;
    }

    private Bot online(long id, String name, long master) {
        Bot bot = mock(Bot.class);
        BotConfig config = new BotConfig();
        config.setMasterQQ(master);
        when(bot.getBotId()).thenReturn(id);
        when(bot.getBotName()).thenReturn(name);
        when(bot.getBotConfig()).thenReturn(config);
        bots.put(id, bot);
        probes.put(id, new Probe(Status.ONLINE, "在线"));
        return bot;
    }

    private void now(long millis) {
        when(clock.millis()).thenReturn(millis);
    }

    private MonitorState saved() throws Exception {
        return JSON.parseObject(LocalDataFiles.readString(path), MonitorState.class);
    }
}
