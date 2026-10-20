package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.service.utils.Utils;

import java.util.Collections;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestServiceBountySettlementTest {
    private static final long BOT_ID = 90021L;
    private static final long GROUP_ID = 516625967L;
    private static final String SETTLEMENT = "🎖️ 悬赏令结算 · 任务达成\n"
            + "✅ 任务结果：你花费百年时间炼制一把可穿透星界屏障的神剑，趁使者降临之际，一剑穿心！\n"
            + "🎯 任务奖励：\n增加修为1208166836989\n额外奖励：地阶下品:浑天术\n悬赏令刷新";
    private static final String NO_BOUNTY = "🌟 悬赏令查询提示🌟\n"
            + "🔍 当前未查询到道友的悬赏令信息\n【操作指引】\n🔄 请先刷新悬赏令！";

    private final TestService service = new TestService();
    private final BotConfig config = new BotConfig();
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private Bot previousBot;

    @BeforeEach
    void setUp() {
        config.setGroupId(GROUP_ID);
        config.setRewardMode(3);
        config.setCultivationMode(0);
        config.setEnableAutoSecret(false);
        config.setStartScheduled(false);
        config.setXslTime(System.currentTimeMillis() - 60000L);
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotName()).thenReturn("黄瞻");
        when(bot.getBotConfig()).thenReturn(config);
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        when(group.getGroupId()).thenReturn(GROUP_ID);
        when(group.getBot()).thenReturn(bot);
        ReflectionTestUtils.setField(service, "xxGroupId", 0L);
        previousBot = BotFactory.getBots().put(BOT_ID, bot);
    }

    @AfterEach
    void restoreBot() {
        if (previousBot == null) {
            BotFactory.getBots().remove(BOT_ID);
        } else {
            BotFactory.getBots().put(BOT_ID, previousBot);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SETTLEMENT, "悬赏令结算成功！增加修为1208166836989"})
    void successfulSettlementClearsTimerAndRefreshesEvenWhenStoryContainsTime(String message) throws Exception {
        settle(message);

        assertEquals(-1L, config.getXslTime());
        assertSentCommand("悬赏令刷新");
        assertSchedulerDoesNotRetrySettlement();
    }

    @Test
    void successfulSettlementContinuesUsingPursuitTokens() throws Exception {
        config.setCommand("一键使用追捕令");

        settle(SETTLEMENT);

        assertEquals(-1L, config.getXslTime());
        assertSentCommand("道具使用追捕令");
        assertSchedulerDoesNotRetrySettlement();
    }

    @Test
    void successfulSettlementResumesCultivationInSemiAutomaticMode() throws Exception {
        config.setRewardMode(2);
        config.setCultivationMode(1);
        config.setEnableAutoBuyLowPrice(false);

        settle(SETTLEMENT);

        assertEquals(-1L, config.getXslTime());
        assertTrue(config.isStartScheduled());
        assertSentCommand("修炼");
        assertSchedulerDoesNotRetrySettlement();
    }

    @ParameterizedTest
    @ValueSource(strings = {NO_BOUNTY, "没有查到你的悬赏令信息"})
    void missingBountyClearsStaleTimerAndRefreshes(String message) throws Exception {
        bounty(message);

        assertEquals(-1L, config.getXslTime());
        assertTrue(config.isStartScheduled());
        assertSentCommand("悬赏令刷新");
        assertSchedulerDoesNotRetrySettlement();
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5})
    void missingBountyRefreshesInEveryAutomaticMode(int rewardMode) throws Exception {
        config.setRewardMode(rewardMode);

        bounty(NO_BOUNTY);

        assertEquals(-1L, config.getXslTime());
        assertSentCommand("悬赏令刷新");
    }

    @Test
    void missingBountyDoesNotRefreshInSemiAutomaticMode() throws Exception {
        config.setRewardMode(2);

        bounty(NO_BOUNTY);

        assertEquals(-1L, config.getXslTime());
        assertTrue(config.isStartScheduled());
        assertSchedulerDoesNotRetrySettlement();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "悬赏令进行中\n预计剩余时间：3分钟\n完成后发送悬赏令结算，增加修为1208166836989",
            "进行中的悬赏令\n预计3分钟后可结束\n完成后发送悬赏令结算，增加修为1208166836989"
    })
    void ongoingBountyWithRewardPreviewIsNotTreatedAsSettled(String message) throws Exception {
        config.setXslTime(System.currentTimeMillis() + 60000L);
        long deadline = config.getXslTime();

        settle(message);

        assertEquals(deadline, config.getXslTime());
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void manualModeDoesNotProcessAutomaticSettlementOrRefresh() throws Exception {
        config.setRewardMode(1);
        long deadline = config.getXslTime();

        settle(SETTLEMENT);
        bounty(NO_BOUNTY);

        assertEquals(deadline, config.getXslTime());
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void repliesToOtherPlayersDoNotChangeOurBounty() throws Exception {
        long deadline = config.getXslTime();
        service.结算(bot, group, null, new MessageChain(), "@90022\n" + SETTLEMENT, 1);
        service.悬赏令(bot, group, null, new MessageChain(), "@90022\n" + NO_BOUNTY, 2);

        assertEquals(deadline, config.getXslTime());
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    private void settle(String message) throws Exception {
        service.结算(bot, group, null, new MessageChain(), "@" + BOT_ID + "\n" + message, 1);
    }

    private void bounty(String message) throws Exception {
        service.悬赏令(bot, group, null, new MessageChain(), "@" + BOT_ID + "\n" + message, 1);
    }

    private void assertSentCommand(String command) {
        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(sent.capture());
        assertEquals(command, Utils.getMessageText(sent.getValue()).trim());
        assertEquals(Collections.singletonList("3889001741"),
                sent.getValue().getMessageByType(AtMessage.class).stream()
                        .map(AtMessage::getQq)
                        .collect(Collectors.toList()));
    }

    private void assertSchedulerDoesNotRetrySettlement() {
        clearInvocations(group);
        service.结算();
        service.结算();
        verify(group, never()).sendMessage(any(MessageChain.class));
    }
}
