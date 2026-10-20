package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Buttons;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.constant.Constant;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.data.RecognitionResult;
import top.sshh.qqbot.service.TestService;
import top.sshh.qqbot.verifycode.RemoteVerifyCode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoBuyHerbsCaptchaRecoveryTest {
    private static final long BOT_ID = 1001L;
    private static final long GROUP_ID = 2001L;
    private static final String CAPTCHA = "at_tinyid=1001) 请点击图中第2个表情对应的按钮";
    @TempDir Path tempDir;

    private final AutoBuyHerbs service = new AutoBuyHerbs();
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final BotConfig config = new BotConfig();
    private final Config alchemyConfig = new Config();
    private String previousTargetDir;
    private ExecutorService originalPool;

    @BeforeEach
    void setUp() {
        previousTargetDir = Constant.targetDir;
        Constant.targetDir = tempDir.toString();
        originalPool = field("customPool");
        config.setEnableAlchemy(true);
        config.setAutoBuyHerbsMode(1);
        config.setAutoVerifyModel(2);
        config.setGroupId(GROUP_ID);
        config.setTaskStatusHerbs(1);
        alchemyConfig.setRandomDelay(0);
        service.danCalculator = mock(DanCalculator.class);
        when(service.danCalculator.getConfig(BOT_ID)).thenReturn(alchemyConfig);
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotConfig()).thenReturn(config);
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        when(group.getGroupId()).thenReturn(GROUP_ID);
    }

    @AfterEach
    void tearDown() {
        originalPool.shutdownNow();
        ((ExecutorService) field("customPool")).shutdownNow();
        Constant.targetDir = previousTargetDir;
    }

    @Test
    void waitsFiveMinutesBeforeRecoveringAndClearsOldPurchases() {
        ProductPrice old = new ProductPrice();
        old.setCode("old-code");
        Map<Long, CopyOnWriteArrayList<ProductPrice>> queues = field("autoBuyListMap");
        queues.put(BOT_ID, new CopyOnWriteArrayList<>(List.of(old)));
        Map<Long, ProductPrice> inFlight = field("inFlightPurchaseMap");
        inFlight.put(BOT_ID, old);
        long started = System.currentTimeMillis();
        captcha();
        long deadline = deadlines().get(BOT_ID);
        assertTrue(deadline >= started + 300000L);

        checkAt(deadline - 1L);
        assertTrue(config.isStop());
        verify(group, never()).sendMessage(any(MessageChain.class));

        checkAt(deadline);
        checkAt(deadline + 1L);

        assertFalse(config.isStop());
        assertEquals("失败", config.getVerificationStatus());
        assertTrue(queues.get(BOT_ID).isEmpty());
        assertFalse(inFlight.containsKey(BOT_ID));
        assertFalse(deadlines().containsKey(BOT_ID));
        assertEquals(List.of("查看坊市药材1"), sentTexts());
    }

    @Test
    void actualKeyboardApiFailureStillRecoversAfterDeadline() {
        captcha();
        long deadline = deadlines().get(BOT_ID);
        RemoteVerifyCode verifier = spy(new RemoteVerifyCode());
        TestService reminders = mock(TestService.class);
        ReflectionTestUtils.setField(verifier, "testService", reminders);
        Buttons buttons = new Buttons();
        buttons.setButtonList(List.of(mock(com.zhuangxv.bot.core.Button.class)));
        buttons.setImageUrl("https://example.com/captcha.png");
        buttons.setImageText(CAPTCHA);
        buttons.setBotAppid("102074059");
        buttons.setMsgSeq("2014036");
        RecognitionResult result = new RecognitionResult();
        result.setAnswer("序号1");
        doReturn(result).when(verifier).recognizeVerifyCode(buttons.getImageUrl(), CAPTCHA, bot);
        doNothing().when(verifier).saveErrorImage(any(), any(), any(), eq(BOT_ID));
        doThrow(new IllegalStateException("OIDB error 10004: 请求第三方失败"))
                .when(bot).clickKeyboardButton(anyLong(), any(), any(), any(), any());

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(verifier, "autoVerifyCode", bot, group,
                new MessageChain().text(CAPTCHA), CAPTCHA, 1, buttons));
        assertTrue(config.isStop());
        verify(bot, times(2)).clickKeyboardButton(anyLong(), any(), any(), any(), any());
        verify(reminders).showButtonMsg(eq(bot), eq(group), eq(1), eq(CAPTCHA), eq(buttons), any());

        checkAt(deadline);
        assertFalse(config.isStop());
        assertEquals(List.of("查看坊市药材1"), sentTexts());
    }

    @Test
    void unrelatedPauseIsNeverAutomaticallyCleared() {
        config.setStop(true);
        config.setAutoTaskRefreshTime(0L);

        checkAt(System.currentTimeMillis() + 600000L);

        assertTrue(config.isStop());
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void manualAndSemiAutomaticVerificationKeepWaitingPastDeadline(int verifyMode) {
        config.setAutoVerifyModel(verifyMode);
        captcha();

        checkAt(deadlines().get(BOT_ID) + 1L);

        assertTrue(config.isStop());
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void switchingFromFullyAutomaticToSemiAutomaticCancelsAutomaticRecovery() {
        captcha();
        long deadline = deadlines().get(BOT_ID);
        config.setAutoVerifyModel(1);

        checkAt(deadline);

        assertTrue(config.isStop());
        assertTrue(deadlines().containsKey(BOT_ID));
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void stoppingPurchasesCancelsRecovery() {
        captcha();
        long deadline = deadlines().get(BOT_ID);
        service.enableScheduled(bot, group, null, new MessageChain(), "停止采购药材", 1);
        clearInvocations(group);

        checkAt(deadline);

        assertEquals(0, config.getAutoBuyHerbsMode());
        assertTrue(deadlines().isEmpty());
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void captchaForOtherAccountOrGroupDoesNotCreateRecovery() {
        Group otherGroup = mock(Group.class);
        when(otherGroup.getGroupId()).thenReturn(3001L);
        onMessage(otherGroup, CAPTCHA);
        onMessage(group, CAPTCHA.replace("1001", "1002"));

        assertTrue(deadlines().isEmpty());
        assertFalse(config.isStop());
    }

    @Test
    void repeatedCaptchaDoesNotExtendRecoveryDeadline() {
        captcha();
        long deadline = deadlines().get(BOT_ID) - 1000L;
        deadlines().put(BOT_ID, deadline);

        captcha();
        checkAt(deadline - 1L);

        assertTrue(config.isStop());
        assertEquals(deadline, deadlines().get(BOT_ID));
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void verificationResultCancelsOldTimeoutAndCannotUnlockLaterPause() {
        captcha();
        long deadline = deadlines().get(BOT_ID);
        onMessage(group, "@1001 验证奖励100灵石");
        config.setVerificationStatus("已完成");
        config.setStop(true);

        checkAt(deadline);

        assertTrue(deadlines().isEmpty());
        assertTrue(config.isStop());
        assertEquals("已完成", config.getVerificationStatus());
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void captchaWaitBlocksQueuedPurchasesEvenIfAnotherTaskClearsStop() {
        captcha();
        config.setStop(false);
        Map<Long, CopyOnWriteArrayList<ProductPrice>> queues = field("autoBuyListMap");
        ProductPrice old = new ProductPrice();
        old.setCode("old-code");
        queues.put(BOT_ID, new CopyOnWriteArrayList<>(List.of(old)));

        ReflectionTestUtils.invokeMethod(service, "buyHerbs", bot);
        ReflectionTestUtils.invokeMethod(service, "refreshHerbsIndex", bot);
        ReflectionTestUtils.invokeMethod(service, "processMarketMessage", bot, group, "坊市数据");

        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void cancelledMarketDelayCannotSendAfterTimeoutRecovery() {
        ExecutorService delayedPool = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "customPool", delayedPool);
        alchemyConfig.setRandomDelay(1);
        ReflectionTestUtils.invokeMethod(service, "refreshHerbsIndexByInterval", bot, alchemyConfig);
        ArgumentCaptor<Runnable> oldTask = ArgumentCaptor.forClass(Runnable.class);
        verify(delayedPool).submit(oldTask.capture());
        captcha();
        alchemyConfig.setRandomDelay(0);
        checkAt(deadlines().get(BOT_ID));

        oldTask.getValue().run();

        assertEquals(List.of("查看坊市药材1"), sentTexts());
    }

    @Test
    void configuredTimeoutIsApplied() {
        ReflectionTestUtils.setField(service, "captchaRecoveryTimeoutMs", 60000L);
        long started = System.currentTimeMillis();

        captcha();

        long deadline = deadlines().get(BOT_ID);
        assertTrue(deadline >= started + 60000L && deadline <= System.currentTimeMillis() + 60000L);
        checkAt(deadline);
        assertFalse(config.isStop());
    }

    @Test
    void recoveringOneBotDoesNotClearAnotherBotsCaptchaWait() {
        captcha();
        Bot otherBot = mock(Bot.class);
        Group otherGroup = mock(Group.class);
        BotConfig otherConfig = new BotConfig();
        otherConfig.setGroupId(3001L);
        otherConfig.setAutoBuyHerbsMode(1);
        otherConfig.setAutoVerifyModel(2);
        when(otherBot.getBotId()).thenReturn(1002L);
        when(otherBot.getBotConfig()).thenReturn(otherConfig);
        when(otherGroup.getGroupId()).thenReturn(3001L);
        String otherCaptcha = CAPTCHA.replace("1001", "1002");
        service.验证码判断(otherBot, otherGroup, null, new MessageChain(), otherCaptcha, 1);

        checkAt(deadlines().get(BOT_ID));

        assertFalse(config.isStop());
        assertTrue(otherConfig.isStop());
        assertTrue(deadlines().containsKey(1002L));
        verify(otherGroup, never()).sendMessage(any(MessageChain.class));
    }

    private void captcha() {
        onMessage(group, CAPTCHA);
    }

    private void onMessage(Group source, String message) {
        service.验证码判断(bot, source, null, new MessageChain().text(message), message, 1);
    }

    private void checkAt(long now) {
        ReflectionTestUtils.invokeMethod(service, "checkPurchaseTimeout", bot, now);
    }

    private Map<Long, Long> deadlines() {
        return field("captchaWaitUntilMap");
    }

    private List<String> sentTexts() {
        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, atLeastOnce()).sendMessage(sent.capture());
        return sent.getAllValues().stream().map(chain -> chain.getMessageByType(TextMessage.class).stream()
                .map(TextMessage::getText).collect(Collectors.joining())).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) {
        return (T) ReflectionTestUtils.getField(service, name);
    }
}
