package top.sshh.qqbot.verifycode;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.*;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.RecognitionResult;
import top.sshh.qqbot.data.VerifyCount;
import top.sshh.qqbot.service.GroupManager;
import top.sshh.qqbot.service.TestService;

import java.util.List;
import java.util.Random;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RemoteVerifyCodeKnnRetryTest {
    private final RemoteVerifyCode service = spy(new RemoteVerifyCode());
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final BotConfig config = new BotConfig();
    private final Buttons buttons = new Buttons();
    private final TestService reminders = mock(TestService.class);
    private int repeatedMessageId = 2;

    @BeforeEach
    void setup() {
        config.setAutoVerifyModel(2); config.setGroupId(2001L); config.setMasterQQ(3001L);
        when(bot.getBotId()).thenReturn(1001L); when(bot.getBotConfig()).thenReturn(config);
        when(group.getGroupId()).thenReturn(2001L);
        buttons.setImageUrl("https://example.com/captcha.jpg");
        buttons.setImageText("请点击图中第2个表情对应的按钮");
        buttons.setBotAppid("app"); buttons.setMsgSeq("seq");
        buttons.setButtonList(List.of(button("1","🔋"),button("2","🐰"),button("3","📖")));
        GroupManager manager = mock(GroupManager.class); manager.verifyCount = new VerifyCount();
        ReflectionTestUtils.setField(service,"groupManager",manager);
        ReflectionTestUtils.setField(service,"testService",reminders);
        ReflectionTestUtils.setField(service,"xxGroupId",0L);
        ReflectionTestUtils.setField(service,"knnRetryEnabled",true);
        Random random = new Random() { @Override public int nextInt(int bound) { return 2; } };
        ReflectionTestUtils.setField(service,"fallbackButtonRandom",random);
        doNothing().when(service).saveErrorImage(any(),any(),any(),anyLong());
        doReturn(result("🐰")).when(service).recognizeVerifyCodeWithKnn(anyString(),anyString(),eq(bot));
    }

    @Test void unusableFirstAnswerUsesKnnBeforeRandomFallback() {
        primary(result("无法匹配")); process();
        verifyKnn(1); verifyButton("2",1); verifyButton("3",0);
        assertTrue(service.codeUrlMap.get(1001L).isKnnAttempted());
    }

    @Test void explicitRecognitionFailureUsesKnnOnce() {
        RecognitionResult failed=result(""); failed.setResult("识别失败"); primary(failed); process();
        verifyKnn(1); verifyButton("2",1);
    }

    @Test void firstSuccessfulAnswerDoesNotCallKnn() {
        primary(result("🔋")); process(); verifyKnn(0); verifyButton("1",1);
    }

    @Test void semiAutomaticModeAlsoGetsOneKnnRetry() {
        config.setAutoVerifyModel(1); primary(result("无效答案")); process();
        verifyKnn(1); verifyButton("2",1); verifyNoInteractions(reminders);
    }

    @Test void bothRecognizersFailThenOriginalFullAutomaticFallbackRuns() {
        primary(result("无效答案")); doReturn(result("识别失败")).when(service)
            .recognizeVerifyCodeWithKnn(anyString(),anyString(),eq(bot));
        process(); verifyKnn(1); verifyButton("3",1);
    }

    @Test void repeatedCaptchaRetriesKnnWithoutCallingPrimaryAgain() {
        RecognitionResult first=result("🔋"); service.codeUrlMap.put(1001L,first);
        repeat(first); verifyKnn(1); verifyButton("2",1);
        verify(service,never()).recognizeVerifyCode(anyString(),anyString(),any());
        repeat(service.codeUrlMap.get(1001L));
        verifyKnn(1); verifyButton("3",1);
    }

    @Test void repeatedWrongAnswerIsNotClickedAgainWhenKnnAgreesWithIt() {
        RecognitionResult first=result("🔋"); service.codeUrlMap.put(1001L,first);
        doReturn(result("🔋")).when(service).recognizeVerifyCodeWithKnn(anyString(),anyString(),eq(bot));
        repeat(first); verifyKnn(1); verifyButton("1",0); verifyButton("3",1);
    }

    @Test void eachNewImageCanHaveItsOwnSingleRetry() {
        primary(result("无效答案")); process();
        buttons.setImageUrl("https://example.com/new.jpg"); primary(result("无效答案")); process();
        verifyKnn(2); verifyButton("2",2);
    }

    @Test void disabledRetryKeepsOriginalFallback() {
        ReflectionTestUtils.setField(service,"knnRetryEnabled",false);
        primary(result("无效答案")); process(); verifyKnn(0); verifyButton("3",1);
    }

    @Test void timeoutAndBlockedMembershipDoNotTriggerKnn() {
        RecognitionResult timeout=result(""); timeout.setResult("请求超时"); primary(timeout); process();
        verifyKnn(0);
        RecognitionResult blocked=result(""); blocked.setCode(40302); primary(blocked); process();
        verifyKnn(0);
    }

    @Test void keyboardApiFailureIsNotARecognitionRetry() {
        primary(result("🐰"));
        doThrow(new IllegalStateException("click failed")).when(bot).clickKeyboardButton(anyLong(),any(),any(),any(),any());
        process(); verifyKnn(0); verifyButton("2",2);
        assertTrue(service.codeUrlMap.get(1001L).isClickFailed());
        repeat(service.codeUrlMap.get(1001L)); verifyKnn(0);
    }

    @Test void targetMustHaveActuallyUsedKnnAndPositionsMustNotBeCompressed() {
        RecognitionResult partial=result(""); partial.setRecognizer("knn");
        partial.setEmojiList(List.of("书本","兔子")); partial.setKnnAppliedIndices(List.of(0));
        doReturn(partial).when(service).callRecognitionAPI(any(),anyString(),anyLong(),eq("knn"));
        doCallRealMethod().when(service).recognizeVerifyCodeWithKnn(anyString(),anyString(),eq(bot));
        RecognitionResult rejected=service.recognizeVerifyCodeWithKnn(buttons.getImageUrl(),buttons.getImageText(),bot);
        assertEquals("",rejected.getAnswer());
        partial.setKnnAppliedIndices(List.of(1));
        RecognitionResult accepted=service.recognizeVerifyCodeWithKnn(buttons.getImageUrl(),buttons.getImageText(),bot);
        assertEquals("兔子",accepted.getAnswer()); assertEquals(1,accepted.getLastClickIndex());
    }

    @Test void oldBackendCannotPretendToHaveUsedKnn() {
        RecognitionResult old=result("");old.setEmojiList(List.of("书本","兔子"));
        doReturn(old).when(service).callRecognitionAPI(any(),anyString(),anyLong(),eq("knn"));
        doCallRealMethod().when(service).recognizeVerifyCodeWithKnn(anyString(),anyString(),eq(bot));
        assertEquals("",service.recognizeVerifyCodeWithKnn(buttons.getImageUrl(),buttons.getImageText(),bot).getAnswer());
    }

    @Test void newerCaptchaCancelsTheOldKnnResult() {
        primary(result("无效答案"));
        RecognitionResult next=result("📖");next.setUrl("https://example.com/new.jpg");
        doAnswer(invocation->{
            Buttons latest=new Buttons();latest.setImageUrl(next.getUrl());
            service.botButtonMap.put(1001L,latest);service.codeUrlMap.put(1001L,next);
            return result("🐰");
        }).when(service).recognizeVerifyCodeWithKnn(anyString(),anyString(),eq(bot));
        process();verifyKnn(1);
        verify(bot,never()).clickKeyboardButton(anyLong(),any(),any(),any(),any());
        assertSame(next,service.codeUrlMap.get(1001L));
    }

    @Test void switchingToManualDuringKnnCancelsClicking() {
        primary(result("无效答案"));
        doAnswer(invocation->{config.setAutoVerifyModel(0);return result("🐰");})
            .when(service).recognizeVerifyCodeWithKnn(anyString(),anyString(),eq(bot));
        process();verifyKnn(1);
        verify(bot,never()).clickKeyboardButton(anyLong(),any(),any(),any(),any());
    }

    @Test void httpProtocolExplicitlyRequestsKnnAndPreservesThePrimaryRequest() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<JSONObject> requestBody=new AtomicReference<>();
        AtomicReference<String> query=new AtomicReference<>();
        server.createContext("/recognize",exchange->{
            requestBody.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            query.set(exchange.getRequestURI().getQuery());
            byte[] reply="{\"code\":0,\"result\":\"\",\"emojiList\":[\"电池\",\"兔子\"],\"recognizer\":\"knn\",\"knnAppliedIndices\":[1]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
        });
        server.createContext("/knn/report_failure", exchange->{
            requestBody.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            query.set(exchange.getRequestURI().getQuery());
            byte[] reply="{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);exchange.getResponseBody().write(reply);exchange.close();
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort()+"/";
            ReflectionTestUtils.setField(service,"shituApiUrl",base);
            doCallRealMethod().when(service).recognizeVerifyCodeWithKnn(anyString(),anyString(),eq(bot));
            String image="https://example.com/captcha.png?name=\"quoted\"";
            RecognitionResult result=service.recognizeVerifyCodeWithKnn(image,buttons.getImageText(),bot);
            assertEquals("兔子",result.getAnswer());
            assertEquals("knn",requestBody.get().getString("recognizer"));
            assertEquals(image,requestBody.get().getString("url"));
            assertEquals("qq=1001",query.get());
            service.callShituAPI(base,image,1001L);
            assertFalse(requestBody.get().containsKey("recognizer"));
            service.reportKnnFailure("a".repeat(64), "请点击第2个表情", "书本", 1001L, "game_rejected");
            assertEquals("a".repeat(64), requestBody.get().getString("reviewId"));
            assertEquals("game_rejected", requestBody.get().getString("reason"));
            assertEquals("书本", requestBody.get().getString("answer"));
            assertEquals("qq=1001", query.get());
        } finally {server.stop(0);}
    }

    private Button button(String id,String label) {
        Button b=mock(Button.class); when(b.getId()).thenReturn(id);when(b.getData()).thenReturn("data-"+id);
        when(b.getLabel()).thenReturn(label);return b;
    }
    private RecognitionResult result(String answer) {
        RecognitionResult r=new RecognitionResult();r.setAnswer(answer);r.setResult("");
        r.setUrl(buttons.getImageUrl());r.setTitle(buttons.getImageText());return r;
    }
    private void primary(RecognitionResult result) {
        doReturn(result).when(service).recognizeVerifyCode(anyString(),anyString(),eq(bot));
    }
    private void process() {
        ReflectionTestUtils.invokeMethod(service,"autoVerifyCode",bot,group,new MessageChain(),"captcha",1,buttons);
    }
    private void repeat(RecognitionResult first) {
        ReflectionTestUtils.invokeMethod(service,"retryRepeatedCaptcha",bot,group,new MessageChain(),"captcha",repeatedMessageId++,buttons,first);
    }
    private void verifyKnn(int times) {
        verify(service,times(times)).recognizeVerifyCodeWithKnn(anyString(),anyString(),eq(bot));
    }
    private void verifyButton(String id,int times) {
        verify(bot,times(times)).clickKeyboardButton(2001L,"app",id,"data-"+id,"seq");
    }
}
