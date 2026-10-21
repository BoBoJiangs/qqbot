package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Button;
import com.zhuangxv.bot.core.Buttons;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.MarkdownMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.service.utils.Utils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TestServiceMarketCaptchaTest {
    private static final String CAPTCHA = "at_tinyid=1001) 请点击图中第2个表情对应的按钮";
    private final TestService service = new TestService();
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final BotConfig config = new BotConfig();

    @BeforeEach
    void setUp() {
        config.setGroupId(2001L);
        config.setAutoVerifyModel(2);
        config.setTaskStatusEquip(2);
        config.setTaskStatusSkills(3);
        config.setTaskStatusHerbs(4);
        when(bot.getBotId()).thenReturn(1001L);
        when(bot.getBotConfig()).thenReturn(config);
        when(bot.getGroup(2001L)).thenReturn(group);
        when(group.getGroupId()).thenReturn(2001L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"开始更新坊市", "更新坊市装备", "更新坊市技能", "更新坊市药材"})
    void verificationPreservesEachModeAndRetriesBlockedPageBeforeContinuing(String command) throws Exception {
        command(command);
        tick();
        String blockedPage = queries().get(0);

        captcha();
        assertTrue(config.isStop());
        tick();
        tick();
        assertEquals(List.of(blockedPage), queries());

        result("@1001 验证奖励100灵石");
        assertFalse(config.isStop());
        tick();
        assertEquals(List.of(blockedPage, blockedPage), queries());
        tick();
        assertEquals(3, queries().size());
        assertNotEquals(blockedPage, queries().get(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"验证奖励100灵石", "不需要验证", "验证成功", "验证通过"})
    void markdownResultsWithSeparateAtSegmentResumePolling(String result) throws Exception {
        start();
        tick();
        captcha();
        MessageChain chain = new MessageChain().at("1001");
        chain.add(new MarkdownMessage(result));

        service.验证码判断(bot, group, null, chain, "", 3, null);
        tick();

        assertEquals(List.of("查看坊市装备2", "查看坊市装备2"), queries());
    }

    @ParameterizedTest
    @ValueSource(strings = {"验证码不正确", "验证码已过期", "验证失败", "未验证通过"})
    void failedVerificationKeepsWaitingUntilAValidResult(String failure) throws Exception {
        start();
        tick();
        captcha();
        result("@1001 " + failure);
        config.setStop(false); // 其他模块清掉通用 stop，也不能误恢复坊市轮询。
        tick();
        assertEquals(List.of("查看坊市装备2"), queries());

        result("@1001 验证成功");
        tick();
        assertEquals(List.of("查看坊市装备2", "查看坊市装备2"), queries());
    }

    @Test
    void missingButtonsStillPausesAndResumesFromMarkdownCaptcha() throws Exception {
        start();
        tick();
        MessageChain chain = new MessageChain().at("1001");
        chain.add(new MarkdownMessage("请点击图中第2个表情对应的按钮"));
        service.验证码判断(bot, group, null, chain, "", 2, null);
        tick();
        assertTrue(config.isStop());
        assertEquals(1, queries().size());

        result("@1001 验证成功");
        tick();
        assertEquals(List.of("查看坊市装备2", "查看坊市装备2"), queries());
    }

    @Test
    void rewardMentionInsideCaptchaPromptIsNotACompletedVerification() throws Exception {
        start();
        tick();
        result(CAPTCHA + "，验证成功后奖励100灵石");
        tick();
        assertTrue(config.isStop());
        assertEquals(1, queries().size());
    }

    @Test
    void repeatedCaptchaDoesNotMoveCheckpointBackAgain() throws Exception {
        start();
        tick();
        captcha();
        captcha();
        result("@1001 验证成功");
        tick();
        assertEquals(List.of("查看坊市装备2", "查看坊市装备2"), queries());
    }

    @ParameterizedTest
    @ValueSource(strings = {"停止更新坊市", "停止执行"})
    void manualStopCannotBeUndoneByLateVerificationResult(String stopCommand) throws Exception {
        start();
        tick();
        captcha();
        command(stopCommand);
        result("@1001 验证成功");
        tick();

        assertFalse(config.isStartScheduledMarket());
        assertFalse(config.isStartScheduledEquip());
        assertFalse(config.isStartScheduledSkills());
        assertFalse(config.isStartScheduledHerbs());
        assertEquals(1, queries().size());
    }

    @Test
    void verificationForAnotherAccountOrGroupCannotResumeThisCaptcha() throws Exception {
        start();
        tick();
        captcha();
        result("@1002 验证成功");
        tick();
        assertTrue(config.isStop());
        Group otherGroup = mock(Group.class);
        when(otherGroup.getGroupId()).thenReturn(2002L);
        service.验证码判断(bot, otherGroup, null, new MessageChain(), "@1001 验证成功", 4, null);
        tick();
        assertEquals(1, queries().size());

        result("@1001 验证成功");
        tick();
        assertEquals(2, queries().size());
    }

    @Test
    void captchaForAnotherBotDoesNotPauseThisBotsPolling() throws Exception {
        start();
        tick();
        result(CAPTCHA.replace("1001", "1002"));
        tick();
        assertFalse(config.isStop());
        assertEquals(List.of("查看坊市装备2", "查看坊市装备3"), queries());
    }

    @Test
    void anotherBotsPollingContinuesWhileThisBotWaitsForVerification() throws Exception {
        start();
        tick();
        captcha();
        Bot second = mock(Bot.class);
        Group secondGroup = mock(Group.class);
        BotConfig secondConfig = new BotConfig();
        secondConfig.setGroupId(2001L);
        secondConfig.setStartScheduledMarket(true);
        secondConfig.setTaskStatusEquip(1);
        when(second.getBotId()).thenReturn(1002L);
        when(second.getBotConfig()).thenReturn(secondConfig);
        when(second.getGroup(2001L)).thenReturn(secondGroup);

        ReflectionTestUtils.invokeMethod(service, "refreshMarket", second);

        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(secondGroup).sendMessage(sent.capture());
        assertEquals("查看坊市装备1", Utils.getMessageText(sent.getValue()));
        assertTrue(config.isStop());
        assertEquals(1, queries().size());
    }

    @Test
    void missingExecutionGroupDoesNotAdvancePage() throws Exception {
        start();
        when(bot.getGroup(2001L)).thenReturn(null);
        tick();
        assertEquals(2, config.getTaskStatusEquip());
        assertTrue(queries().isEmpty());

        when(bot.getGroup(2001L)).thenReturn(group);
        tick();
        assertEquals(List.of("查看坊市装备2"), queries());
    }

    @Test
    void captchaOnFirstPageAfterWrappingRetriesThatPage() throws Exception {
        config.setTaskStatusEquip(5);
        config.setTaskStatusSkills(10);
        config.setTaskStatusHerbs(8);
        start();
        tick();
        captcha();
        result("@1001 验证成功");
        tick();
        assertEquals(List.of("查看坊市装备1", "查看坊市装备1"), queries());
    }

    @Test
    void checkpointDoesNotOverwriteUnqueriedCategoryProgress() throws Exception {
        command("更新坊市装备");
        tick();
        config.setTaskStatusHerbs(6);
        captcha();
        assertEquals(6, config.getTaskStatusHerbs());
        assertEquals(2, config.getTaskStatusEquip());
    }

    @Test
    void unrelatedGlobalPauseBlocksPolling() throws Exception {
        start();
        config.setStop(true);
        tickAt(Long.MAX_VALUE);
        assertTrue(queries().isEmpty());
    }

    @Test
    void fullyAutomaticModeRetriesAfterFiveMinutesWithoutExtendingWaitOnDuplicates() throws Exception {
        start();
        tick();
        long beforeCaptcha = System.currentTimeMillis();
        captcha();
        tickAt(beforeCaptcha + 299999L);
        assertEquals(1, queries().size());
        // 改长配置后重复收到同一验证码，不应延长已经开始的五分钟等待。
        ReflectionTestUtils.setField(service, "marketCaptchaRecoveryTimeoutMs", 600000L);
        captcha();
        tickAt(System.currentTimeMillis() + 300000L);

        assertFalse(config.isStop());
        assertEquals("失败", config.getVerificationStatus());
        assertEquals(List.of("查看坊市装备2", "查看坊市装备2"), queries());
        tick();
        assertEquals("查看坊市装备3", queries().get(2));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void manualAndSemiAutomaticModesDoNotResumeOnTimeout(int mode) throws Exception {
        start();
        tick();
        config.setAutoVerifyModel(mode);
        // 无按钮的验证码也必须建立等待，避免人工提示依赖影响此测试。
        result(CAPTCHA);
        tickAt(Long.MAX_VALUE);
        assertTrue(config.isStop());
        assertEquals(1, queries().size());

        result("@1001 验证成功");
        tick();
        assertEquals(List.of("查看坊市装备2", "查看坊市装备2"), queries());
    }

    @Test
    void verificationSuccessCancelsTimeoutAndDoesNotUnlockLaterUnrelatedPause() throws Exception {
        start();
        tick();
        captcha();
        result("@1001 验证成功");
        config.setStop(true);
        tickAt(Long.MAX_VALUE);
        assertTrue(config.isStop());
        assertEquals(1, queries().size());
    }

    @Test
    void manualStopCancelsTimeoutRecovery() throws Exception {
        start();
        tick();
        captcha();
        command("停止更新坊市");
        tickAt(Long.MAX_VALUE);
        assertEquals(1, queries().size());
        assertTrue(config.isStop());
    }

    @Test
    void accountRestrictionStillStopsInsteadOfBeingAutomaticallyResumed() throws Exception {
        start();
        tick();
        result("@1001 账号受限，请解除限制");
        result("@1001 验证成功");
        tick();
        assertFalse(config.isStartScheduledMarket());
        assertEquals(1, queries().size());
    }

    private void start() throws Exception { command("开始更新坊市"); }

    private void command(String message) throws Exception {
        service.enableScheduled(bot, group, null, new MessageChain().text(message), message, 1);
    }

    private void tick() { ReflectionTestUtils.invokeMethod(service, "refreshMarket", bot); }

    private void tickAt(long now) { ReflectionTestUtils.invokeMethod(service, "refreshMarket", bot, now); }

    private void captcha() {
        Buttons buttons = new Buttons();
        buttons.setButtonList(Collections.nCopies(6, mock(Button.class)));
        service.验证码判断(bot, group, null, new MessageChain().text(CAPTCHA), CAPTCHA, 2, buttons);
    }

    private void result(String message) {
        service.验证码判断(bot, group, null, new MessageChain().text(message), message, 3, null);
    }

    private List<String> queries() {
        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, atLeastOnce()).sendMessage(sent.capture());
        return sent.getAllValues().stream()
                .filter(chain -> !chain.getMessageByType(AtMessage.class).isEmpty())
                .map(Utils::getMessageText).filter(text -> text.startsWith("查看坊市"))
                .collect(Collectors.toList());
    }
}
