package top.sshh.qqbot.service;

import top.sshh.qqbot.service.storage.LocalDataFiles;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 收款人按群保存；累计及回执去重按机器人QQ和群号保存，与旧模式配置独立。 */
@Component
public class LingShiGiftStore {
    private final Path path;
    private Map<String, Long> recipients = new HashMap<>();
    private Map<String, Long> totals = new HashMap<>();
    private Set<String> codes = new LinkedHashSet<>();
    private Set<String> receipts = new LinkedHashSet<>();
    private boolean loaded;
    private boolean legacyFormat;

    public LingShiGiftStore() {
        this(Paths.get("./config/ling-shi-gifts.json"));
    }

    LingShiGiftStore(Path path) {
        this.path = path;
    }

    /** 接收指令层从万换算后的初始累计（枚）；null 只关闭本号在该群的统计。 */
    public synchronized void configure(long groupId, long recipientId, long botId, Long initialTotal)
            throws IOException {
        load();
        if (groupId <= 0 || recipientId <= 0 || botId <= 0 || initialTotal != null && initialTotal < 0) {
            throw new IllegalArgumentException("QQ、群号必须大于0，累计初始值不能小于0");
        }
        Map<String, Long> nextRecipients = new HashMap<>(recipients);
        Map<String, Long> nextTotals = new HashMap<>(totals);
        nextRecipients.put(groupId + ":" + recipientId, botId);
        String statisticsKey = statisticsKey(botId, groupId);
        if (initialTotal == null) {
            nextTotals.remove(statisticsKey);
        } else {
            nextTotals.put(statisticsKey, initialTotal);
        }
        save(nextRecipients, nextTotals, codes, receipts);
        recipients = nextRecipients;
        totals = nextTotals;
    }

    public synchronized Map<Long, Long> recipientsFor(long groupId) throws IOException {
        load();
        Map<Long, Long> result = new HashMap<>();
        String prefix = groupId + ":";
        for (Map.Entry<String, Long> entry : recipients.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(Long.parseLong(entry.getKey().substring(prefix.length())), entry.getValue());
            }
        }
        return result;
    }

    public synchronized Long totalFor(long botId, long groupId) throws IOException {
        load();
        return totals.get(statisticsKey(botId, groupId));
    }

    public synchronized Map<Long, List<Long>> recipientsByGroupFor(long botId) throws IOException {
        load();
        Map<Long, List<Long>> result = new TreeMap<>();
        for (Map.Entry<String, Long> entry : recipients.entrySet()) {
            if (entry.getValue() == botId) {
                String[] ids = entry.getKey().split(":");
                result.computeIfAbsent(Long.parseLong(ids[0]), ignored -> new ArrayList<>())
                        .add(Long.parseLong(ids[1]));
            }
        }
        // 即使专属QQ已切换给其他号，本号仍可独立统计和查看该群累计。
        String prefix = botId + ":";
        for (String key : totals.keySet()) {
            if (key.startsWith(prefix)) {
                result.computeIfAbsent(Long.parseLong(key.substring(prefix.length())), ignored -> new ArrayList<>());
            }
        }
        result.values().forEach(recipients -> recipients.sort(Long::compareTo));
        return result;
    }

    public synchronized boolean hasClaimedCode(String key) throws IOException {
        load();
        return codes.contains(key);
    }

    /** 发送前落盘，同一条接收码消息只由一个机器人发送一次。 */
    public synchronized boolean claimCode(String key) throws IOException {
        load();
        if (codes.contains(key)) {
            return false;
        }
        Set<String> next = append(codes, key);
        save(recipients, totals, next, receipts);
        codes = next;
        return true;
    }

    /** 不识别赠送人；各机器人在各群独立累计、独立去重，同一事件每份累计只记一次。 */
    public synchronized Long recordReceipt(long botId, long groupId, String eventKey, long amount) throws IOException {
        load();
        String statisticsKey = statisticsKey(botId, groupId);
        String receiptKey = statisticsKey + ":" + eventKey;
        if (amount <= 0 || !totals.containsKey(statisticsKey) || receipts.contains(receiptKey)) {
            return null;
        }
        long total = Math.addExact(totals.get(statisticsKey), amount);
        Map<String, Long> nextTotals = new HashMap<>(totals);
        nextTotals.put(statisticsKey, total);
        Set<String> nextReceipts = new LinkedHashSet<>(receipts);
        nextReceipts.add(receiptKey);
        String prefix = statisticsKey + ":";
        long count = nextReceipts.stream().filter(key -> key.startsWith(prefix)).count();
        java.util.Iterator<String> history = nextReceipts.iterator();
        while (count > 2048 && history.hasNext()) {
            if (history.next().startsWith(prefix)) {
                history.remove();
                count--;
            }
        }
        save(recipients, nextTotals, codes, nextReceipts);
        totals = nextTotals;
        receipts = nextReceipts;
        return total;
    }

    private static String statisticsKey(long botId, long groupId) {
        return botId + ":" + groupId;
    }

    private static Set<String> append(Set<String> history, String key) {
        Set<String> next = new LinkedHashSet<>(history);
        next.add(key);
        while (next.size() > 2048) {
            next.remove(next.iterator().next());
        }
        return next;
    }

    private void load() throws IOException {
        if (loaded) {
            return;
        }
        if (LocalDataFiles.exists(path)) {
            try {
                JSONObject saved = JSON.parseObject(LocalDataFiles.readString(path, StandardCharsets.UTF_8));
                if (saved == null || saved.getJSONObject("recipients") == null
                        || saved.getJSONObject("totals") == null) {
                    throw new IOException("新模式灵石赠送配置格式无效");
                }
                Integer version = saved.getInteger("version");
                boolean legacy = version == null || version == 1;
                if (!legacy && version != 2) {
                    throw new IOException("不支持的灵石赠送配置版本");
                }
                Map<String, Long> loadedRecipients = new HashMap<>();
                JSONObject bindings = saved.getJSONObject("recipients");
                for (String key : bindings.keySet()) {
                    String[] ids = key.split(":", -1);
                    Long botId = bindings.getLong(key);
                    if (ids.length != 2 || Long.parseLong(ids[0]) <= 0 || Long.parseLong(ids[1]) <= 0
                            || botId == null || botId <= 0) {
                        throw new IOException("新模式灵石赠送配置条目无效");
                    }
                    loadedRecipients.put(key, botId);
                }
                Map<String, Long> loadedTotals = new HashMap<>();
                JSONObject savedTotals = saved.getJSONObject("totals");
                for (String key : savedTotals.keySet()) {
                    Long total = savedTotals.getLong(key);
                    String[] ids = key.split(":", -1);
                    if (ids.length != (legacy ? 1 : 2) || Long.parseLong(ids[0]) <= 0
                            || !legacy && Long.parseLong(ids[1]) <= 0 || total == null || total < 0) {
                        throw new IOException("灵石群累计配置无效");
                    }
                    if (!legacy) {
                        loadedTotals.put(key, total);
                        continue;
                    }
                    // 旧文件只记录共享群累计：为该群现有绑定的各机器人继承一份，保留原数值。
                    String groupPrefix = key + ":";
                    boolean assigned = false;
                    for (Map.Entry<String, Long> binding : loadedRecipients.entrySet()) {
                        if (binding.getKey().startsWith(groupPrefix)) {
                            loadedTotals.put(statisticsKey(binding.getValue(), Long.parseLong(key)), total);
                            assigned = true;
                        }
                    }
                    if (!assigned) {
                        throw new IOException("旧版群累计缺少机器人绑定，无法迁移");
                    }
                }
                Set<String> loadedCodes = new LinkedHashSet<>();
                Set<String> loadedReceipts = new LinkedHashSet<>();
                if (saved.getJSONArray("codes") != null) {
                    loadedCodes.addAll(saved.getJSONArray("codes").toJavaList(String.class));
                }
                if (saved.getJSONArray("receipts") != null) {
                    for (String event : saved.getJSONArray("receipts").toJavaList(String.class)) {
                        if (event == null || event.isEmpty()) {
                            throw new IOException("灵石赠送回执去重记录无效");
                        }
                        if (!legacy) {
                            loadedReceipts.add(event);
                            continue;
                        }
                        String groupId = event.split(":", 2)[0];
                        for (Map.Entry<String, Long> binding : loadedRecipients.entrySet()) {
                            if (binding.getKey().startsWith(groupId + ":")) {
                                loadedReceipts.add(statisticsKey(binding.getValue(), Long.parseLong(groupId))
                                        + ":" + event);
                            }
                        }
                    }
                }
                recipients = loadedRecipients;
                totals = loadedTotals;
                codes = loadedCodes;
                receipts = loadedReceipts;
                legacyFormat = legacy;
            } catch (RuntimeException e) {
                throw new IOException("读取新模式灵石赠送配置失败", e);
            }
        }
        loaded = true;
    }

    private void save(Map<String, Long> bindings, Map<String, Long> groupTotals,
            Set<String> codeHistory, Set<String> receiptHistory) throws IOException {
        JSONObject saved = new JSONObject();
        saved.put("version", 2);
        saved.put("recipients", bindings);
        saved.put("totals", groupTotals);
        saved.put("codes", codeHistory);
        saved.put("receipts", receiptHistory);
        LocalDataFiles.writeString(path, JSON.toJSONString(saved), StandardCharsets.UTF_8);
        legacyFormat = false;
    }
}
