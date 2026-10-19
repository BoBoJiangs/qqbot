package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 同群同一收款人只保留一个默认赠送机器人；保存成功后才更新内存。 */
@Component
public class LingShiGiftPreferenceStore {
    private final Path path;
    private Map<String, Preference> defaults = new HashMap<>();
    private Set<String> handledCodes = new LinkedHashSet<>();
    private boolean loaded;

    public LingShiGiftPreferenceStore() {
        this(Paths.get("./config/ling-shi-gift-defaults.json"));
    }

    LingShiGiftPreferenceStore(Path path) {
        this.path = path;
    }

    public synchronized void select(long groupId, long recipientId, long botId, int amount) throws IOException {
        if (amount <= 0) {
            throw new IllegalArgumentException("指定赠送数量必须大于0");
        }
        selectPreference(groupId, recipientId, new Preference(botId, amount, false));
    }

    public synchronized void selectAutoLimit(long groupId, long recipientId, long botId) throws IOException {
        selectPreference(groupId, recipientId, new Preference(botId, 0, true));
    }

    private void selectPreference(long groupId, long recipientId, Preference preference) throws IOException {
        load();
        if (groupId <= 0 || recipientId <= 0 || preference.getBotId() <= 0) {
            throw new IllegalArgumentException("赠送群、收款人和机器人必须大于0");
        }
        Map<String, Preference> updated = new HashMap<>(defaults);
        updated.put(groupId + ":" + recipientId, preference);
        save(updated, handledCodes);
        defaults = updated;
    }

    public synchronized void clear(long groupId, long recipientId) throws IOException {
        load();
        Map<String, Preference> updated = new HashMap<>(defaults);
        updated.remove(groupId + ":" + recipientId);
        save(updated, handledCodes);
        defaults = updated;
    }

    /** 返回各收款人的固定数量（万）；0 表示按接收码中当日剩余可收额度赠送。 */
    public synchronized Map<Long, Integer> recipientsFor(long groupId, long botId) throws IOException {
        load();
        Map<Long, Integer> result = new HashMap<>();
        String prefix = groupId + ":";
        for (Map.Entry<String, Preference> entry : defaults.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue().getBotId() == botId) {
                result.put(Long.parseLong(entry.getKey().substring(prefix.length())), entry.getValue().getAmount());
            }
        }
        return result;
    }

    public synchronized boolean hasHandledCode(String eventKey) throws IOException {
        load();
        return handledCodes.contains(eventKey);
    }

    public synchronized void rememberCode(String eventKey) throws IOException {
        load();
        Set<String> updated = new LinkedHashSet<>(handledCodes);
        updated.add(eventKey);
        while (updated.size() > 512) {
            updated.remove(updated.iterator().next());
        }
        save(defaults, updated);
        handledCodes = updated;
    }

    private void load() throws IOException {
        if (loaded) {
            return;
        }
        if (Files.exists(path)) {
            try {
                JSONObject saved = JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8));
                if (saved == null || saved.getJSONObject("defaults") == null) {
                    throw new IOException("默认赠送配置格式无效");
                }
                Map<String, Preference> restored = new HashMap<>();
                JSONObject bindings = saved.getJSONObject("defaults");
                for (String key : bindings.keySet()) {
                    Preference preference = bindings.getObject(key, Preference.class);
                    String[] ids = key.split(":", -1);
                    if (ids.length != 2 || Long.parseLong(ids[0]) <= 0 || Long.parseLong(ids[1]) <= 0
                            || preference == null || preference.getBotId() <= 0
                            || (preference.isAutoLimit() ? preference.getAmount() != 0 : preference.getAmount() <= 0)) {
                        throw new IOException("默认赠送配置条目无效");
                    }
                    restored.put(key, preference);
                }
                Set<String> restoredCodes = new LinkedHashSet<>();
                if (saved.getJSONArray("handledCodes") != null) {
                    restoredCodes.addAll(saved.getJSONArray("handledCodes").toJavaList(String.class));
                }
                defaults = restored;
                handledCodes = restoredCodes;
            } catch (RuntimeException e) {
                throw new IOException("读取默认赠送配置失败", e);
            }
        }
        loaded = true;
    }

    private void save(Map<String, Preference> bindings, Set<String> codes) throws IOException {
        JSONObject saved = new JSONObject();
        saved.put("defaults", bindings);
        saved.put("handledCodes", codes);
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "ling-shi-gifts-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJSONString(saved), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static final class Preference {
        private long botId;
        private int amount;
        private boolean autoLimit;

        public Preference() {
        }

        private Preference(long botId, int amount, boolean autoLimit) {
            this.botId = botId;
            this.amount = amount;
            this.autoLimit = autoLimit;
        }

        public long getBotId() {
            return botId;
        }

        public void setBotId(long botId) {
            this.botId = botId;
        }

        public int getAmount() {
            return amount;
        }

        public void setAmount(int amount) {
            this.amount = amount;
        }

        public boolean isAutoLimit() {
            return autoLimit;
        }

        public void setAutoLimit(boolean autoLimit) {
            this.autoLimit = autoLimit;
        }
    }
}
