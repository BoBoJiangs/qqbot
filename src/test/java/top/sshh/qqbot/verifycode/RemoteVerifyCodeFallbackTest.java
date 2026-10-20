package top.sshh.qqbot.verifycode;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Button;
import com.zhuangxv.bot.core.Buttons;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.RecognitionResult;
import top.sshh.qqbot.data.VerifyCount;
import top.sshh.qqbot.service.GroupManager;
import top.sshh.qqbot.service.TestService;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RemoteVerifyCodeFallbackTest {
    private static final long BOT_ID = 1001L;
    private static final long GROUP_ID = 2001L;
    private final RemoteVerifyCode service = spy(new RemoteVerifyCode());
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final BotConfig config = new BotConfig();
    private final Buttons buttons = new Buttons();
    private final FixedRandom random = new FixedRandom();
    private final TestService reminders = mock(TestService.class);
    private final GroupManager groupManager = mock(GroupManager.class);

    @BeforeEach
    void setUp() {
        config.setAutoVerifyModel(2);
        config.setGroupId(GROUP_ID);
        config.setMasterQQ(3001L);
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotConfig()).thenReturn(config);
        when(group.getGroupId()).thenReturn(GROUP_ID);
        buttons.setBotAppid("102074059");
        buttons.setMsgSeq("2014036");
        buttons.setImageUrl("https://example.com/captcha.png");
        buttons.setImageText("请点击图中第2个表情对应的按钮");
        buttons.setButtonList(List.of(button("1", "🔋"), button("2", "🐰"), button("3", "📖")));
        ReflectionTestUtils.setField(service, "fallbackButtonRandom", random);
        ReflectionTestUtils.setField(service, "testService", reminders);
        groupManager.verifyCount = new VerifyCount();
        ReflectionTestUtils.setField(service, "groupManager", groupManager);
        ReflectionTestUtils.setField(service, "xxGroupId", 0L);
        doNothing().when(service).saveErrorImage(any(), any(), any(), eq(BOT_ID));
    }

    @Test
    void modeTwoRandomlyClicksEvenWhenNoEmojiWasRecognized() {
        RecognitionResult result = new RecognitionResult();
        result.setResult("识别失败");
        result.setEmojiList(Collections.emptyList());

        process(result);

        assertRandomThirdButtonWasClicked();
    }

    @Test
    void missingEmojiListDoesNotInterruptRandomFallback() {
        RecognitionResult result = new RecognitionResult();
        result.setResult("识别失败");

        process(result);

        assertRandomThirdButtonWasClicked();
        verifyNoInteractions(reminders);
    }

    @Test
    void unrecognizedAnswerAlsoUsesRandomFallback() {
        RecognitionResult result = new RecognitionResult();
        result.setAnswer("不存在的按钮");

        process(result);

        assertRandomThirdButtonWasClicked();
    }

    @Test
    void arithmeticFallbackWithoutNumericButtonsStillClicksRandomly() {
        RecognitionResult result = new RecognitionResult();
        result.setResult("请点击加法题正确答案");

        process(result);

        assertRandomThirdButtonWasClicked();
    }

    @Test
    void semiAutomaticModeDoesNotRandomlyClickAfterRecognitionFailure() {
        config.setAutoVerifyModel(1);
        RecognitionResult result = new RecognitionResult();
        result.setResult("识别失败");

        process(result);

        verify(bot, never()).clickKeyboardButton(anyLong(), any(), any(), any(), any());
        assertEquals(0, random.calls);
    }

    @Test
    void keyboardApiFailureRetriesSameButtonOnlyOnceThenLeavesTimeoutRecoveryInPlace() {
        RecognitionResult result = new RecognitionResult();
        result.setAnswer("🐰");
        doThrow(new IllegalStateException("OIDB error 10004: 请求第三方失败"))
                .when(bot).clickKeyboardButton(GROUP_ID, buttons.getBotAppid(), "2", "data-2", buttons.getMsgSeq());

        process(result);

        verify(bot, times(2)).clickKeyboardButton(
                GROUP_ID, buttons.getBotAppid(), "2", "data-2", buttons.getMsgSeq());
        assertEquals(0, random.calls);
        assertEquals(0, groupManager.verifyCount.correctCount);
        verify(reminders).showButtonMsg(eq(bot), eq(group), eq(1), anyString(), eq(buttons), any());
    }

    @Test
    void firstClickSuccessDoesNotRetry() {
        RecognitionResult result = new RecognitionResult();
        result.setAnswer("🐰");

        process(result);

        verify(bot, times(1)).clickKeyboardButton(anyLong(), any(), any(), any(), any());
        assertEquals(1, groupManager.verifyCount.correctCount);
        verifyNoInteractions(reminders);
    }

    @Test
    void successfulRetryDoesNotRepeatRecognitionOrSendFailureReminder() {
        RecognitionResult result = new RecognitionResult();
        result.setAnswer("🐰");
        doThrow(new IllegalStateException("第一次点击失败")).doNothing().when(bot)
                .clickKeyboardButton(GROUP_ID, buttons.getBotAppid(), "2", "data-2", buttons.getMsgSeq());

        process(result);

        verify(bot, times(2)).clickKeyboardButton(
                GROUP_ID, buttons.getBotAppid(), "2", "data-2", buttons.getMsgSeq());
        verify(service, times(1)).recognizeVerifyCode(buttons.getImageUrl(), buttons.getImageText(), bot);
        assertEquals(1, groupManager.verifyCount.correctCount);
        assertEquals(0, random.calls);
        verifyNoInteractions(reminders);
    }

    @Test
    void randomClickRetryKeepsTheOriginallyChosenButton() {
        RecognitionResult result = new RecognitionResult();
        result.setResult("识别失败");
        doThrow(new IllegalStateException("第一次点击失败")).doNothing().when(bot)
                .clickKeyboardButton(GROUP_ID, buttons.getBotAppid(), "3", "data-3", buttons.getMsgSeq());

        process(result);

        assertEquals(1, random.calls);
        verify(bot, times(2)).clickKeyboardButton(
                GROUP_ID, buttons.getBotAppid(), "3", "data-3", buttons.getMsgSeq());
        verifyNoInteractions(reminders);
    }

    @Test
    void indexAnswerUsesTheSameBoundedRetry() {
        RecognitionResult result = new RecognitionResult();
        result.setAnswer("序号2");
        doThrow(new IllegalStateException("第一次点击失败")).doNothing().when(bot)
                .clickKeyboardButton(GROUP_ID, buttons.getBotAppid(), "2", "data-2", buttons.getMsgSeq());

        process(result);

        verify(bot, times(2)).clickKeyboardButton(
                GROUP_ID, buttons.getBotAppid(), "2", "data-2", buttons.getMsgSeq());
        assertEquals(1, groupManager.verifyCount.correctCount);
        verifyNoInteractions(reminders);
    }

    @Test
    void semiAutomaticModeDoesNotRetryFailedKeyboardApi() {
        config.setAutoVerifyModel(1);
        RecognitionResult result = new RecognitionResult();
        result.setAnswer("🐰");
        doThrow(new IllegalStateException("点击失败")).when(bot)
                .clickKeyboardButton(anyLong(), any(), any(), any(), any());

        process(result);

        verify(bot, times(1)).clickKeyboardButton(anyLong(), any(), any(), any(), any());
        verify(reminders).showButtonMsg(eq(bot), eq(group), eq(1), anyString(), eq(buttons), any());
    }

    @Test
    void interruptedRetryWaitStopsWithoutClickingAgainOrSendingReminder() {
        RecognitionResult result = new RecognitionResult();
        result.setAnswer("🐰");
        doThrow(new IllegalStateException("点击失败")).when(bot)
                .clickKeyboardButton(anyLong(), any(), any(), any(), any());

        Thread.currentThread().interrupt();
        try {
            process(result);

            assertTrue(Thread.currentThread().isInterrupted());
            verify(bot, times(1)).clickKeyboardButton(anyLong(), any(), any(), any(), any());
            verifyNoInteractions(reminders);
        } finally {
            Thread.interrupted();
        }
    }

    private void process(RecognitionResult result) {
        doReturn(result).when(service).recognizeVerifyCode(buttons.getImageUrl(), buttons.getImageText(), bot);
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "autoVerifyCode", bot, group,
                new MessageChain().text(buttons.getImageText()), buttons.getImageText(), 1, buttons));
    }

    private void assertRandomThirdButtonWasClicked() {
        assertEquals(1, random.calls);
        verify(bot).clickKeyboardButton(GROUP_ID, buttons.getBotAppid(), "3", "data-3", buttons.getMsgSeq());
    }

    private Button button(String id, String label) {
        Button button = mock(Button.class);
        when(button.getId()).thenReturn(id);
        when(button.getData()).thenReturn("data-" + id);
        when(button.getLabel()).thenReturn(label);
        return button;
    }

    private static class FixedRandom extends Random {
        private int calls;

        @Override
        public int nextInt(int bound) {
            assertEquals(3, bound);
            calls++;
            return 2;
        }
    }
}
