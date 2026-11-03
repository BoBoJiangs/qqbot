package top.sshh.qqbot.verifycode;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Button;
import com.zhuangxv.bot.core.Buttons;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.sshh.qqbot.data.RecognitionResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RemoteVerifyCodeImageUploadTest {
    private static final String ID = "a".repeat(32);
    private static final String TOKEN = "t".repeat(43);
    private final RemoteVerifyCode service = new RemoteVerifyCode();
    private final byte[] image = {0, 1, 2, 3, -1, -128, 10, 13};
    private HttpServer server;
    private String base;
    private JSONObject firstReply;
    private JSONObject uploadReply;
    private final AtomicReference<JSONObject> originalRequest = new AtomicReference<>();
    private final AtomicReference<JSONObject> uploadedRequest = new AtomicReference<>();
    private final AtomicInteger uploadCount = new AtomicInteger();
    private final AtomicInteger downloadCount = new AtomicInteger();
    private int imageStatus = 200;
    private int uploadStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        firstReply = JSON.parseObject("{\"code\":1,\"serviceError\":true,\"uploadFallback\":true,\"result\":\"识别失败：图片下载失败\"}");
        firstReply.put("recognitionId", ID); firstReply.put("feedbackToken", TOKEN);
        uploadReply = JSON.parseObject("{\"code\":0,\"emojiList\":[\"兔子\"],\"result\":\"\",\"recognizer\":\"primary\"}");
        uploadReply.put("recognitionId", ID); uploadReply.put("feedbackToken", TOKEN);
        server.createContext("/recognize", exchange -> {
            originalRequest.set(read(exchange)); reply(exchange, 200, firstReply);
        });
        server.createContext("/recognize/fallback", exchange -> {
            uploadedRequest.set(read(exchange)); uploadCount.incrementAndGet();
            assertEquals("qq=1001", exchange.getRequestURI().getQuery());
            reply(exchange, uploadStatus, uploadReply);
        });
        server.createContext("/image", exchange -> {
            downloadCount.incrementAndGet(); exchange.sendResponseHeaders(imageStatus, image.length);
            exchange.getResponseBody().write(image); exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    void downloadFailureUploadsUnchangedBytesAndReusesTheTicket() {
        RecognitionResult result = recognize();
        assertEquals(0, result.getCode()); assertFalse(result.isServiceError());
        assertEquals(ID, result.getRecognitionId()); assertEquals(TOKEN, result.getFeedbackToken());
        assertEquals(1, uploadCount.get()); assertEquals(1, downloadCount.get());
        JSONObject upload = uploadedRequest.get();
        assertEquals(ID, upload.getString("recognitionId"));
        assertEquals(TOKEN, upload.getString("feedbackToken"));
        assertEquals(base + "image", upload.getString("url"));
        assertArrayEquals(image, Base64.getDecoder().decode(upload.getString("imageBase64")));
        assertEquals(base + "image", originalRequest.get().getString("url"));
    }

    @Test
    void uploadedRecoveryIsCountedOnceAndStillWaitsForGameReceipt() {
        Bot bot = mock(Bot.class); Group group = mock(Group.class); Button button = mock(Button.class);
        BotConfig config = new BotConfig(); config.setAutoVerifyModel(2); config.setGroupId(2001L);
        when(bot.getBotId()).thenReturn(1001L); when(bot.getBotConfig()).thenReturn(config);
        when(group.getGroupId()).thenReturn(2001L); when(button.getLabel()).thenReturn("兔子");
        Buttons buttons = new Buttons(); buttons.setImageUrl(base + "image");
        buttons.setImageText("请点击图中第1个表情对应的按钮"); buttons.setButtonList(List.of(button));
        RecordingStatisticsClient statistics = new RecordingStatisticsClient();
        ReflectionTestUtils.setField(service, "shituApiUrl", base);
        ReflectionTestUtils.setField(service, "statisticsClient", statistics);
        ReflectionTestUtils.setField(service, "xxGroupId", 0L);

        ReflectionTestUtils.invokeMethod(service, "autoVerifyCode", bot, group, new MessageChain(), "captcha", 1, buttons);
        assertEquals(1, statistics.counts.getPrimary().getAttempts());
        assertEquals(0, statistics.counts.getPrimary().getSuccesses());
        assertEquals(1, uploadCount.get());
        verify(bot, times(1)).clickKeyboardButton(anyLong(), any(), any(), any(), any());
        service.autoVerifyCode(bot, group, null, new MessageChain(), "@1001 验证成功", 2, null);
        assertEquals(1, statistics.counts.getPrimary().getSuccesses());
        assertEquals(1, statistics.counts.getPrimary().getAttempts());
    }

    @Test
    void knnRequestStillUsesItsOriginalKnnTicket() {
        uploadReply.put("recognizer", "knn");
        uploadReply.put("knnAppliedIndices", new int[]{0});
        RecognitionResult result = service.callRecognitionAPI(base, base + "image", 1001L, "knn");
        assertEquals("knn", originalRequest.get().getString("recognizer"));
        assertEquals("knn", result.getRecognizer());
        assertEquals(ID, result.getRecognitionId());
    }

    @Test
    void legacyServiceWithoutUploadCapabilityDoesNotTryAnUnsupportedEndpoint() {
        firstReply.remove("uploadFallback");
        assertTrue(recognize().isServiceError());
        assertEquals(0, uploadCount.get()); assertEquals(0, downloadCount.get());
    }

    @Test
    void ordinaryRecognitionMissDoesNotUseImageUploadFallback() {
        firstReply.put("serviceError", false);
        RecognitionResult result = recognize();
        assertFalse(result.isServiceError()); assertEquals(0, uploadCount.get());
    }

    @Test
    void localDownloadFailurePreservesOriginalUnconfirmedTicket() {
        imageStatus = 503;
        RecognitionResult result = recognize();
        assertTrue(result.isServiceError()); assertEquals(ID, result.getRecognitionId());
        assertEquals(1, downloadCount.get()); assertEquals(0, uploadCount.get());
    }

    @Test
    void rejectedUploadIsNotRetriedOrCountedAsAnotherRecognition() {
        uploadStatus = 409;
        RecognitionResult result = recognize();
        assertTrue(result.isServiceError()); assertEquals(ID, result.getRecognitionId());
        assertEquals(1, uploadCount.get());
    }

    @Test
    void uploadCannotReplaceOriginalRecognitionWithAnotherTicket() {
        uploadReply.put("recognitionId", "b".repeat(32));
        RecognitionResult result = recognize();
        assertTrue(result.isServiceError()); assertEquals(ID, result.getRecognitionId());
    }

    @Test
    void oversizedOriginalIsNotBufferedOrUploaded() throws IOException {
        server.removeContext("/image");
        server.createContext("/image", exchange -> {
            exchange.sendResponseHeaders(200, 5000001L); exchange.close();
        });
        RecognitionResult result = recognize();
        assertTrue(result.isServiceError()); assertEquals(0, uploadCount.get());
    }

    private RecognitionResult recognize() {
        return service.callRecognitionAPI(base, base + "image", 1001L, "primary");
    }

    private JSONObject read(HttpExchange exchange) throws IOException {
        return JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void reply(HttpExchange exchange, int status, JSONObject body) throws IOException {
        byte[] bytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
}
