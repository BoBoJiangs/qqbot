package top.sshh.qqbot.service.utils;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.service.TestService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SectCultivationManagerTest {
    private static final long BOT_ID = 90021L;
    private static final long GROUP_ID = 516625967L;
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final BotConfig config = new BotConfig();
    private final Runnable success = mock(Runnable.class);
    private final Runnable failure = mock(Runnable.class);
    private final List<Dispatch> dispatches = new ArrayList<>();
    private final List<String> notifications = new ArrayList<>();
    private final List<Job> jobs = new ArrayList<>();
    private long now;
    private SectCultivationManager manager;

    @BeforeEach
    void setUp() {
        config.setGroupId(GROUP_ID);
        config.setCultivationMode(3);
        config.setCommand("开始自动悬赏");
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotName()).thenReturn("黄瞻");
        when(bot.getBotConfig()).thenReturn(config);
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        when(group.getGroupId()).thenReturn(GROUP_ID);
        when(group.getBot()).thenReturn(bot);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS))).thenAnswer(call -> {
            Job job = new Job(call.getArgument(0), now + (long) call.getArgument(1));
            jobs.add(job);
            return job.future;
        });
        manager = new SectCultivationManager(scheduler,
                (target, groupId, message, guard, result) -> dispatches.add(
                        new Dispatch(target, groupId, Utils.getMessageText(message), guard, result)),
                () -> 0, (target, message) -> notifications.add(message));
    }

    @Test
    void busyRetriesOnlyTheExitWithBackoffAndContinuesExactlyOnce() {
        exit();
        send(0);
        assertTrue(reply("宗门系统繁忙，请稍后再试。", 1));
        reply("宗门系统繁忙，请稍后再试。", 1);
        reply("宗门系统繁忙，请稍后再试。", 2);
        advance(4);
        assertEquals(1, dispatches.size());
        advance(1);
        assertEquals(2, dispatches.size());
        assertEquals("宗门出关", dispatches.get(1).command);
        send(1);
        reply("宗门系统繁忙，请稍后再试。", 3);
        advance(14);
        assertEquals(2, dispatches.size());
        advance(1);
        send(2);
        reply("宗门出关成功，闭关结束", 4);
        reply("宗门出关成功，闭关结束", 4);
        advance(2);
        verify(success).run();
        assertFalse(manager.isTracking(bot));
        advance(500);
        assertEquals(3, dispatches.size());
        verifyNoInteractions(failure);
        assertTrue(notifications.isEmpty());
    }

    @Test
    void timeoutStartsOnlyAfterTheQueuedCommandActuallySends() {
        exit();
        advance(600);
        assertEquals(1, dispatches.size());
        assertTrue(jobs.isEmpty(), "错峰排队时不能启动回执超时");
        send(0);
        advance(29);
        assertEquals(1, dispatches.size());
        advance(1);
        advance(5);
        assertEquals(2, dispatches.size());
    }

    @Test
    void fiveRetriesWithoutAReplyPauseAndNotifyOnce() {
        exit();
        long[] waits = {5, 15, 30, 60, 120};
        for (int i = 0; i < 6; i++) {
            send(i);
            advance(30);
            if (i < 5) advance(waits[i]);
        }
        assertEquals(6, dispatches.size(), "首次发送加最多5次重试");
        assertEquals(1, notifications.size());
        verify(failure).run();
        verifyNoInteractions(success);
        assertFalse(manager.isTracking(bot));
        advance(1000);
        reply("闭关结束", 8);
        assertEquals(1, notifications.size());
        verifyNoInteractions(success);
    }

    @Test
    void failedDispatchAlsoUsesTheBoundedRetry() {
        exit();
        dispatches.get(0).result.accept(false);
        advance(5);
        assertEquals(2, dispatches.size());
        assertEquals("宗门出关", dispatches.get(1).command);
    }

    @Test
    void testServiceExhaustedBusyRetriesClearOnlyThePendingStartAndPreserveTaskTimers() throws Exception {
        TestService service = service();
        config.setXslTime(123456L);
        config.setMjTime(234567L);
        service.enableScheduled(bot, group, null, new MessageChain(), "开始自动悬赏", 1);
        long[] waits = {5, 15, 30, 60, 120};
        for (int i = 0; i < 6; i++) {
            send(i);
            service.开始自动秘境悬赏(bot, group, null, new MessageChain().at("90021"),
                    "宗门系统繁忙，请稍后再试。", i + 2);
            if (i < 5) advance(waits[i]);
        }
        assertEquals("", config.getCommand());
        assertEquals(123456L, config.getXslTime());
        assertEquals(234567L, config.getMjTime());
        assertEquals(1, notifications.size());
        assertTrue(manager.wasHandled(bot, group, 7));
        advance(1000);
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void failedEnterReceiptCannotBeMistakenForSuccessfulRecovery() {
        manager.enter(bot, GROUP_ID);
        send(0);
        assertFalse(reply("无法进入宗门闭关室，灵石不足", 1));
        advance(35);
        assertEquals(2, dispatches.size());
        assertEquals("宗门闭关", dispatches.get(1).command);
    }

    @Test
    void cancellationInvalidatesQueuedSendsRetriesAndDelayedContinuation() {
        exit();
        manager.cancel(bot);
        assertFalse(dispatches.get(0).guard.getAsBoolean());
        exit();
        send(1);
        reply("宗门系统繁忙", 1);
        manager.cancel(bot);
        advance(1000);
        assertEquals(2, dispatches.size());
        exit();
        send(2);
        reply("闭关结束", 2);
        manager.cancel(bot);
        advance(1000);
        verifyNoInteractions(success, failure);
        assertTrue(notifications.isEmpty());
    }

    @Test
    void aNewTaskInvalidatesTheOldQueueEntryEvenWithTheSameExitCommand() {
        exit();
        config.setCommand("开始自动秘境");
        manager.exit(bot, GROUP_ID, "开始自动秘境", () -> !config.isStop(), success, failure);
        assertEquals(2, dispatches.size());
        assertFalse(dispatches.get(0).guard.getAsBoolean());
        assertTrue(dispatches.get(1).guard.getAsBoolean());
    }

    @Test
    void changingConfigurationOrStoppingInvalidatesTheOperation() {
        exit();
        config.setCultivationMode(0);
        assertFalse(dispatches.get(0).guard.getAsBoolean());
        assertFalse(manager.isTracking(bot));
        config.setCultivationMode(3);
        exit();
        send(1);
        config.setStop(true);
        advance(1000);
        assertEquals(2, dispatches.size());
        verifyNoInteractions(success, failure);
        assertTrue(notifications.isEmpty());
    }

    @Test
    void anotherPlayersReplyIsIgnoredEvenInOurConfiguredGroup() {
        exit();
        send(0);
        assertFalse(manager.handleReply(bot, group, new MessageChain().at("90022"),
                "宗门系统繁忙", 1));
        assertFalse(manager.handleReply(bot, group, new MessageChain(), "@90022 闭关结束", 2));
        Group anotherGroup = mock(Group.class);
        when(anotherGroup.getGroupId()).thenReturn(123L);
        assertFalse(manager.handleReply(bot, anotherGroup, new MessageChain().at("90021"),
                "闭关结束", 3));
        advance(2);
        verifyNoInteractions(success);
        assertEquals(1, dispatches.size());
    }

    @Test
    void multipleBotsKeepTheirOwnOperationsAndReplyContexts() {
        exit();
        send(0);
        Bot second = mock(Bot.class);
        BotConfig secondConfig = new BotConfig();
        secondConfig.setCultivationMode(3);
        secondConfig.setGroupId(GROUP_ID);
        when(second.getBotId()).thenReturn(90022L);
        when(second.getBotConfig()).thenReturn(secondConfig);
        Runnable secondSuccess = mock(Runnable.class);
        manager.exit(second, GROUP_ID, "开始自动秘境", () -> true, secondSuccess, failure);
        send(1);
        assertTrue(manager.handleReply(second, group, new MessageChain().at("90022"), "闭关结束", 1));
        advance(2);
        verify(secondSuccess).run();
        verifyNoInteractions(success);
        assertTrue(manager.isTracking(bot));
    }

    @ParameterizedTest
    @ValueSource(strings = {"道友现在什么都没干呢", "道友未在宗门闭关", "宗门出关成功", "闭关结束",
            "闭关结算", "出关捷报", "闭关时长10分钟，修为提升100"})
    void successfulExitVariantsAdvanceTheOriginalTask(String message) {
        exit();
        send(0);
        assertTrue(reply(message, 1));
        advance(2);
        verify(success).run();
    }

    @ParameterizedTest
    @ValueSource(strings = {"宗门闭关成功", "道友进入宗门闭关室，开始闭关", "道友已经在宗门闭关室中"})
    void enterConfirmationStopsRecoveryWithoutReplayingAnyGameTask(String message) {
        manager.enter(bot, GROUP_ID);
        send(0);
        reply("宗门系统繁忙", 1);
        advance(5);
        send(1);
        assertEquals("宗门闭关", dispatches.get(1).command);
        assertTrue(reply(message, 2));
        advance(1000);
        assertEquals(2, dispatches.size());
        verifyNoInteractions(success, failure);
        assertTrue(notifications.isEmpty());
    }

    @Test
    void lateSuccessCancelsARetryThatHasAlreadyEnteredTheSendQueue() {
        exit();
        send(0);
        reply("宗门系统繁忙", 1);
        advance(5);
        reply("闭关结束", 2);
        assertFalse(dispatches.get(1).guard.getAsBoolean());
        advance(2);
        verify(success).run();
    }

    @Test
    void lateSettlementCannotReplaceANewerTaskWaitingForExit() {
        exit();
        manager.enter(bot, GROUP_ID);
        assertEquals(1, dispatches.size());
        assertEquals("宗门出关", dispatches.get(0).command);
    }

    @Test
    void expiredTimeoutFromAnOldAttemptCannotInterruptTheNextAttempt() {
        exit();
        send(0);
        Job oldTimeout = jobs.get(0);
        reply("宗门系统繁忙", 1);
        advance(5);
        send(1);
        oldTimeout.runnable.run(); // 模拟取消前已被线程取出的旧超时回调
        advance(20);
        assertEquals(2, dispatches.size());
    }

    @Test
    void cancelledRetryCallbackCannotOverwriteTheSuccessfulContinuation() {
        exit();
        send(0);
        reply("宗门系统繁忙", 1);
        Job cancelledRetry = jobs.get(1);
        reply("闭关结束", 2);
        cancelledRetry.runnable.run();
        assertEquals(1, dispatches.size());
        advance(2);
        verify(success).run();
    }

    @ParameterizedTest
    @ValueSource(strings = {"开始自动悬赏", "开始自动秘境"})
    void testServiceWaitsForExitThenSendsOnlyTheSelectedTask(String command) throws Exception {
        TestService service = service();
        config.setStop(true);
        service.enableScheduled(bot, group, null, new MessageChain(), command, 1);
        assertFalse(config.isStop());
        assertEquals(1, dispatches.size());
        verify(group, never()).sendMessage(any(MessageChain.class));
        send(0);
        service.开始自动秘境悬赏(bot, group, null, new MessageChain().at("90021")
                .text("宗门系统繁忙，请稍后再试。"), "", 2);
        advance(5);
        send(1);
        service.开始自动秘境悬赏(bot, group, null, new MessageChain().at("90021")
                .text("出关捷报"), "", 3);
        advance(2);
        String expected = command.equals("开始自动悬赏") ? "悬赏令刷新" : "探索秘境";
        verify(group).sendMessage(argThat((MessageChain chain) -> expected.equals(Utils.getMessageText(chain))));
        assertEquals("", config.getCommand());
        advance(1000);
        assertEquals(2, dispatches.size());
        verify(group, times(1)).sendMessage(any(MessageChain.class));
    }

    @Test
    void testServiceSwitchingTasksOrStoppingCancelsOldContinuation() throws Exception {
        TestService service = service();
        service.enableScheduled(bot, group, null, new MessageChain(), "开始自动悬赏", 1);
        send(0);
        service.开始自动秘境悬赏(bot, group, null, new MessageChain().at("90021"), "闭关结束", 2);
        service.enableScheduled(bot, group, null, new MessageChain(), "开始自动秘境", 3);
        advance(2);
        verify(group, never()).sendMessage(any(MessageChain.class));
        service.enableScheduled(bot, group, null, new MessageChain(), "停止执行", 4);
        assertFalse(dispatches.get(1).guard.getAsBoolean());
        clearInvocations(group);
        service.开始自动秘境悬赏(bot, group, null, new MessageChain().at("90021"), "闭关结束", 5);
        advance(1000);
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    private TestService service() {
        TestService service = new TestService();
        ReflectionTestUtils.setField(service, "sectCultivationManager", manager);
        ReflectionTestUtils.setField(service, "xxGroupId", 0L);
        return service;
    }

    private void exit() {
        manager.exit(bot, GROUP_ID, "开始自动悬赏",
                () -> !config.isStop() && config.getCultivationMode() == 3, success, failure);
    }

    private boolean reply(String message, int messageId) {
        return manager.handleReply(bot, group, new MessageChain().at(String.valueOf(BOT_ID)), message, messageId);
    }

    private void send(int index) {
        Dispatch dispatch = dispatches.get(index);
        assertTrue(dispatch.guard.getAsBoolean());
        dispatch.result.accept(true);
    }

    private void advance(long seconds) {
        long target = now + seconds;
        while (true) {
            Job next = jobs.stream().filter(job -> !job.cancelled && !job.ran && job.due <= target)
                    .min(Comparator.comparingLong(job -> job.due)).orElse(null);
            if (next == null) break;
            now = next.due;
            next.ran = true;
            next.runnable.run();
        }
        now = target;
    }

    private static class Job {
        final Runnable runnable;
        final long due;
        final ScheduledFuture<?> future = mock(ScheduledFuture.class);
        boolean cancelled;
        boolean ran;

        Job(Runnable runnable, long due) {
            this.runnable = runnable;
            this.due = due;
            when(future.cancel(anyBoolean())).thenAnswer(call -> {
                cancelled = true;
                return true;
            });
        }
    }

    private static class Dispatch {
        final Bot bot;
        final long groupId;
        final String command;
        final BooleanSupplier guard;
        final Consumer<Boolean> result;

        Dispatch(Bot bot, long groupId, String command, BooleanSupplier guard, Consumer<Boolean> result) {
            this.bot = bot;
            this.groupId = groupId;
            this.command = command;
            this.guard = guard;
            this.result = result;
        }
    }
}
