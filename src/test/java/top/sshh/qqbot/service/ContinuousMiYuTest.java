package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.MarkdownMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.MiYuExploreContext;
import top.sshh.qqbot.service.utils.SectCultivationManager;
import top.sshh.qqbot.service.utils.Utils;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContinuousMiYuTest {
    private static final AtomicLong IDS = new AtomicLong(772200L);
    private static final String EXHAUSTED = "道友今日的秘域探险次数已尽，明日再来。";
    @TempDir Path directory;
    private final long botId = IDS.incrementAndGet();
    private final long groupId = botId + 100000L;
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final BotConfig config = new BotConfig();
    private GameTaskStateStore store;
    private TestService service;

    @BeforeEach
    void setUp() {
        config.setGroupId(groupId);
        config.setEnableAutoSecret(true);
        config.setCultivationMode(0);
        when(bot.getBotId()).thenReturn(botId);
        when(bot.getBotName()).thenReturn("一心");
        when(bot.getBotConfig()).thenReturn(config);
        when(bot.getGroup(groupId)).thenReturn(group);
        when(group.getGroupId()).thenReturn(groupId);
        when(group.getBot()).thenReturn(bot);
        store = new GameTaskStateStore(directory);
        service = newService();
    }

    @AfterEach
    void cleanUp() {
        SectCultivationManager.getInstance().cancel(bot);
    }

    private TestService newService() {
        TestService created = new TestService();
        ReflectionTestUtils.setField(created, "xxGroupId", 0L);
        ReflectionTestUtils.setField(created, "groupManager", mock(GroupManager.class));
        ReflectionTestUtils.setField(created, "gameTaskStateStore", new GameTaskStateStore(directory));
        return created;
    }

    private void start(String command) throws Exception {
        service.enableScheduled(bot, group, null, new MessageChain(), command, 1);
        config.setCultivationMode(2);
    }

    private void enter(int messageId) {
        service.秘域(bot, group, null, reply("道友已入【幽都】，约 90 分钟后可发『秘域结算』"), "", messageId);
    }

    private MessageChain reply(String text) {
        return new MessageChain().at(String.valueOf(botId)).text(text);
    }

    private void result(String text, int messageId, int firstHandler) throws Exception {
        MessageChain chain = reply(text);
        for (int offset = 0; offset < 3; offset++) {
            switch ((firstHandler + offset) % 3) {
                case 0:
                    service.秘域地图选择(bot, group, null, chain, "", messageId, null);
                    break;
                case 1:
                    service.秘域(bot, group, null, chain, "", messageId);
                    break;
                case 2:
                    service.结算(bot, group, null, chain, "", messageId);
                    break;
                default:
                    throw new AssertionError("未知消息处理器");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"秘域探险结算成功,0", "秘域结算完成,1", "此行探毕,2"})
    void continuesOncePerRoundAndOnlyCultivatesAfterDailyLimit(String settlement, int firstHandler) throws Exception {
        start("开始连续自动秘域");
        assertCommands("秘域快捷开始");
        assertTrue(store.load(botId).exploration.isContinuous());
        enter(10);
        assertEquals(MiYuExploreContext.State.EXPLORING, store.load(botId).exploration.getState());

        result(settlement, 11, firstHandler);
        assertCommands("秘域快捷开始", "秘域快捷开始");
        assertNull(store.load(botId).myTime);
        assertEquals(MiYuExploreContext.State.WAIT_DEPARTURE, store.load(botId).exploration.getState());

        enter(12);
        Long nextDeadline = store.load(botId).myTime;
        // 上轮回执迟到，不能清掉新一轮计时或再次发送探索指令。
        result(settlement, 11, firstHandler);
        assertEquals(nextDeadline, store.load(botId).myTime);
        result(settlement, 13, firstHandler);
        assertCommands("秘域快捷开始", "秘域快捷开始", "秘域快捷开始");
        result(EXHAUSTED, 14, firstHandler);
        assertCommands("秘域快捷开始", "秘域快捷开始", "秘域快捷开始", "闭关");
        assertNull(store.load(botId).myTime);
        assertNull(store.load(botId).exploration);
    }

    @Test
    void exhaustedReplyBeforeFirstEntryClosesOnceAndSupportsNameMentions() throws Exception {
        start("开始连续自动秘域");
        MessageChain chain = new MessageChain().text("@一心(发言次数:10)\n" + EXHAUSTED);
        service.秘域地图选择(bot, group, null, chain, "", 2, null);
        service.秘域(bot, group, null, chain, "", 2);
        service.结算(bot, group, null, chain, "", 2);

        assertCommands("秘域快捷开始", "闭关");
        assertNull(store.load(botId).exploration);
    }

    @Test
    void customOptionsAreReusedAfterEachSettlement() throws Exception {
        start("开始连续自动秘域\n指定路线：否\n禁区选择：是\n携带丹药：随机");
        assertCommands("探索秘域");
        enter(10);
        result("秘域结算成功", 11, 0);

        assertCommands("探索秘域", "探索秘域");
        MiYuExploreContext saved = store.load(botId).exploration;
        assertTrue(saved.isContinuous());
        assertFalse(saved.isQuickStart());
        assertTrue(saved.isForbiddenZone());
        assertTrue(saved.isRandomPills());
        assertEquals(MiYuExploreContext.State.WAIT_MAP, saved.getState());
    }

    @Test
    void ordinaryAutomaticExplorationStillEndsAfterOneRound() throws Exception {
        start("开始自动秘域");
        assertCommands("秘域快捷开始");
        assertFalse(store.load(botId).exploration.isContinuous());
        enter(10);
        assertNull(store.load(botId).exploration);
        result("此行探毕", 11, 2);

        assertCommands("秘域快捷开始", "闭关");
    }

    @Test
    void restartingDuringExplorationPreservesContinuousModeAndWaitsForSettlement() throws Exception {
        start("开始连续自动秘域");
        assertCommands("秘域快捷开始");
        enter(10);
        Long deadline = store.load(botId).myTime;
        service = newService();
        service.restoreGameTaskState(bot);
        service.resumeMiYuExploration(bot);
        assertCommands("秘域快捷开始");
        assertEquals(deadline, store.load(botId).myTime);
        assertTrue(store.load(botId).exploration.isContinuous());
        assertEquals(MiYuExploreContext.State.EXPLORING, store.load(botId).exploration.getState());

        result("秘域结算成功", 11, 1);
        assertCommands("秘域快捷开始", "秘域快捷开始");
    }

    @Test
    void longExplorationIsNotRemovedByPreparationTimeoutCleanup() throws Exception {
        start("开始连续自动秘域");
        assertCommands("秘域快捷开始");
        enter(10);
        @SuppressWarnings("unchecked")
        Map<Long, MiYuExploreContext> contexts =
                (Map<Long, MiYuExploreContext>) ReflectionTestUtils.getField(service, "miyuExploreMap");
        contexts.get(botId).setLastActiveTime(System.currentTimeMillis() - 60L * 60L * 1000L);
        Bot previous = BotFactory.getBots().put(botId, bot);
        try {
            service.结算();
            assertNotNull(contexts.get(botId));
            assertEquals(MiYuExploreContext.State.EXPLORING, contexts.get(botId).getState());
            assertCommands("秘域快捷开始");
        } finally {
            BotFactory.getBots().remove(botId);
            if (previous != null) BotFactory.getBots().put(botId, previous);
        }
    }

    @Test
    void restartBetweenSettlementAndNextRoundResumesContinuousExploration() {
        MiYuExploreContext context = new MiYuExploreContext();
        context.setContinuous(true);
        context.setQuickStart(true);
        context.setState(MiYuExploreContext.State.EXPLORING);
        store.setExploration(botId, context);

        service.restoreGameTaskState(bot);
        assertEquals(MiYuExploreContext.State.WAIT_START, store.load(botId).exploration.getState());
        service.resumeMiYuExploration(bot);
        assertCommands("秘域快捷开始");
    }

    @Test
    void manualStopRemovesContinuousModeAndLateSettlementDoesNotRestartIt() throws Exception {
        start("开始连续自动秘域");
        assertCommands("秘域快捷开始");
        enter(10);
        service.enableScheduled(bot, group, null, new MessageChain(), "停止执行", 11);
        assertNull(store.load(botId).exploration);
        assertNull(store.load(botId).myTime);
        service = newService();
        service.restoreGameTaskState(bot);
        service.resumeMiYuExploration(bot);
        result("秘域结算成功", 12, 0);

        assertCommands("秘域快捷开始");
        assertNull(store.load(botId).exploration);
    }

    @Test
    void otherPlayersAndOtherGroupsCannotEndThisBotsContinuousExploration() throws Exception {
        start("开始连续自动秘域");
        assertCommands("秘域快捷开始");
        enter(10);
        ReflectionTestUtils.setField(service, "xxGroupId", 1L);
        MessageChain otherPlayer = new MessageChain().at(String.valueOf(botId + 1)).text(EXHAUSTED);
        service.秘域(bot, group, null, otherPlayer, "", 11);
        service.结算(bot, group, null, otherPlayer, "", 11);
        service.秘域地图选择(bot, group, null, otherPlayer, "", 11, null);
        Group otherGroup = mock(Group.class);
        when(otherGroup.getGroupId()).thenReturn(groupId + 1);
        service.秘域地图选择(bot, otherGroup, null, reply(EXHAUSTED), "@" + botId + " " + EXHAUSTED, 12, null);
        service.秘域(bot, otherGroup, null, reply(EXHAUSTED), "", 12);
        service.结算(bot, otherGroup, null, reply(EXHAUSTED), "", 12);

        assertCommands("秘域快捷开始");
        assertNotNull(store.load(botId).myTime);
        assertEquals(MiYuExploreContext.State.EXPLORING, store.load(botId).exploration.getState());
    }

    @Test
    void markdownMentionAndBodyCanConfirmTheDailyLimit() throws Exception {
        start("开始连续自动秘域");
        MessageChain chain = new MessageChain();
        chain.add(new MarkdownMessage("[@一心](mqqapi://test?at_tinyid=" + botId + ")\n" + EXHAUSTED));
        service.秘域(bot, group, null, chain, "", 2);

        assertCommands("秘域快捷开始", "闭关");
        assertNull(store.load(botId).exploration);
    }

    private static boolean isGameCommand(MessageChain chain) {
        return chain.getMessageByType(AtMessage.class).stream().anyMatch(at -> "3889001741".equals(at.getQq()));
    }

    private void assertCommands(String... expected) {
        verify(group, timeout(6000).times(expected.length))
                .sendMessage(argThat((MessageChain chain) -> isGameCommand(chain)));
        List<String> actual = mockingDetails(group).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("sendMessage"))
                .map(call -> (MessageChain) call.getArgument(0))
                .filter(ContinuousMiYuTest::isGameCommand)
                .map(Utils::getMessageText).collect(Collectors.toList());
        assertEquals(Arrays.asList(expected), actual);
    }
}
