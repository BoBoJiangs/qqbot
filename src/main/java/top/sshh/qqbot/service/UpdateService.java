package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.sshh.qqbot.config.UpdateProperties;
import top.sshh.qqbot.data.UpdateManifest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.PostConstruct;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;

@Service
public class UpdateService {
    private static final Logger logger = LoggerFactory.getLogger(UpdateService.class);

    private final ReentrantLock lock = new ReentrantLock();

    @Autowired
    private UpdateProperties updateProperties;

    @Autowired
    private MemoryCleaner memoryCleaner;

    @Autowired(required = false)
    private BuildProperties buildProperties;

    private volatile Instant lastCheckAt;
    private volatile String lastError;
    private volatile UpdateManifest lastManifest;
    private static final String NOTICE_FILE_NAME = "last_update_notice.json";
    private static final String APPLIED_VERSION_FILE_NAME = "applied_version.txt";

    public String getCurrentVersionText() {
        return getCurrentVersion();
    }

    public boolean hasUpdate(UpdateManifest manifest) {
        if (manifest == null) return false;
        boolean force = manifest.getForce() != null && manifest.getForce();
        return force || isUpdateAvailable(getCurrentVersion(), manifest.getVersion());
    }

    public String buildCheckUpdateMessage() throws Exception {
        UpdateManifest manifest = checkForUpdate();
        String current = getCurrentVersion();
        boolean available = hasUpdate(manifest);
        StringBuilder sb = new StringBuilder();
        if (available) {
            sb.append("检测到新版本\n");
            sb.append("当前版本：").append(StringUtils.defaultString(current, "—")).append("\n");
            sb.append("最新版本：").append(StringUtils.defaultString(manifest == null ? null : manifest.getVersion(), "—")).append("\n");
            if (StringUtils.isNotBlank(manifest == null ? null : manifest.getNotes())) {
                sb.append("\n更新日志：\n").append(manifest.getNotes());
            } else {
                sb.append("\n更新日志：暂无");
            }
            sb.append("\n如需更新，请发送“立即更新”");
        } else {
            sb.append("当前已是最新版本\n");
            sb.append("当前版本：").append(StringUtils.defaultString(current, "—"));
        }
        return sb.toString();
    }

    public String buildLatestChangelogMessage() throws Exception {
        UpdateManifest manifest = checkForUpdate();
        StringBuilder sb = new StringBuilder();
        sb.append("版本更新日志\n");
        sb.append("当前版本：").append(StringUtils.defaultString(getCurrentVersion(), "—")).append("\n");
        sb.append("最新版本：").append(StringUtils.defaultString(manifest == null ? null : manifest.getVersion(), "—")).append("\n");
        if (StringUtils.isNotBlank(manifest == null ? null : manifest.getNotes())) {
            sb.append("\n").append(manifest.getNotes());
        } else {
            sb.append("\n暂无更新日志");
        }
        return sb.toString();
    }

    public Map<String, Object> getState() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", updateProperties.isEnabled());
        out.put("baseUrl", updateProperties.getBaseUrl());
        out.put("manifestUrl", updateProperties.getManifestUrl());
        out.put("autoApply", updateProperties.isAutoApply());
        out.put("autoRestart", updateProperties.isAutoRestart());
        out.put("checkIntervalMs", updateProperties.getCheckIntervalMs());
        out.put("initialDelayMs", updateProperties.getInitialDelayMs());
        out.put("lastCheckAt", lastCheckAt == null ? null : lastCheckAt.toEpochMilli());
        out.put("lastError", lastError);
        out.put("currentVersion", getCurrentVersion());
        if (lastManifest != null) {
            out.put("latestVersion", lastManifest.getVersion());
            out.put("latest", lastManifest);
            out.put("updateAvailable", isUpdateAvailable(getCurrentVersion(), lastManifest.getVersion()));
        } else {
            out.put("latestVersion", null);
            out.put("latest", null);
            out.put("updateAvailable", null);
        }
        return out;
    }

    @PostConstruct
    public void postStartupSendPendingNotice() {
        new Thread(() -> {
            try {
                syncAppliedVersionFromPendingNotice();
            } catch (Exception ignored) {
            }
            for (int i = 0; i < 30; i++) {
                try {
                    if (trySendPendingNoticeOnce()) return;
                } catch (Exception ignored) {
                }
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "update-notice-on-start").start();
    }

    @PostConstruct
    public void postStartupAutoCheckOnce() {
        if (!updateProperties.isEnabled() || !updateProperties.isAutoApply()) {
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(15000L);
                scheduledAutoUpdate();
            } catch (InterruptedException ignored) {
            }
        }, "update-check-on-start").start();
    }

    public UpdateManifest checkForUpdate() throws Exception {
        if (!updateProperties.isEnabled()) {
            throw new IllegalStateException("更新功能未启用");
        }
        lock.lock();
        try {
            UpdateManifest manifest = fetchManifest();
            this.lastManifest = manifest;
            this.lastCheckAt = Instant.now();
            this.lastError = null;
            return manifest;
        } catch (Exception e) {
            this.lastCheckAt = Instant.now();
            this.lastError = e.getMessage();
            throw e;
        } finally {
            lock.unlock();
        }
    }

    public Path downloadLatest(UpdateManifest manifest) throws Exception {
        if (manifest == null || StringUtils.isBlank(manifest.getVersion())) {
            throw new IllegalArgumentException("manifest无效");
        }
        String jarUrl = StringUtils.isNotBlank(manifest.getJarUrl()) ? manifest.getJarUrl() : normalizeBaseUrl(updateProperties.getBaseUrl()) + "bot.jar";
        if (!jarUrl.toLowerCase(Locale.ROOT).startsWith("http://") && !jarUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            jarUrl = normalizeBaseUrl(updateProperties.getBaseUrl()) + stripLeadingSlash(jarUrl);
        }

        Path workDir = Paths.get(System.getProperty("user.dir"));
        Path tmpDir = workDir.resolve("update");
        Files.createDirectories(tmpDir);

        Path tmpJar = tmpDir.resolve("bot.jar.download");
        Path targetNew = workDir.resolve("bot.jar.new");

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(updateProperties.getConnectTimeoutMs())
                .setSocketTimeout(updateProperties.getReadTimeoutMs())
                .build();

        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(requestConfig).build()) {
            HttpGet get = new HttpGet(jarUrl);
            try (CloseableHttpResponse res = client.execute(get)) {
                int status = res.getStatusLine().getStatusCode();
                if (status < 200 || status >= 300) {
                    String body = safeBody(res.getEntity());
                    throw new IllegalStateException("下载失败，HTTP " + status + (body == null ? "" : (": " + body)));
                }
                HttpEntity entity = res.getEntity();
                if (entity == null) {
                    throw new IllegalStateException("下载失败：响应为空");
                }
                try (InputStream in = entity.getContent()) {
                    Files.copy(in, tmpJar, StandardCopyOption.REPLACE_EXISTING);
                }
                EntityUtils.consumeQuietly(entity);
            }
        }

        if (StringUtils.isNotBlank(manifest.getSha256())) {
            String actual = sha256Hex(tmpJar);
            if (!actual.equalsIgnoreCase(manifest.getSha256().trim())) {
                throw new IllegalStateException("SHA256校验失败，期望=" + manifest.getSha256() + " 实际=" + actual);
            }
        }

        Files.move(tmpJar, targetNew, StandardCopyOption.REPLACE_EXISTING);
        return targetNew;
    }

    public void applyUpdateAndRestart(UpdateManifest manifest) throws Exception {
        applyUpdateInternal(manifest, updateProperties.isAutoRestart());
    }

    public void applyUpdateAndRestartNow(UpdateManifest manifest) throws Exception {
        applyUpdateInternal(manifest, true);
    }

    private void applyUpdateInternal(UpdateManifest manifest, boolean restart) throws Exception {
        lock.lock();
        try {
            Path newJar = downloadLatest(manifest);
            logger.info("更新包已下载：{}", newJar.toAbsolutePath());
            String buildVersion = getBuildVersion();
            savePendingNotice(buildVersion, manifest);
            trySendNoticeNow(buildVersion, manifest);
            tryPromoteDownloadedJar(newJar, manifest == null ? null : manifest.getVersion());
            if (restart) {
                new Thread(() -> {
                    try {
                        Thread.sleep(1500);
                        memoryCleaner.restartByScript();
                    } catch (Exception e) {
                        logger.error("触发重启失败", e);
                    }
                }, "update-restart").start();
            }
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(
            fixedDelayString = "${update.checkIntervalMs:3600000}",
            initialDelayString = "${update.initialDelayMs:60000}"
    )
    public void scheduledAutoUpdate() {
        if (!updateProperties.isEnabled() || !updateProperties.isAutoApply()) {
            return;
        }
        try {
            UpdateManifest manifest = checkForUpdate();
            String current = getCurrentVersion();
            boolean force = manifest.getForce() != null && manifest.getForce();
            if (force || isUpdateAvailable(current, manifest.getVersion())) {
                logger.info("检测到新版本：当前={} 远端={}", current, manifest.getVersion());
                applyUpdateAndRestart(manifest);
            }
        } catch (Exception e) {
            logger.warn("自动更新检查失败：{}", e.getMessage());
        }
    }

    private UpdateManifest fetchManifest() throws Exception {
        String manifestUrl = updateProperties.getManifestUrl();
        if (StringUtils.isNotBlank(manifestUrl)) {
            return fetchManifestFromUrl(manifestUrl);
        }

        String base = normalizeBaseUrl(updateProperties.getBaseUrl());
        String url = base + "update.json";
        try {
            return fetchManifestFromUrl(url);
        } catch (Exception e) {
            throw new IllegalStateException("拉取更新信息失败: " + url + "，原因: " + e.getMessage(), e);
        }
    }

    private UpdateManifest fetchManifestFromUrl(String manifestUrl) throws Exception {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(updateProperties.getConnectTimeoutMs())
                .setSocketTimeout(updateProperties.getReadTimeoutMs())
                .build();

        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(requestConfig).build()) {
            HttpGet get = new HttpGet(manifestUrl);
            try (CloseableHttpResponse res = client.execute(get)) {
                int status = res.getStatusLine().getStatusCode();
                String body = safeBody(res.getEntity());
                if (status < 200 || status >= 300) {
                    if (status == 403) {
                        throw new IllegalStateException("拉取manifest被拒绝(403)：可能服务端做了IP白名单/鉴权限制，请放行下载服务器出口IP或取消限制" + (body == null ? "" : (": " + body)));
                    }
                    throw new IllegalStateException("拉取manifest失败，HTTP " + status + (body == null ? "" : (": " + body)));
                }
                if (StringUtils.isBlank(body)) {
                    throw new IllegalStateException("manifest为空");
                }
                UpdateManifest manifest = JSON.parseObject(body, UpdateManifest.class);
                if (manifest == null || StringUtils.isBlank(manifest.getVersion())) {
                    throw new IllegalStateException("manifest缺少version字段");
                }
                String base = normalizeBaseUrl(updateProperties.getBaseUrl());
                if (StringUtils.isBlank(manifest.getJarUrl())) {
                    manifest.setJarUrl(base + "bot.jar");
                } else {
                    String jarUrl = manifest.getJarUrl().trim();
                    String lower = jarUrl.toLowerCase(Locale.ROOT);
                    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                        manifest.setJarUrl(base + stripLeadingSlash(jarUrl));
                    }
                }
                return manifest;
            }
        }
    }

    private String safeBody(HttpEntity entity) {
        if (entity == null) return null;
        try {
            return EntityUtils.toString(entity, "UTF-8");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getCurrentVersion() {
        String applied = readAppliedVersion();
        String build = getBuildVersion();
        if (StringUtils.isNotBlank(applied) && compareVersion(applied, build) > 0) return applied;
        return build;
    }

    private String getBuildVersion() {
        try {
            if (buildProperties != null && StringUtils.isNotBlank(buildProperties.getVersion())) {
                return buildProperties.getVersion();
            }
        } catch (Exception ignored) {
        }
        try {
            String v = UpdateService.class.getPackage().getImplementationVersion();
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        } catch (Exception ignored) {
        }
        return "1.0.0";
    }

    private boolean isUpdateAvailable(String current, String remote) {
        if (StringUtils.isBlank(remote) || StringUtils.isBlank(current)) return false;
        return compareVersion(remote, current) > 0;
    }

    private int compareVersion(String a, String b) {
        String[] aa = a.trim().split("\\.");
        String[] bb = b.trim().split("\\.");
        int len = Math.max(aa.length, bb.length);
        for (int i = 0; i < len; i++) {
            String as = i < aa.length ? aa[i] : "0";
            String bs = i < bb.length ? bb[i] : "0";
            Integer ai = tryParseInt(as);
            Integer bi = tryParseInt(bs);
            int cmp;
            if (ai != null && bi != null) {
                cmp = Integer.compare(ai, bi);
            } else {
                cmp = as.compareTo(bs);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "";
        String s = baseUrl.trim();
        if (!s.endsWith("/")) s = s + "/";
        return s;
    }

    private String stripLeadingSlash(String s) {
        if (s == null) return null;
        String out = s;
        while (out.startsWith("/")) out = out.substring(1);
        return out;
    }

    private void savePendingNotice(String currentVersion, UpdateManifest manifest) {
        try {
            Path dir = Paths.get(System.getProperty("user.dir")).resolve("update");
            Files.createDirectories(dir);
            Path file = dir.resolve(NOTICE_FILE_NAME);
            Map<String, Object> payload = new HashMap<>();
            payload.put("time", Instant.now().toEpochMilli());
            payload.put("currentVersion", currentVersion);
            payload.put("targetVersion", manifest == null ? null : manifest.getVersion());
            payload.put("notes", manifest == null ? null : manifest.getNotes());
            payload.put("jarUrl", manifest == null ? null : manifest.getJarUrl());
            payload.put("sha256", manifest == null ? null : manifest.getSha256());
            payload.put("force", manifest == null ? null : manifest.getForce());
            payload.put("sent", false);
            String json = JSON.toJSONString(payload);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warn("保存更新通知失败：{}", e.getMessage());
        }
    }

    private void syncAppliedVersionFromPendingNotice() {
        try {
            Path workDir = Paths.get(System.getProperty("user.dir"));
            if (Files.exists(workDir.resolve("bot.jar.new"))) return;
            Path file = workDir.resolve("update").resolve(NOTICE_FILE_NAME);
            if (!Files.exists(file)) return;
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(json)) return;
            Map<String, Object> payload = JSON.parseObject(json, Map.class);
            if (payload == null) return;
            Object target = payload.get("targetVersion");
            if (target == null) return;
            String targetVersion = String.valueOf(target).trim();
            if (StringUtils.isBlank(targetVersion)) return;
            String buildVersion = getBuildVersion();
            if (StringUtils.isNotBlank(buildVersion) && compareVersion(buildVersion, targetVersion) >= 0) {
                writeAppliedVersion(targetVersion);
                return;
            }

            Long noticeTime = null;
            try {
                Object t = payload.get("time");
                if (t instanceof Number) noticeTime = ((Number) t).longValue();
                else if (t != null) noticeTime = Long.parseLong(String.valueOf(t));
            } catch (Exception ignored) {
            }

            Path currentJar = workDir.resolve("bot.jar");
            if (noticeTime == null || !Files.exists(currentJar)) {
                maybeClearWrongAppliedVersion(workDir, targetVersion);
                return;
            }

            long jarMtime = Files.getLastModifiedTime(currentJar).toMillis();
            if (jarMtime + 1000L >= noticeTime) {
                writeAppliedVersion(targetVersion);
            } else {
                maybeClearWrongAppliedVersion(workDir, targetVersion);
            }
        } catch (Exception ignored) {
        }
    }

    private void maybeClearWrongAppliedVersion(Path workDir, String targetVersion) {
        try {
            if (workDir == null || StringUtils.isBlank(targetVersion)) return;
            String applied = readAppliedVersion();
            if (StringUtils.isBlank(applied)) return;
            if (compareVersion(applied, targetVersion) >= 0) {
                Path file = workDir.resolve("update").resolve(APPLIED_VERSION_FILE_NAME);
                Files.deleteIfExists(file);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean trySendPendingNoticeOnce() {
        try {
            Path file = Paths.get(System.getProperty("user.dir")).resolve("update").resolve(NOTICE_FILE_NAME);
            if (!Files.exists(file)) return false;
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(json)) return false;
            Map<String, Object> payload = JSON.parseObject(json, Map.class);
            if (payload == null) return false;
            Object sent = payload.get("sent");
            if (Boolean.TRUE.equals(sent)) return true;

            String target = payload.get("targetVersion") == null ? null : String.valueOf(payload.get("targetVersion"));
            String notes = payload.get("notes") == null ? null : String.valueOf(payload.get("notes"));
            String jarUrl = payload.get("jarUrl") == null ? null : String.valueOf(payload.get("jarUrl"));
            Long time = null;
            try {
                Object t = payload.get("time");
                if (t instanceof Number) time = ((Number) t).longValue();
                else if (t != null) time = Long.parseLong(String.valueOf(t));
            } catch (Exception ignored) {
            }

            String buildVersion = getBuildVersion();
            String appliedVersion = readAppliedVersion();
            String message = buildUpdateMessage(buildVersion, appliedVersion, target, notes, jarUrl, time, true);
            boolean ok = sendToAllMasters(message);
            if (ok) {
                payload.put("sent", true);
                Files.write(file, JSON.toJSONString(payload).getBytes(StandardCharsets.UTF_8));
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void trySendNoticeNow(String currentVersion, UpdateManifest manifest) {
        try {
            String message = buildUpdateMessage(currentVersion, null, manifest == null ? null : manifest.getVersion(),
                    manifest == null ? null : manifest.getNotes(),
                    manifest == null ? null : manifest.getJarUrl(),
                    Instant.now().toEpochMilli(),
                    false);
            sendToAllMasters(message);
        } catch (Exception ignored) {
        }
    }

    private String buildUpdateMessage(String buildVersion, String appliedVersion, String target, String notes, String jarUrl, Long timeMs, boolean afterRestart) {
        String when = "—";
        if (timeMs != null) {
            try {
                when = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception ignored) {
            }
        }
        StringBuilder sb = new StringBuilder();
        if (afterRestart) sb.append("✅ 版本更新完成（已重启）\n");
        else sb.append("✅ 检测到版本更新，准备重启\n");
        String displayCurrent = StringUtils.isNotBlank(appliedVersion) ? appliedVersion : buildVersion;
        if (StringUtils.isNotBlank(displayCurrent) || StringUtils.isNotBlank(target)) {
            sb.append("当前版本：").append(StringUtils.defaultString(displayCurrent, "—")).append("\n");
            if (StringUtils.isNotBlank(appliedVersion) && StringUtils.isNotBlank(buildVersion) && !appliedVersion.equals(buildVersion)) {
                sb.append("程序版本：").append(buildVersion).append("\n");
            }
            sb.append("目标版本：").append(StringUtils.defaultString(target, "—")).append("\n");
        }
        sb.append("时间：").append(when).append("\n");
        // if (StringUtils.isNotBlank(jarUrl)) sb.append("下载地址：").append(jarUrl).append("\n");
        if (StringUtils.isNotBlank(notes)) sb.append("\n更新说明：\n").append(notes);
        return sb.toString();
    }

    private boolean sendToAllMasters(String message) {
        Map<Long, Bot> bots = BotFactory.getBots();
        if (bots == null || bots.isEmpty()) return false;
        Set<Long> masters = new HashSet<>();
        for (Bot b : bots.values()) {
            try {
                long master = b.getBotConfig().getMasterQQ();
                if (master > 0) masters.add(master);
            } catch (Exception ignored) {
            }
        }
        boolean any = false;
        for (Long masterId : masters) {
            if (masterId == null) continue;
            if (sendToMaster(masterId, message, null)) any = true;
        }
        return any;
    }

    private boolean sendToMaster(long masterId, String message, Long excludeBotId) {
        Map<Long, Bot> bots = BotFactory.getBots();
        if (bots == null || bots.isEmpty()) return false;
        for (Bot bot2 : bots.values()) {
            try {
                long botId = bot2.getBotId();
                if (excludeBotId != null && botId == excludeBotId) continue;
                if (bot2.getBotConfig().getMasterQQ() == masterId && masterId != 1277499726L) {
                    bot2.sendPrivateMessage(masterId, (new MessageChain()).text(message));
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void tryPromoteDownloadedJar(Path newJar, String targetVersion) {
        if (newJar == null) return;
        try {
            Path workDir = Paths.get(System.getProperty("user.dir"));
            Path currentJar = workDir.resolve("bot.jar");
            if (!Files.exists(newJar)) return;

            cleanupOldBackupJars(workDir);
            Path backupJar = workDir.resolve("bot.jar.bak");

            if (Files.exists(currentJar)) {
                Files.move(currentJar, backupJar, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                Files.move(newJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception moveNewFailed) {
                if (Files.exists(backupJar) && !Files.exists(currentJar)) {
                    try {
                        Files.move(backupJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ignored) {
                    }
                }
                throw moveNewFailed;
            }

            if (StringUtils.isNotBlank(targetVersion)) {
                writeAppliedVersion(targetVersion);
            }
        } catch (Exception e) {
            logger.warn("更新文件替换失败（bot.jar.new 未能生效）：{}", e.getMessage());
        }
    }

    private void cleanupOldBackupJars(Path workDir) {
        if (workDir == null) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workDir, "bot.jar.bak.*")) {
            for (Path p : stream) {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String readAppliedVersion() {
        try {
            Path file = Paths.get(System.getProperty("user.dir")).resolve("update").resolve(APPLIED_VERSION_FILE_NAME);
            if (!Files.exists(file)) return null;
            String v = Files.readString(file, StandardCharsets.UTF_8);
            if (v == null) return null;
            v = v.trim();
            return StringUtils.isBlank(v) ? null : v;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeAppliedVersion(String version) {
        try {
            if (StringUtils.isBlank(version)) return;
            Path dir = Paths.get(System.getProperty("user.dir")).resolve("update");
            Files.createDirectories(dir);
            Path file = dir.resolve(APPLIED_VERSION_FILE_NAME);
            Files.write(file, version.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
