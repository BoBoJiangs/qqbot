package top.sshh.qqbot.verifycode;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.RecognitionResult;
import top.sshh.qqbot.service.storage.LocalDataFiles;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class CaptchaStatisticsClient {
    private static final Logger log = LoggerFactory.getLogger(CaptchaStatisticsClient.class);
    @Value("${captcha.shitu-api-url:}") private String apiUrl = "";
    @Value("${captcha.statistics.server-id:}") private String configuredServerId = "";
    @Value("${captcha.statistics.server-name:}") private String configuredServerName = "";
    @Value("${captcha.statistics.identity-file:./config/captcha-statistics-instance.json}")
    private String identityFile = "./config/captcha-statistics-instance.json";
    @Value("${captcha.statistics.outbox-file:./config/captcha-statistics-outbox.json}")
    private String outboxFile = "./config/captcha-statistics-outbox.json";
    @Value("${captcha.statistics.read-key-file:./config/captcha-statistics.key}")
    private String readKeyFile = "./config/captcha-statistics.key";
    @Value("${captcha.statistics.read-key:}") private String readKey = "";
    private String serverId, serverName;
    private boolean initialized;
    private final Object lock = new Object();
    private final LinkedHashMap<String, PendingFeedback> outbox = new LinkedHashMap<>();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "captcha-statistics-feedback"); thread.setDaemon(true); return thread;
    });

    @PostConstruct
    public void initialize() {
        synchronized (lock) {
            if (initialized) { return; }
            try {
                JSONObject identity = LocalDataFiles.exists(Paths.get(identityFile))
                        ? JSON.parseObject(LocalDataFiles.readString(Paths.get(identityFile))) : new JSONObject();
                serverId = StringUtils.defaultIfBlank(configuredServerId, identity.getString("serverId"));
                if (StringUtils.isBlank(serverId)) { serverId = "srv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
                if (!serverId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) { throw new IOException("统计服务器编号格式无效"); }
                serverName = StringUtils.defaultIfBlank(configuredServerName, identity.getString("serverName"));
                if (StringUtils.isBlank(serverName)) {
                    try { serverName = InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) { serverName = serverId; }
                }
                serverName = StringUtils.left(serverName.replaceAll("[\\p{Cntrl}]", " ").trim(), 80);
                identity.put("serverId", serverId); identity.put("serverName", serverName);
                LocalDataFiles.write(Paths.get(identityFile), JSON.toJSONBytes(identity));
                if (LocalDataFiles.exists(Paths.get(outboxFile))) {
                    Map<String, PendingFeedback> saved = JSON.parseObject(LocalDataFiles.readString(Paths.get(outboxFile)),
                            new TypeReference<LinkedHashMap<String, PendingFeedback>>() { });
                    if (saved == null) { throw new IOException("统计上报队列格式无效"); }
                    outbox.putAll(saved);
                    for (PendingFeedback pending : outbox.values()) {
                        if (pending.outcome == null) { pending.outcome = "unconfirmed"; pending.reason = "client_restart"; }
                    }
                    persist();
                }
                initialized = true;
            } catch (Exception error) {
                log.error("统一统计初始化失败，保留原队列文件：{}", error.getClass().getSimpleName());
            }
        }
        requestFlush();
    }

    public JSONObject requestContext() {
        if (StringUtils.isBlank(apiUrl)) { return null; }
        initialize();
        if (!initialized) { return null; }
        JSONObject data = new JSONObject(); data.put("version", 1); data.put("serverId", serverId); data.put("serverName", serverName);
        return data;
    }

    /** Persist the receipt before clicking, so a restart can close unresolved attempts honestly. */
    public void trackRecognition(RecognitionResult result, String recognizer, long botId) {
        if (!validTicket(result)) { return; }
        initialize(); if (!initialized) { return; }
        synchronized (lock) {
            if (outbox.containsKey(result.getRecognitionId())) { return; }
            PendingFeedback pending = new PendingFeedback(); pending.recognitionId = result.getRecognitionId();
            pending.feedbackToken = result.getFeedbackToken(); pending.recognizer = recognizer; pending.qq = botId;
            pending.createdAt = System.currentTimeMillis(); pending.apiUrl = apiUrl;
            outbox.put(pending.recognitionId, pending); persistQuietly();
        }
    }

    public void reportOutcome(RecognitionResult result, String outcome, String reason) {
        if (!validTicket(result)) { return; }
        initialize(); if (!initialized) { return; }
        synchronized (lock) {
            PendingFeedback pending = outbox.get(result.getRecognitionId());
            if (pending == null) { return; }
            if (pending.outcome == null) { pending.outcome = outcome; pending.reason = reason; persistQuietly(); }
        }
        requestFlush();
    }

    private boolean validTicket(RecognitionResult result) {
        return result != null && result.getRecognitionId() != null && result.getRecognitionId().matches("[0-9a-f]{32}")
                && StringUtils.length(result.getFeedbackToken()) >= 32;
    }

    private void persist() throws IOException { LocalDataFiles.write(Paths.get(outboxFile), JSON.toJSONBytes(outbox)); }
    private void persistQuietly() {
        try { persist(); } catch (Exception error) { log.error("统一统计上报队列持久化失败，稍后重试：{}", error.getClass().getSimpleName()); }
    }

    private void requestFlush() {
        if (executor.isShutdown() || !initialized || !scheduled.compareAndSet(false, true)) { return; }
        executor.submit(() -> { try { flushFeedback(); } finally { scheduled.set(false); } });
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void flushFeedback() {
        if (!initialized || !flushing.compareAndSet(false, true)) { return; }
        try {
            List<PendingFeedback> ready = new ArrayList<>();
            synchronized (lock) {
                for (PendingFeedback pending : outbox.values()) {
                    if (pending.outcome == null && System.currentTimeMillis() - pending.createdAt > 10 * 60 * 1000L) {
                        pending.outcome = "unconfirmed"; pending.reason = "feedback_timeout";
                    }
                    if (pending.outcome != null && ready.size() < 50) { ready.add(pending); }
                }
                persistQuietly();
            }
            for (PendingFeedback pending : ready) {
                JSONObject body = new JSONObject(); body.put("recognitionId", pending.recognitionId);
                body.put("feedbackToken", pending.feedbackToken); body.put("outcome", pending.outcome); body.put("reason", pending.reason);
                try {
                    Reply reply = request(pending.apiUrl, "statistics/feedback", body, null);
                    if (reply.status >= 200 && reply.status < 300 || Arrays.asList(400,403,404,409,422).contains(reply.status)) {
                        if (reply.status >= 400) { log.warn("统一统计上报已终止 status={}，记录可能已重置或凭据无效", reply.status); }
                        synchronized (lock) { outbox.remove(pending.recognitionId); persistQuietly(); }
                    } else { break; }
                } catch (IOException error) {
                    log.warn("统一统计上报暂未完成，保留队列自动重试：{}", error.getClass().getSimpleName()); break;
                }
            }
        } finally { flushing.set(false); }
    }

    public String queryCommand(String message) {
        String[] tokens = message.trim().split("\\s+"); String path = "statistics/summary";
        if (Arrays.equals(tokens, new String[]{"验证统计", "服务器列表"})) { path = "statistics/servers"; }
        else {
            String server = null, qq = null;
            if (tokens.length == 3 && "服务器".equals(tokens[1])) { server = tokens[2]; }
            else if (tokens.length == 3 && "QQ".equalsIgnoreCase(tokens[1])) { qq = tokens[2]; }
            else if (tokens.length == 5 && "服务器".equals(tokens[1]) && "QQ".equalsIgnoreCase(tokens[3])) { server = tokens[2]; qq = tokens[4]; }
            else if (tokens.length != 1 || !"验证统计".equals(tokens[0])) { return help(); }
            if (server != null && !server.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                    || qq != null && !qq.matches("[0-9]{1,20}")) { return help(); }
            if (server != null || qq != null) {
                path += "?";
                if (server != null) { path += "server_id=" + server; }
                if (qq != null) { path += (server == null ? "" : "&") + "qq=" + qq; }
            }
        }
        try {
            String key = StringUtils.defaultIfBlank(readKey, Files.exists(Paths.get(readKeyFile))
                    ? Files.readString(Paths.get(readKeyFile), StandardCharsets.UTF_8).trim() : "");
                if (StringUtils.isBlank(key)) { return "未配置统一统计查询密钥，请在验证码 API 后台复制密钥并配置到本服务"; }
            Reply reply = request(apiUrl, path, null, key);
            if (reply.status == 200 && reply.body.getString("text") != null) { return reply.body.getString("text"); }
            return "统一统计查询失败：" + StringUtils.defaultIfBlank(reply.body.getString("error"), "HTTP " + reply.status);
        } catch (Exception error) { return "统一统计 API 暂不可用，请稍后重试"; }
    }

    private String help() {
        return "统计命令：\n验证统计\n验证统计 服务器列表\n验证统计 服务器 server-a\n验证统计 QQ 123456789\n验证统计 服务器 server-a QQ 123456789";
    }

    protected Reply request(String base, String path, JSONObject body, String key) throws IOException {
        if (StringUtils.isBlank(base)) { throw new IOException("API address missing"); }
        HttpURLConnection connection = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + "/" + path).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(body == null ? "GET" : "POST");
        if (key != null) { connection.setRequestProperty("X-Statistics-Key", key); }
        try {
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (OutputStream output = connection.getOutputStream()) { output.write(JSON.toJSONBytes(body)); }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            byte[] data;
            try (InputStream stream = input) { data = stream == null ? new byte[0] : stream.readNBytes(1_000_001); }
            if (data.length > 1_000_000) { throw new IOException("Statistics response too large"); }
            JSONObject json;
            try { json = JSON.parseObject(new String(data, StandardCharsets.UTF_8)); }
            catch (Exception error) { throw new IOException("Invalid statistics response", error); }
            return new Reply(status, json == null ? new JSONObject() : json);
        } finally { connection.disconnect(); }
    }

    @PreDestroy public void close() { executor.shutdown(); synchronized (lock) { if (initialized) { persistQuietly(); } } }

    public static class PendingFeedback {
        public String recognitionId, feedbackToken, recognizer, apiUrl, outcome, reason;
        public long qq, createdAt;
    }
    protected static class Reply {
        public final int status; public final JSONObject body;
        public Reply(int status, JSONObject body) { this.status = status; this.body = body; }
    }
}
