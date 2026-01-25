package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BuyGoodsReminder {
    private static final Pattern YEMA = Pattern.compile("(查看坊市|坊市查看)\\s*([^\\s@]+)");
    private static final Pattern PAGE_KEY = Pattern.compile("(技能|装备|丹药|药材|道具)\\s*\\d+");
    private static final long ITEM_REMIND_INTERVAL_MS = 10 * 60 * 1000L;
    private static final String[] REMIND_TEMPLATES = new String[]{
            "坊市中出现稀有物品需要的道友快去抢夺机缘！！！",
            "天降机缘！坊市刷出稀有物品，错过再等十年！",
            "道友注意！坊市惊现珍稀物品，手慢无！",
            "机缘已至！坊市挂出稀有宝物，速速前往！",
            "快报！坊市出现罕见物品，速去一探究竟！"
    };

    private final ExecutorService customPool = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<Long, String> groupPageMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastItemRemindTimeMap = new ConcurrentHashMap<>();

    @Autowired
    public GroupManager groupManager;

    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.ONLY_ITSELF)
    public void 设置购买物品提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        message = message.trim();
        if (!message.startsWith("设置购买提醒物品") && !message.startsWith("设置购买物品提醒")) {
            return;
        }


        String[] lines = message.split("\n");
        Set<String> items = ConcurrentHashMap.newKeySet();
        for (int i = 1; i < lines.length; i++) {
            String item = extractItemName(lines[i].trim());
            if (StringUtils.isNotBlank(item)) {
                items.add(item);
            }
        }

        groupManager.setBuyRemindItems(bot.getBotId(), items);
        if (items.isEmpty()) {
            group.sendMessage(new MessageChain().reply(messageId).text("已清空购买提醒物品"));
        } else {
            group.sendMessage(new MessageChain().reply(messageId).text("购买提醒物品已设置：\n" + StringUtils.join(items, "\n")));
        }
    }

    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.ONLY_ITSELF)
    public void 查询购买提醒物品(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        message = message.trim();
        if (!"查询购买提醒物品".equals(message) && !"查询购买物品提醒".equals(message)) {
            return;
        }
        Set<String> items = groupManager.getBuyRemindItems(bot.getBotId());
        if (items == null || items.isEmpty()) {
            group.sendMessage(new MessageChain().reply(messageId).text("无购买提醒物品"));
            return;
        }
        group.sendMessage(new MessageChain().reply(messageId).text(StringUtils.join(items, "\n")));
    }

    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.ONLY_ITSELF)
    public void 开启关闭本群购买提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        message = message.trim();
        if ("开启本群购买提醒".equals(message)) {
            groupManager.setBuyRemindGroupEnabled(group.getGroupId(), true);
            group.sendMessage(new MessageChain().reply(messageId).text("本群购买提醒已开启"));
        } else if ("关闭本群购买提醒".equals(message)) {
            groupManager.setBuyRemindGroupEnabled(group.getGroupId(), false);
            group.sendMessage(new MessageChain().reply(messageId).text("本群购买提醒已关闭"));
        }
    }

    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.NOT_IGNORE)
    public void 记录商店页码(Bot bot, Group group, String message, Integer messageId) {
        long groupId = group.getGroupId();
        if ((message.contains("@小小") || message.contains("@3889001741")) && (message.contains("查看坊市") || message.contains("坊市查看"))) {
            String pageKey = extractPageKey(message);
            if (pageKey != null && (pageKey.contains("技能") || pageKey.contains("装备") || pageKey.contains("丹药") || pageKey.contains("药材") || pageKey.contains("道具"))) {
                this.groupPageMap.put(groupId, pageKey);
            }
        }
    }

    @GroupMessageHandler(senderIds = {3889001741L})
    public void 坊市购买提醒(Bot bot, Group group, String message, Integer messageId) {
        if (!message.contains("不鼓励不保障任何第三方交易行为") || message.contains("下架")) {
            return;
        }

        Set<String> remindItems = groupManager.getBuyRemindItems(bot.getBotId());
        if (remindItems.isEmpty()) {
            return;
        }

        if (groupManager.buyRemindGroupEnabledMap.values().stream().noneMatch(Boolean::booleanValue)) {
            return;
        }

        customPool.submit(() -> processMarketMessage(bot, group, message, remindItems));
    }

    private void processMarketMessage(Bot bot, Group group, String message, Set<String> remindItems) {
        long now = System.currentTimeMillis();
        String pageKey = resolvePageKey(group.getGroupId(), message);

        Map<String, MarketHit> hits = new HashMap<>();
        String[] lines = message.split("\n");
        for (String line : lines) {
            if (!line.startsWith("价格") || !line.contains("mqqapi")) {
                continue;
            }
            String[] parts = line.split("\\[|\\]");
            if (parts.length < 2) {
                continue;
            }
            String itemName = extractItemName(parts[1].trim());
            if (!remindItems.contains(itemName)) {
                continue;
            }

            double priceWan = extractPrice(line);
            MarketHit existing = hits.get(itemName);
            if (existing == null || priceWan < existing.priceWan) {
                hits.put(itemName, new MarketHit(itemName, priceWan, pageKey));
            }
        }

        if (hits.isEmpty()) {
            return;
        }

        for (MarketHit hit : hits.values()) {
            String dedupKey = bot.getBotId() + ":" + hit.itemName;
            if (!allowAndUpdate(lastItemRemindTimeMap, dedupKey, now, ITEM_REMIND_INTERVAL_MS)) {
                continue;
            }
            String text = buildRemindText(hit);
            sendToEnabledGroups(bot, text);
        }
    }

    private void sendToEnabledGroups(Bot bot, String text) {
        for (Map.Entry<String, Boolean> entry : groupManager.buyRemindGroupEnabledMap.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            long groupId;
            try {
                groupId = Long.parseLong(entry.getKey());
            } catch (Exception e) {
                continue;
            }
            try {
                bot.sendGroupMessage(groupId, new MessageChain().text(text));
            } catch (Exception ignored) {
            }
        }
    }

    private String buildRemindText(MarketHit hit) {
        String head = REMIND_TEMPLATES[new Random().nextInt(REMIND_TEMPLATES.length)];
        return head + "\n"
                + "物品：" + hit.itemName + "\n"
                + "价格：" + formatPrice(hit.priceWan) + "\n"
                + "页数：" + hit.pageKey + "\n"
                + "时间：" + formatNowTime();
    }

    private String formatNowTime() {
        LocalTime now = LocalTime.now();
        return now.getHour() + "点" + String.format(Locale.ROOT, "%02d", now.getMinute()) + "分";
    }

    private String resolvePageKey(long groupId, String message) {
        String cached = groupPageMap.get(groupId);
        if (StringUtils.isNotBlank(cached)) {
            return cached;
        }
        String[] lines = message.split("\n");
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            String line = lines[i];
            if (!line.contains("坊市") && !line.contains("页")) {
                continue;
            }
            Matcher matcher = PAGE_KEY.matcher(line);
            if (matcher.find()) {
                return matcher.group();
            }
        }
        Matcher matcher = PAGE_KEY.matcher(message);
        if (matcher.find()) {
            return matcher.group();
        }
        return "未知";
    }

    private String extractPageKey(String message) {
        Matcher matcher = YEMA.matcher(message);
        if (matcher.find()) {
            String pageKey = matcher.group(2).trim();
            if (!pageKey.isEmpty()) {
                return pageKey;
            }
        }
        return null;
    }

    private double extractPrice(String message) {
        if (message.contains("万 [")) {
            return Double.parseDouble(message.split("价格:|万")[1]);
        } else if (message.contains("亿 [")) {
            return Double.parseDouble(message.split("价格:|亿")[1]) * 10000.0;
        } else {
            return Double.MAX_VALUE;
        }
    }

    private String formatPrice(double priceWan) {
        if (priceWan == Double.MAX_VALUE) {
            return "未知";
        }
        if (priceWan >= 10000.0) {
            double yi = priceWan / 10000.0;
            if (Math.abs(yi - Math.rint(yi)) < 0.0001) {
                return ((long) Math.rint(yi)) + "亿";
            }
            return String.format(Locale.ROOT, "%.2f亿", yi);
        }
        if (Math.abs(priceWan - Math.rint(priceWan)) < 0.0001) {
            return ((long) Math.rint(priceWan)) + "万";
        }
        return String.format(Locale.ROOT, "%.2f万", priceWan);
    }

    private String extractItemName(String rawName) {
        StringBuilder result = new StringBuilder();
        for (char c : rawName.toCharArray()) {
            if (Character.toString(c).matches("[\\u4e00-\\u9fa5()（）]")) {
                result.append(c);
            }
        }
        return result.toString();
    }

    private boolean allowAndUpdate(Map<String, Long> timeMap, String key, long now, long intervalMs) {
        AtomicBoolean allowed = new AtomicBoolean(false);
        timeMap.compute(key, (k, last) -> {
            if (last == null || now - last > intervalMs) {
                allowed.set(true);
                return now;
            }
            return last;
        });
        if (timeMap.size() > 5000) {
            timeMap.entrySet().removeIf(e -> now - e.getValue() > 3600000L);
        }
        return allowed.get();
    }

    private static class MarketHit {
        private final String itemName;
        private final double priceWan;
        private final String pageKey;

        private MarketHit(String itemName, double priceWan, String pageKey) {
            this.itemName = itemName;
            this.priceWan = priceWan;
            this.pageKey = pageKey;
        }
    }
}
