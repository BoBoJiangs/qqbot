package top.sshh.qqbot.verifycode;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Button;
import com.zhuangxv.bot.core.Buttons;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.MarkdownMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.ObjectProvider;
import top.sshh.qqbot.data.RecognitionResult;
import top.sshh.qqbot.data.VerifyCount;
import top.sshh.qqbot.service.GroupManager;
import top.sshh.qqbot.service.TestService;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RemoteVerifyCodeStatisticsTest {
    private final RemoteVerifyCode service = spy(new RemoteVerifyCode());
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final GroupManager manager = mock(GroupManager.class);
    private final BotConfig config = new BotConfig();
    private final Buttons buttons = new Buttons();

    @BeforeEach
    void setUp() {
        config.setAutoVerifyModel(2);
        config.setGroupId(2001L);
        config.setMasterQQ(3001L);
        when(bot.getBotId()).thenReturn(1001L);
        when(bot.getBotConfig()).thenReturn(config);
        when(group.getGroupId()).thenReturn(2001L);
        buttons.setImageUrl("https://example.com/captcha.jpg");
        buttons.setImageText("请点击图中第2个表情对应的按钮");
        buttons.setButtonList(List.of(button("1", "🔋"), button("2", "🐰"), button("3", "📖")));
        manager.verifyCount = new VerifyCount();
        ReflectionTestUtils.setField(service, "groupManager", manager);
        ReflectionTestUtils.setField(service, "testService", mock(TestService.class));
        ReflectionTestUtils.setField(service, "xxGroupId", 0L);
        ReflectionTestUtils.setField(service, "knnRetryEnabled", true);
        ReflectionTestUtils.setField(service, "fallbackButtonRandom", new Random() {
            @Override public int nextInt(int bound) { return 2; }
        });
        doNothing().when(service).saveErrorImage(any(), any(), any(), anyLong());
        doReturn(result("🐰")).when(service).recognizeVerifyCode(anyString(), anyString(), eq(bot));
        doReturn(result("📖")).when(service).recognizeVerifyCodeWithKnn(anyString(), anyString(), eq(bot));
    }

    @Test
    void primaryClickIsUnconfirmedUntilRealSuccessReceiptAndDuplicatesCountOnce() {
        process(1);
        assertEquals(1, manager.verifyCount.getPrimary().getAttempts());
        assertEquals(0, manager.verifyCount.getPrimary().getSuccesses());
        assertEquals(1, manager.verifyCount.getPrimary().getUnconfirmed());
        feedback("@1001 验证奖励100灵石", 2);
        feedback("@1001 验证奖励100灵石", 2);
        assertEquals(1, manager.verifyCount.getPrimary().getSuccesses());
        assertEquals(100.0, manager.verifyCount.getPrimary().getSuccessRate());
        assertEquals(0, manager.verifyCount.getPrimary().getUnconfirmed());
        assertEquals(0, manager.verifyCount.getKnn().getAttempts());
    }

    @Test
    void realMarkdownRewardWithoutVerificationKeywordConfirmsPrimaryOnce() {
        process(1);
        String receipt = "[](%7B%22version%22%3A2%7D)\n"
                + "[@黄瞻(发言次数:1000)](mqqapi://markdown/mention?at_type=1&at_tinyid=1001)奖励5574灵石";
        MessageChain chain = new MessageChain();
        chain.add(new MarkdownMessage(receipt));
        service.autoVerifyCode(bot, group, null, chain, "[Markdown]", 2, null);
        service.autoVerifyCode(bot, group, null, chain, "[Markdown]", 2, null);
        assertEquals(1, manager.verifyCount.getPrimary().getSuccesses());
        assertEquals(0, manager.verifyCount.getPrimary().getUnconfirmed());
    }

    @Test
    void separateAtAndMarkdownRewardConfirmsKnnRatherThanPrimary() {
        process(1);
        repeat(2);
        MessageChain chain = new MessageChain().at("1001");
        chain.add(new MarkdownMessage("奖励1826灵石"));
        service.autoVerifyCode(bot, group, null, chain, "", 3, null);
        assertEquals(1, manager.verifyCount.getPrimary().getFailures());
        assertEquals(0, manager.verifyCount.getPrimary().getSuccesses());
        assertEquals(1, manager.verifyCount.getKnn().getSuccesses());
    }

    @Test
    void plainRewardAndCqAtReceiptAreSupported() {
        process(1);
        feedback("@1001 奖励5574灵石", 2);
        buttons.setImageUrl("https://example.com/next.jpg");
        process(3);
        feedback("[CQ:at,qq=1001]奖励1826灵石", 4);
        assertEquals(2, manager.verifyCount.getPrimary().getSuccesses());
    }

    @Test
    void ordinaryRewardsOtherTargetsAndAccountNumberAsRewardAreNotSuccess() {
        process(1);
        for (String text : List.of("悬赏奖励100灵石", "宗门任务奖励100灵石", "秘境奖励100灵石", "不需要验证")) {
            MessageChain chain = new MessageChain().at("1001");
            chain.add(new MarkdownMessage(text));
            service.autoVerifyCode(bot, group, null, chain, "", 2, null);
        }
        MessageChain other = new MessageChain().at("1002");
        other.add(new MarkdownMessage("奖励1001灵石"));
        service.autoVerifyCode(bot, group, null, other, "", 3, null);
        feedback("@10010 奖励5574灵石", 4);
        assertEquals(0, manager.verifyCount.getPrimary().getSuccesses());
        assertEquals(1, manager.verifyCount.getPrimary().getUnconfirmed());
    }

    @Test
    void markdownFailureWithSeparateAtCanStillArchiveKnnError() {
        process(1);
        repeat(2);
        MessageChain chain = new MessageChain().at("1001");
        chain.add(new MarkdownMessage("验证码不正确"));
        service.autoVerifyCode(bot, group, null, chain, "", 3, null);
        assertEquals(1, manager.verifyCount.getKnn().getFailures());
        assertEquals(0, manager.verifyCount.getKnn().getUnconfirmed());
    }

    @Test
    void repeatedCaptchaAttributesFirstFailureToPrimaryAndSecondSuccessToKnn() {
        process(1);
        repeat(2);
        feedback("@1001 验证通过", 3);
        assertEquals(1, manager.verifyCount.getPrimary().getAttempts());
        assertEquals(1, manager.verifyCount.getPrimary().getFailures());
        assertEquals(0, manager.verifyCount.getPrimary().getSuccesses());
        assertEquals(1, manager.verifyCount.getKnn().getAttempts());
        assertEquals(1, manager.verifyCount.getKnn().getSuccesses());
        assertEquals(100.0, manager.verifyCount.getKnn().getSuccessRate());
    }

    @Test
    void noUsablePrimaryAnswerIsOneFailureBeforeKnnRequest() {
        doReturn(result("无法匹配")).when(service).recognizeVerifyCode(anyString(), anyString(), eq(bot));
        process(1);
        feedback("@1001 验证成功", 2);
        assertEquals(1, manager.verifyCount.getPrimary().getFailures());
        assertEquals(1, manager.verifyCount.getKnn().getSuccesses());
    }

    @Test
    void knnWithoutUsableResultIsFailureAndRandomSuccessIsNotCredited() {
        doReturn(result("无法匹配")).when(service).recognizeVerifyCode(anyString(), anyString(), eq(bot));
        doReturn(result("识别失败")).when(service).recognizeVerifyCodeWithKnn(anyString(), anyString(), eq(bot));
        process(1);
        feedback("@1001 验证成功", 2);
        assertEquals(1, manager.verifyCount.getPrimary().getFailures());
        assertEquals(1, manager.verifyCount.getKnn().getFailures());
        assertEquals(0, manager.verifyCount.getKnn().getSuccesses());
    }

    @Test
    void sameWrongKnnAnswerCountsOneFailureEvenWhenNoButtonIsSubmitted() {
        doReturn(result("🐰")).when(service).recognizeVerifyCodeWithKnn(anyString(), anyString(), eq(bot));
        process(1);
        repeat(2);
        feedback("@1001 验证成功", 3);
        assertEquals(1, manager.verifyCount.getPrimary().getFailures());
        assertEquals(1, manager.verifyCount.getKnn().getFailures());
        assertEquals(0, manager.verifyCount.getKnn().getSuccesses());
    }

    @Test
    void explicitFailureDoesNotDoubleCountWhenSameCaptchaThenReturns() {
        process(1);
        feedback("@1001 验证未通过", 2);
        repeat(3);
        feedback("@1001 验证失败", 4);
        repeat(5);
        assertEquals(1, manager.verifyCount.getPrimary().getFailures());
        assertEquals(1, manager.verifyCount.getKnn().getFailures());
        assertEquals(0, manager.verifyCount.getKnn().getSuccesses());
    }

    @Test
    void duplicatePromptDoesNotTurnSubmittedKnnAnswerIntoFailure() {
        process(1);
        repeat(2);
        repeat(2);
        assertEquals(0, manager.verifyCount.getKnn().getFailures());
        assertEquals(1, manager.verifyCount.getKnn().getUnconfirmed());
        verify(service, times(1)).recognizeVerifyCodeWithKnn(anyString(), anyString(), eq(bot));
        feedback("@1001 验证成功", 3);
        assertEquals(1, manager.verifyCount.getKnn().getSuccesses());
    }

    @Test
    void unrelatedRewardsOtherAccountsAndCaptchaPromptAreIgnored() {
        process(1);
        feedback("@1002 验证成功", 2);
        feedback("@10010 验证成功", 3);
        feedback("@1001 悬赏奖励100灵石", 4);
        feedback("@1001 不需要验证", 5);
        feedback("@1001 请点击表情，验证成功后奖励100灵石", 6);
        assertEquals(1, manager.verifyCount.getPrimary().getUnconfirmed());
        assertEquals(0, manager.verifyCount.getPrimary().getSuccesses());
    }

    @Test
    void otherGroupFeedbackCannotCompleteAnAttempt() {
        process(1);
        Group other = mock(Group.class);
        when(other.getGroupId()).thenReturn(2002L);
        service.autoVerifyCode(bot, other, null, new MessageChain(), "@1001 验证成功", 2, null);
        assertEquals(0, manager.verifyCount.getPrimary().getSuccesses());
    }

    @Test
    void expiredOrTooLateFeedbackStaysUnconfirmed() {
        process(1);
        feedback("@1001 验证码已过期", 2);
        feedback("@1001 验证成功", 3);
        assertEquals(1, manager.verifyCount.getPrimary().getUnconfirmed());
        buttons.setImageUrl("https://example.com/next.jpg");
        process(4);
        Map<?, ?> attempts = (Map<?, ?>) ReflectionTestUtils.getField(service, "verificationAttempts");
        ReflectionTestUtils.setField(attempts.get(1001L), "submittedAt", System.currentTimeMillis() - 6 * 60 * 1000L);
        feedback("@1001 验证成功", 5);
        assertEquals(2, manager.verifyCount.getPrimary().getUnconfirmed());
        assertEquals(0, manager.verifyCount.getPrimary().getFailures());
    }

    @Test
    void timeoutAndMembershipFailureAreUnconfirmedRatherThanRecognitionFailures() {
        RecognitionResult timeout = result("");
        timeout.setResult("请求超时");
        doReturn(timeout).when(service).recognizeVerifyCode(anyString(), anyString(), eq(bot));
        process(1);
        RecognitionResult blocked = result("");
        blocked.setCode(40302);
        doReturn(blocked).when(service).recognizeVerifyCode(anyString(), anyString(), eq(bot));
        buttons.setImageUrl("https://example.com/next.jpg");
        process(2);
        assertEquals(2, manager.verifyCount.getPrimary().getUnconfirmed());
        assertEquals(0, manager.verifyCount.getPrimary().getFailures());
        assertEquals(0, manager.verifyCount.getKnn().getAttempts());
    }

    @Test
    void clickApiExceptionDoesNotCountAsRecognitionFailureOrSuccess() {
        config.setAutoVerifyModel(1);
        doThrow(new IllegalStateException("click failed")).when(bot).clickKeyboardButton(anyLong(), any(), any(), any(), any());
        process(1);
        feedback("@1001 验证成功", 2);
        assertEquals(1, manager.verifyCount.getPrimary().getUnconfirmed());
        assertEquals(0, manager.verifyCount.getPrimary().getFailures());
        assertEquals(0, manager.verifyCount.getPrimary().getSuccesses());
    }

    @Test
    void resettingStatisticsDoesNotBringBackOutstandingOldCounts() {
        process(1);
        VerifyCount old = manager.verifyCount;
        manager.verifyCount = new VerifyCount();
        feedback("@1001 验证成功", 2);
        assertEquals(1, old.getPrimary().getSuccesses());
        assertEquals(0, manager.verifyCount.getPrimary().getSuccesses());
        assertEquals(0, manager.verifyCount.getPrimary().getAttempts());
    }

    @Test
    void switchingToManualLeavesOutstandingAutomaticAttemptUnconfirmed() {
        process(1);
        config.setAutoVerifyModel(0);
        feedback("@1001 验证成功", 2);
        assertEquals(0, manager.verifyCount.getPrimary().getSuccesses());
        assertEquals(1, manager.verifyCount.getPrimary().getUnconfirmed());
    }

    @Test
    void manualControlClickDoesNotCreditTheOutstandingAutomaticModel() {
        process(1);
        TestService manualService = new TestService();
        ObjectProvider<RemoteVerifyCode> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        ReflectionTestUtils.setField(manualService, "remoteVerifyCodeProvider", provider);
        manualService.botButtonMap.put(1001L, buttons);
        ReflectionTestUtils.invokeMethod(manualService, "clickButton", bot, group, null, new MessageChain(), "点击序号3");
        verify(bot, timeout(3000).times(2)).clickKeyboardButton(anyLong(), any(), any(), any(), any());
        feedback("@1001 验证成功", 2);
        assertEquals(0, manager.verifyCount.getPrimary().getSuccesses());
        assertEquals(1, manager.verifyCount.getPrimary().getUnconfirmed());
    }

    @Test
    void lateRepeatedCaptchaDoesNotTurnExpiredAttemptIntoRecognitionFailure() {
        process(1);
        Map<?, ?> attempts = (Map<?, ?>) ReflectionTestUtils.getField(service, "verificationAttempts");
        ReflectionTestUtils.setField(attempts.get(1001L), "submittedAt", System.currentTimeMillis() - 6 * 60 * 1000L);
        repeat(2);
        assertEquals(0, manager.verifyCount.getPrimary().getFailures());
        assertEquals(1, manager.verifyCount.getPrimary().getUnconfirmed());
    }

    @Test
    void duplicateSuccessReceiptCannotBeReusedForANewAttempt() {
        process(1);
        feedback("@1001 验证成功", 2);
        buttons.setImageUrl("https://example.com/next.jpg");
        process(3);
        feedback("@1001 验证成功", 2);
        assertEquals(1, manager.verifyCount.getPrimary().getSuccesses());
        assertEquals(1, manager.verifyCount.getPrimary().getUnconfirmed());
    }

    @Test
    void confirmedKnnFailureReportsSnapshotOnceWhilePrimaryFailureDoesNot() {
        RecognitionResult knn = result("📖");
        String reviewId = "a".repeat(64);
        knn.setKnnReviewId(reviewId);
        doReturn(knn).when(service).recognizeVerifyCodeWithKnn(anyString(), anyString(), eq(bot));
        doNothing().when(service).reportKnnFailure(anyString(), any(), any(), anyLong(), anyString());
        process(1);
        feedback("@1001 验证失败", 2);
        verify(service, never()).reportKnnFailure(anyString(), any(), any(), anyLong(), anyString());
        repeat(3);
        feedback("@1001 验证失败", 4);
        feedback("@1001 验证失败", 4);
        verify(service, timeout(3000).times(1)).reportKnnFailure(eq(reviewId), any(), eq("📖"), eq(1001L), eq("game_rejected"));
    }

    @Test
    void successfulKnnAndExpiredKnnDoNotEnterTheErrorFolder() {
        RecognitionResult knn = result("📖");
        knn.setKnnReviewId("b".repeat(64));
        doReturn(knn).when(service).recognizeVerifyCodeWithKnn(anyString(), anyString(), eq(bot));
        doNothing().when(service).reportKnnFailure(anyString(), any(), any(), anyLong(), anyString());
        process(1);
        repeat(2);
        feedback("@1001 验证成功", 3);
        verify(service, never()).reportKnnFailure(anyString(), any(), any(), anyLong(), anyString());
        buttons.setImageUrl("https://example.com/next.jpg");
        doReturn(result("🐰")).when(service).recognizeVerifyCode(anyString(), anyString(), eq(bot));
        RecognitionResult nextKnn = result("📖");
        nextKnn.setKnnReviewId("d".repeat(64));
        doReturn(nextKnn).when(service).recognizeVerifyCodeWithKnn(anyString(), anyString(), eq(bot));
        process(4); repeat(5);
        feedback("@1001 验证码已过期", 6);
        verify(service, never()).reportKnnFailure(anyString(), any(), any(), anyLong(), anyString());
    }

    @Test
    void knnWithoutUsableAnswerIsReportedAndDoesNotUploadAGenericErrorPicture() {
        doReturn(result("无法匹配")).when(service).recognizeVerifyCode(anyString(), anyString(), eq(bot));
        RecognitionResult knn = result("");
        knn.setResult("识别失败");
        knn.setKnnReviewId("c".repeat(64));
        doReturn(knn).when(service).recognizeVerifyCodeWithKnn(anyString(), anyString(), eq(bot));
        doNothing().when(service).reportKnnFailure(anyString(), any(), any(), anyLong(), anyString());
        process(1);
        verify(service, timeout(3000)).reportKnnFailure(eq(knn.getKnnReviewId()), any(), any(), eq(1001L), eq("no_usable_answer"));
        verify(service, never()).saveErrorImage(any(), any(), any(), anyLong());
    }

    private void process(int messageId) {
        ReflectionTestUtils.invokeMethod(service, "autoVerifyCode", bot, group, new MessageChain(), "captcha", messageId, buttons);
    }

    private void repeat(int messageId) {
        ReflectionTestUtils.invokeMethod(service, "retryRepeatedCaptcha", bot, group, new MessageChain(), "captcha", messageId,
                buttons, service.codeUrlMap.get(1001L));
    }

    private void feedback(String text, int messageId) {
        service.autoVerifyCode(bot, group, null, new MessageChain(), text, messageId, null);
    }

    private RecognitionResult result(String answer) {
        RecognitionResult result = new RecognitionResult();
        result.setAnswer(answer);
        result.setResult("");
        result.setUrl(buttons.getImageUrl());
        result.setTitle(buttons.getImageText());
        return result;
    }

    private Button button(String id, String label) {
        Button button = mock(Button.class);
        when(button.getId()).thenReturn(id);
        when(button.getLabel()).thenReturn(label);
        return button;
    }
}
