package top.sshh.qqbot.verifycode;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.RecognitionResult;
import top.sshh.qqbot.service.storage.LocalDataFiles;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CaptchaStatisticsClientTest {
    @TempDir Path directory;
    private final List<CaptchaStatisticsClient> clients = new ArrayList<>();

    @AfterEach void close() { clients.forEach(CaptchaStatisticsClient::close); }

    private OfflineClient client() {
        OfflineClient client = new OfflineClient(); clients.add(client);
        ReflectionTestUtils.setField(client, "apiUrl", "http://api.example");
        ReflectionTestUtils.setField(client, "identityFile", directory.resolve("identity.json").toString());
        ReflectionTestUtils.setField(client, "outboxFile", directory.resolve("outbox.json").toString());
        ReflectionTestUtils.setField(client, "readKeyFile", directory.resolve("read.key").toString());
        client.initialize(); return client;
    }

    private RecognitionResult ticket() {
        RecognitionResult result = new RecognitionResult();
        result.setRecognitionId(UUID.randomUUID().toString().replace("-", ""));
        result.setFeedbackToken("t".repeat(43)); return result;
    }

    @Test void identitySurvivesRestartAndDifferentServicesGetDifferentIds() {
        String id = client().requestContext().getString("serverId");
        assertEquals(id, client().requestContext().getString("serverId"));
        OfflineClient other = client();
        ReflectionTestUtils.setField(other, "identityFile", directory.resolve("other.json").toString());
        ReflectionTestUtils.setField(other, "initialized", false);
        assertNotEquals(id, other.requestContext().getString("serverId"));
    }

    @Test void failedFeedbackSurvivesRestartAndKeepsItsOriginalApiDestination() throws Exception {
        OfflineClient first = client(); RecognitionResult result = ticket();
        first.trackRecognition(result, "primary", 1001);
        first.reportOutcome(result, "success", "game_accepted");
        first.networkFailure = true; first.drain();
        assertEquals(1, first.requests.size()); first.close();
        OfflineClient restarted = client();
        ReflectionTestUtils.setField(restarted, "apiUrl", "http://new-api.example");
        restarted.drain();
        assertEquals("http://api.example", restarted.lastBase);
        assertEquals("success", restarted.requests.get(0).getString("outcome"));
        restarted.drain(); assertEquals(1, restarted.requests.size());
        assertTrue(JSON.parseObject(LocalDataFiles.readString(directory.resolve("outbox.json"))).isEmpty());
    }

    @Test void restartClosesUnresolvedRecognitionAsUnconfirmed() {
        OfflineClient first = client(); RecognitionResult result = ticket();
        first.trackRecognition(result, "knn", 1001); first.close();
        OfflineClient restarted = client(); restarted.drain();
        assertEquals("unconfirmed", restarted.requests.get(0).getString("outcome"));
        assertEquals("client_restart", restarted.requests.get(0).getString("reason"));
    }

    @Test void duplicateTrackingAndOutcomesOnlySendOneSettlement() {
        OfflineClient client = client(); RecognitionResult result = ticket();
        client.trackRecognition(result, "primary", 1001); client.trackRecognition(result, "primary", 1001);
        client.reportOutcome(result, "failure", "game_rejected");
        client.reportOutcome(result, "success", "game_accepted"); client.drain(); client.drain();
        assertEquals(1, client.requests.size());
        assertEquals("failure", client.requests.get(0).getString("outcome"));
    }

    @Test void serverErrorsRetryAndResetTicketsStopRetrying() {
        OfflineClient client = client(); RecognitionResult result = ticket();
        client.trackRecognition(result, "primary", 1001); client.reportOutcome(result, "success", "game_accepted");
        client.status = 503; client.drain(); client.status = 404; client.drain(); client.drain();
        assertEquals(2, client.requests.size());
    }

    @Test void noReceiptTimesOutWithoutEnteringSuccessOrFailure() {
        OfflineClient client = client(); RecognitionResult result = ticket();
        client.trackRecognition(result, "primary", 1001);
        @SuppressWarnings("unchecked") java.util.Map<String, CaptchaStatisticsClient.PendingFeedback> outbox =
                (java.util.Map<String, CaptchaStatisticsClient.PendingFeedback>) ReflectionTestUtils.getField(client, "outbox");
        outbox.get(result.getRecognitionId()).createdAt -= 11 * 60 * 1000L;
        client.drain();
        assertEquals("unconfirmed", client.requests.get(0).getString("outcome"));
        assertEquals("feedback_timeout", client.requests.get(0).getString("reason"));
    }

    @Test void legacyResultsWithoutTicketsDoNotGenerateFeedback() {
        OfflineClient client = client(); RecognitionResult result = new RecognitionResult();
        client.trackRecognition(result, "primary", 1001); client.reportOutcome(result, "success", "game_accepted");
        client.drain(); assertTrue(client.requests.isEmpty());
    }

    @Test void qqCommandsUseTheApiForGlobalAndFilteredStatistics() throws Exception {
        OfflineClient client = client(); Files.writeString(directory.resolve("read.key"), "key-from-api");
        assertEquals("来自 API 的统计", client.queryCommand("验证统计"));
        assertEquals("statistics/summary", client.lastPath); assertEquals("key-from-api", client.lastKey);
        client.queryCommand("验证统计 服务器 server-a QQ 123456789");
        assertEquals("statistics/summary?server_id=server-a&qq=123456789", client.lastPath);
        client.queryCommand("验证统计 QQ 123456789"); assertEquals("statistics/summary?qq=123456789", client.lastPath);
        client.queryCommand("验证统计 服务器列表"); assertEquals("statistics/servers", client.lastPath);
        assertTrue(client.queryCommand("验证统计 QQ 123&qq=456").startsWith("统计命令"));
    }

    @Test void feedbackUsesJsonAndQueriesCarryTheReadKeyOverHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JSONObject> received = new AtomicReference<>(); AtomicReference<String> key = new AtomicReference<>();
        server.createContext("/statistics/feedback", exchange -> {
            received.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.createContext("/statistics/summary", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("X-Statistics-Key"));
            byte[] response = "{\"text\":\"统一统计\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            CaptchaStatisticsClient client = new CaptchaStatisticsClient(); clients.add(client);
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            JSONObject body = new JSONObject(); body.put("reason", "game_accepted");
            assertEquals(200, client.request(base, "statistics/feedback", body, null).status);
            assertEquals("game_accepted", received.get().getString("reason"));
            assertEquals("统一统计", client.request(base, "statistics/summary", null, "secret").body.getString("text"));
            assertEquals("secret", key.get());
        } finally { server.stop(0); }
    }

    private static class OfflineClient extends CaptchaStatisticsClient {
        final List<JSONObject> requests = new ArrayList<>();
        String lastPath, lastBase, lastKey; int status = 200; boolean networkFailure;
        @Override public void flushFeedback() { }
        void drain() { super.flushFeedback(); }
        @Override protected Reply request(String base, String path, JSONObject body, String key) throws IOException {
            lastBase = base; lastPath = path; lastKey = key;
            if (body != null) { requests.add(JSON.parseObject(body.toJSONString())); }
            if (networkFailure) { throw new IOException("offline"); }
            JSONObject reply = new JSONObject(); reply.put("code", 0); reply.put("text", "来自 API 的统计");
            return new Reply(status, reply);
        }
    }
}
