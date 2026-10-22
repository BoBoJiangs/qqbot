package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Button;
import com.zhuangxv.bot.core.Buttons;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.MiYuExploreContext;
import top.sshh.qqbot.service.utils.Utils;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameTaskRuntimePersistenceTest {
    @TempDir Path directory;
    private static final long BOT_ID = 9988776601L;
    private static final long GROUP_ID = 66778899L;
    private GameTaskStateStore store;
    private Bot bot;
    private BotConfig config;
    private Group group;

    @BeforeEach
    void setUp() {
        store = new GameTaskStateStore(directory);
        bot = mock(Bot.class);
        config = new BotConfig();
        config.setEnableAutoSecret(true);
        config.setRewardMode(2);
        config.setGroupId(GROUP_ID);
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotConfig()).thenReturn(config);
        group = mock(Group.class);
        when(group.getGroupId()).thenReturn(GROUP_ID);
        when(group.getBot()).thenReturn(bot);
    }

    private TestService service() {
        TestService service = new TestService();
        ReflectionTestUtils.setField(service, "gameTaskStateStore", new GameTaskStateStore(directory));
        ReflectionTestUtils.setField(service, "xxGroupId", 0L);
        return service;
    }

    @Test
    void allThreeAbsoluteDeadlinesSurviveCreatingANewServiceAndBot() {
        long due = System.currentTimeMillis() - 3600000L;
        store.setMjTime(BOT_ID, due);
        store.setXslTime(BOT_ID, due + 123L);
        store.setMyTime(BOT_ID, due + 456L);

        TestService restarted = service();
        restarted.restoreGameTaskState(bot);
        assertEquals(due, config.getMjTime());
        assertEquals(due + 123L, config.getXslTime());
        Map<?, ?> myTimes = (Map<?, ?>) ReflectionTestUtils.getField(restarted, "myTimeMap");
        assertEquals(due + 456L, myTimes.get(BOT_ID), "停机超过到期时间也应保留原到期时间");
        verify(bot, never()).sendGroupMessage(anyLong(), any());
    }

    @Test
    void secretRealmMessagePersistsTimeImmediatelyAndTerminalMessageClearsIt() throws Exception {
        TestService first = service();
        first.秘境(bot, group, null, new MessageChain(), "@" + BOT_ID + " 道友已经参加过本次秘境", 1);
        assertEquals(-1L, store.load(BOT_ID).mjTime);
        first.秘境(bot, group, null, new MessageChain(), "@" + BOT_ID + " 秘境昭告 勘历天时：5分钟", 2);
        long due = config.getMjTime();
        assertTrue(due > System.currentTimeMillis());
        assertEquals(due, new GameTaskStateStore(directory).load(BOT_ID).mjTime);

        first.秘境(bot, group, null, new MessageChain(), "@" + BOT_ID + " 道友已经参加过本次秘境", 3);
        service().restoreGameTaskState(bot);
        assertEquals(-1L, config.getMjTime(), "完成后的旧时间不能在下一次启动复活");
    }

    @Test
    void bountyUpdatesAndClearsItsOwnDeadlineWithoutOverwritingSecretRealm() {
        store.setMjTime(BOT_ID, 123456L);
        TestService first = service();
        ReflectionTestUtils.invokeMethod(first, "setXslTime", bot, 987654L);
        assertEquals(123456L, store.load(BOT_ID).mjTime);
        assertEquals(987654L, store.load(BOT_ID).xslTime);
        ReflectionTestUtils.invokeMethod(first, "setXslTime", bot, -1L);
        service().restoreGameTaskState(bot);
        assertEquals(-1L, config.getXslTime());
        assertEquals(123456L, config.getMjTime());
    }

    @Test
    void enteringMiYuPersistsDeadlineAndAtomicallyFinishesPreparation() {
        MiYuExploreContext context = new MiYuExploreContext();
        context.setState(MiYuExploreContext.State.WAIT_DEPARTURE);
        store.setExploration(BOT_ID, context);
        TestService first = service();
        first.秘域(bot, group, null, new MessageChain(),
                "@" + BOT_ID + " 道友已入【幽都】，约 90 分钟后可发『秘域结算』", 1);

        GameTaskStateStore.State saved = store.load(BOT_ID);
        assertNotNull(saved.myTime);
        assertTrue(saved.myTime > System.currentTimeMillis());
        assertNull(saved.exploration, "有了探索到期时间就不应再恢复准备出发流程");
        first.秘域(bot, group, null, new MessageChain(), "@" + BOT_ID + " 秘域结算成功", 2);
        assertNull(store.load(BOT_ID).myTime);
        assertNull(store.load(BOT_ID).exploration);
    }

    @Test
    void inProgressMiYuResponseReplacesDeadlineAndUnparseableResponseDoesNotDiscardIt() {
        store.setMyTime(BOT_ID, 123456L);
        TestService first = service();
        first.秘域(bot, group, null, new MessageChain(),
                "@" + BOT_ID + " 秘域探索仍在进行，约还需 79 分钟", 1);
        long due = store.load(BOT_ID).myTime;
        assertTrue(due > System.currentTimeMillis());
        first.秘域(bot, group, null, new MessageChain(),
                "@" + BOT_ID + " 秘域探索仍在进行，剩余分钟暂不可用", 2);
        assertEquals(due, store.load(BOT_ID).myTime);
    }

    @Test
    void explorationRestoresParametersAndNormalizesInterruptedClicksWithoutReplayingButtons() {
        MiYuExploreContext context = new MiYuExploreContext();
        context.setRoutes(Arrays.asList(Arrays.asList("青石道", "试剑崖", "弑师坪")));
        context.setForbiddenZone(true);
        context.setPills(Arrays.asList("天命血凝丹", "归藏灵丹", "九阳真丹"));
        context.setRefreshCount(4);
        context.setState(MiYuExploreContext.State.CLICKING_PILLS);
        context.setCreateTime(123456L);
        context.setLastActiveTime(234567L);
        store.setExploration(BOT_ID, context);

        TestService restarted = service();
        restarted.restoreGameTaskState(bot);
        Map<?, ?> contexts = (Map<?, ?>) ReflectionTestUtils.getField(restarted, "miyuExploreMap");
        MiYuExploreContext restored = (MiYuExploreContext) contexts.get(BOT_ID);
        assertEquals(MiYuExploreContext.State.WAIT_PILLS, restored.getState());
        assertEquals(context.getRoutes(), restored.getRoutes());
        assertEquals(context.getPills(), restored.getPills());
        assertTrue(restored.isForbiddenZone());
        assertEquals(4, restored.getRefreshCount());
        assertEquals(123456L, restored.getCreateTime());
        assertEquals(234567L, restored.getLastActiveTime());
        verify(bot, never()).clickKeyboardButton(anyLong(), anyString(), anyString(), anyString(), anyString());

        restored.setRefreshCount(5);
        restored.setState(MiYuExploreContext.State.WAIT_DEPARTURE);
        assertEquals(5, store.load(BOT_ID).exploration.getRefreshCount());
        assertEquals(MiYuExploreContext.State.WAIT_DEPARTURE, store.load(BOT_ID).exploration.getState());
    }

    @Test
    void clearingExplorationCannotBeUndoneByALateCallbackFromOldContext() {
        TestService first = service();
        MiYuExploreContext context = new MiYuExploreContext();
        ReflectionTestUtils.invokeMethod(first, "putMiYuContext", BOT_ID, context);
        ReflectionTestUtils.invokeMethod(first, "removeMiYuContext", BOT_ID);
        context.setState(MiYuExploreContext.State.WAIT_MAP);
        assertNull(new GameTaskStateStore(directory).load(BOT_ID).exploration);
    }

    @Test
    void singleLineQuickStartPersistsItsPreparationAndRestoresWithoutRepeatingDeparture() throws Exception {
        config.setCultivationMode(0);
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        TestService first = service();
        first.enableScheduled(bot, group, null, new MessageChain(), "开始自动秘域", 1);
        MiYuExploreContext saved = store.load(BOT_ID).exploration;
        assertNotNull(saved);
        assertTrue(saved.isQuickStart());
        assertEquals(MiYuExploreContext.State.WAIT_DEPARTURE, saved.getState());
        org.mockito.ArgumentCaptor<MessageChain> sent = org.mockito.ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(sent.capture());
        assertEquals("秘域快捷开始", Utils.getMessageText(sent.getValue()));

        clearInvocations(group);
        TestService restarted = service();
        restarted.restoreGameTaskState(bot);
        restarted.resumeMiYuExploration(bot);
        restarted.resumeMiYuExploration(bot);
        verify(group, times(1)).sendMessage(sent.capture());
        assertEquals("探索秘域", Utils.getMessageText(sent.getValue()), "先查询当前状态，不重发出发指令");
        verify(bot, never()).clickKeyboardButton(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void waitingForCultivationRestoresTheSecretExplorationCommandAndQueriesExit() {
        config.setCultivationMode(2);
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        MiYuExploreContext context = new MiYuExploreContext();
        store.setExploration(BOT_ID, context);
        TestService restarted = service();
        restarted.restoreGameTaskState(bot);
        restarted.resumeMiYuExploration(bot);
        assertEquals("开始自动秘域", config.getCommand());
        org.mockito.ArgumentCaptor<MessageChain> sent = org.mockito.ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(sent.capture());
        assertEquals("出关", Utils.getMessageText(sent.getValue()));
        assertEquals(MiYuExploreContext.State.WAIT_START, store.load(BOT_ID).exploration.getState());
    }

    @Test
    void confirmedEntryDetachesOldPreparationSoLaterCallbacksCannotReviveIt() {
        TestService first = service();
        first.restoreGameTaskState(bot);
        MiYuExploreContext old = new MiYuExploreContext();
        ReflectionTestUtils.invokeMethod(first, "putMiYuContext", BOT_ID, old);
        ReflectionTestUtils.invokeMethod(first, "setMyTime", bot, 987654L);
        old.setState(MiYuExploreContext.State.CLICKING_PILLS);
        assertNull(store.load(BOT_ID).exploration);
        assertEquals(987654L, store.load(BOT_ID).myTime);
        Map<?, ?> contexts = (Map<?, ?>) ReflectionTestUtils.getField(first, "miyuExploreMap");
        assertFalse(contexts.containsKey(BOT_ID));
    }

    @Test
    void overdueMiYuSettlementRequestDoesNotEraseTimeBeforeGameConfirmation() {
        long due = System.currentTimeMillis() - 3600000L;
        store.setMyTime(BOT_ID, due);
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        Bot previous = BotFactory.getBots().put(BOT_ID, bot);
        try {
            service().结算();
            assertEquals(due, store.load(BOT_ID).myTime);
            org.mockito.ArgumentCaptor<MessageChain> sent = org.mockito.ArgumentCaptor.forClass(MessageChain.class);
            verify(group).sendMessage(sent.capture());
            assertEquals("秘域结算", Utils.getMessageText(sent.getValue()));
        } finally {
            BotFactory.getBots().remove(BOT_ID);
            if (previous != null) BotFactory.getBots().put(BOT_ID, previous);
        }
    }

    @Test
    void manualStopCancelsSavedAutomaticTasksSoRestartCannotResumeThem() throws Exception {
        store.setMjTime(BOT_ID, 123456L);
        store.setXslTime(BOT_ID, 234567L);
        store.setMyTime(BOT_ID, 345678L);
        TestService first = service();
        first.enableScheduled(bot, group, null, new MessageChain(), "停止执行", 1);
        service().restoreGameTaskState(bot);
        assertEquals(-1L, config.getMjTime());
        assertEquals(-1L, config.getXslTime());
        assertNull(store.load(BOT_ID).myTime);
        assertNull(store.load(BOT_ID).exploration);
    }

    @Test
    void disconnectedButtonClickPreservesPreparationAndRequestsFreshStateOnNextAttempt() throws Exception {
        CountDownLatch savedForRecovery = new CountDownLatch(1);
        GameTaskStateStore trackingStore = new GameTaskStateStore(directory) {
            @Override
            public synchronized void setExploration(long botId, MiYuExploreContext context) {
                super.setExploration(botId, context);
                if (context != null && context.getState() == MiYuExploreContext.State.WAIT_MAP) {
                    savedForRecovery.countDown();
                }
            }
        };
        TestService first = service();
        ReflectionTestUtils.setField(first, "gameTaskStateStore", trackingStore);
        first.restoreGameTaskState(bot);
        MiYuExploreContext context = new MiYuExploreContext();
        context.setState(MiYuExploreContext.State.CLICKING_ROUTE);
        context.setRefreshCount(3);
        ReflectionTestUtils.invokeMethod(first, "putMiYuContext", BOT_ID, context);
        Buttons buttons = mock(Buttons.class);
        when(buttons.getGroupId()).thenReturn(GROUP_ID);
        when(buttons.getBotAppid()).thenReturn("app");
        when(buttons.getMsgSeq()).thenReturn("seq");
        Button button = mock(Button.class);
        when(button.getId()).thenReturn("region");
        when(button.getData()).thenReturn("data");
        doThrow(new IllegalStateException("连接断开")).when(bot)
                .clickKeyboardButton(GROUP_ID, "app", "region", "data", "seq");
        ReflectionTestUtils.invokeMethod(first, "clickMiYuButtons", bot, buttons,
                Arrays.asList(button), context, MiYuExploreContext.State.WAIT_PILLS);
        assertTrue(savedForRecovery.await(5L, TimeUnit.SECONDS));
        assertEquals(MiYuExploreContext.State.WAIT_MAP, store.load(BOT_ID).exploration.getState());
        assertEquals(3, store.load(BOT_ID).exploration.getRefreshCount());
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        // 回调在保存之后标记恢复意图；新建服务模拟后续重新连接。
        TestService restarted = service();
        restarted.restoreGameTaskState(bot);
        restarted.resumeMiYuExploration(bot);
        org.mockito.ArgumentCaptor<MessageChain> sent = org.mockito.ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(sent.capture());
        assertEquals("探索秘域", Utils.getMessageText(sent.getValue()));
    }

    @Test
    void restoringSameBotAgainDoesNotOverwriteNewerRuntimeValuesOrOtherAccounts() {
        store.setMjTime(BOT_ID, 123L);
        store.setMjTime(BOT_ID + 1L, 999L);
        TestService first = service();
        first.restoreGameTaskState(bot);
        config.setMjTime(456L);
        first.restoreGameTaskState(bot);
        assertEquals(456L, config.getMjTime());
        assertEquals(999L, store.load(BOT_ID + 1L).mjTime);
    }
}
