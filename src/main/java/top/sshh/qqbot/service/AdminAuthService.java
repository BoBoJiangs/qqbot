package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class AdminAuthService {

    public static final String SESSION_KEY = "ADMIN_AUTHENTICATED";

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;

    private final SecureRandom secureRandom = new SecureRandom();
    private volatile AuthRecord authRecord;

    @PostConstruct
    public void init() {
        loadOrInit();
    }

    public boolean authenticate(String username, String password) {
        if (username == null || password == null) return false;
        AuthRecord record = authRecord;
        if (record == null) return false;
        if (!DEFAULT_USERNAME.equals(username.trim())) return false;
        byte[] salt = Base64.getDecoder().decode(record.saltBase64);
        byte[] expected = Base64.getDecoder().decode(record.hashBase64);
        byte[] actual = pbkdf2(password.toCharArray(), salt, record.iterations, record.keyLengthBits);
        return constantTimeEquals(expected, actual);
    }

    public void changePassword(String oldPassword, String newPassword) {
        if (oldPassword == null || newPassword == null) throw new RuntimeException("密码不能为空");
        if (!authenticate(DEFAULT_USERNAME, oldPassword)) throw new RuntimeException("原密码不正确");
        String trimmed = newPassword.trim();
        if (trimmed.length() < 4) throw new RuntimeException("新密码至少 4 位");
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = pbkdf2(trimmed.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS);
        AuthRecord updated = new AuthRecord(DEFAULT_USERNAME,
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash),
                ITERATIONS,
                KEY_LENGTH_BITS,
                ALGORITHM);
        save(updated);
        this.authRecord = updated;
    }

    public String username() {
        return DEFAULT_USERNAME;
    }

    private void loadOrInit() {
        Path path = authFilePath();
        try {
            if (!Files.exists(path)) {
                initDefault(path);
            }
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            JSONObject obj = JSON.parseObject(content);
            AuthRecord loaded = new AuthRecord(
                    obj.getString("username"),
                    obj.getString("saltBase64"),
                    obj.getString("hashBase64"),
                    obj.getIntValue("iterations"),
                    obj.getIntValue("keyLengthBits"),
                    obj.getString("algorithm")
            );
            if (!DEFAULT_USERNAME.equals(loaded.username)) {
                throw new RuntimeException("auth username must be admin");
            }
            this.authRecord = loaded;
        } catch (Exception e) {
            throw new RuntimeException("加载登录配置失败: " + e.getMessage(), e);
        }
    }

    private void initDefault(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = pbkdf2(DEFAULT_PASSWORD.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS);
        AuthRecord record = new AuthRecord(DEFAULT_USERNAME,
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash),
                ITERATIONS,
                KEY_LENGTH_BITS,
                ALGORITHM);
        save(record);
        this.authRecord = record;
    }

    private void save(AuthRecord record) {
        try {
            Path path = authFilePath();
            Files.createDirectories(path.getParent());
            JSONObject obj = new JSONObject();
            obj.put("username", record.username);
            obj.put("saltBase64", record.saltBase64);
            obj.put("hashBase64", record.hashBase64);
            obj.put("iterations", record.iterations);
            obj.put("keyLengthBits", record.keyLengthBits);
            obj.put("algorithm", record.algorithm);
            Files.write(path,
                    JSON.toJSONString(obj).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException("保存登录配置失败: " + e.getMessage(), e);
        }
    }

    private Path authFilePath() {
        return Paths.get("./config/admin-auth.json");
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLengthBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("密码计算失败: " + e.getMessage(), e);
        }
    }

    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        int diff = a.length ^ b.length;
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            byte x = i < a.length ? a[i] : 0;
            byte y = i < b.length ? b[i] : 0;
            diff |= (x ^ y);
        }
        return diff == 0;
    }

    private static class AuthRecord {
        final String username;
        final String saltBase64;
        final String hashBase64;
        final int iterations;
        final int keyLengthBits;
        final String algorithm;

        private AuthRecord(String username, String saltBase64, String hashBase64, int iterations, int keyLengthBits, String algorithm) {
            this.username = username;
            this.saltBase64 = saltBase64;
            this.hashBase64 = hashBase64;
            this.iterations = iterations;
            this.keyLengthBits = keyLengthBits;
            this.algorithm = algorithm;
        }
    }

}

