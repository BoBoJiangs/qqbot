package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
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
    private static final long PAGE_REQUEST_TTL_MS = 15 * 1000L;
    private static final int MAX_PENDING_PAGE_COUNT = 20;
    private static final String[] REMIND_TEMPLATES = new String[]{
            "坊市中出现稀有物品需要的道友快去抢夺机缘！！！",
            "天降机缘！坊市刷出稀有物品，错过再等十年！",
            "道友注意！坊市惊现珍稀物品，手慢无！",
            "机缘已至！坊市挂出稀有宝物，速速前往！",
            "快报！坊市出现罕见物品，速去一探究竟！"
    };

    private final ExecutorService customPool = Executors.newCachedThreadPool();
    /** 待回复的坊市查询，key 为 群号:发送者QQ，按发送顺序保存页码。 */
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<PageRequest>> pendingPageMap =
            new ConcurrentHashMap<>();
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
    public void 记录商店页码(Bot bot, Group group, Member member,
            MessageChain messageChain, String message, Integer messageId) {
        long groupId = group.getGroupId();
        if ((message.contains("@小小") || message.contains("@3889001741")) && (message.contains("查看坊市") || message.contains("坊市查看"))) {
            String pageKey = extractPageKey(message);
            if (pageKey != null && (pageKey.contains("技能") || pageKey.contains("装备") || pageKey.contains("丹药") || pageKey.contains("药材") || pageKey.contains("道具"))) {
                long senderQQ = member == null ? bot.getBotId() : member.getUserId();
                enqueuePageRequest(groupId, String.valueOf(senderQQ), pageKey);
            }
        }
    }

    @GroupMessageHandler(senderIds = {3889001741L})
    public void 坊市购买提醒(Bot bot, Group group, MessageChain messageChain,
            String message, Integer messageId) {
        if (!message.contains("不鼓励不保障任何第三方交易行为")) {
            return;
        }

        // 必须在异步提交前取出页码，避免下一次坊市查询先覆盖上下文。
        String pageKey = resolvePageKey(bot, group, messageChain, message);

        // 下架消息也要先消费对应请求，否则后续页码会整体错位。
        if (message.contains("下架")) {
            return;
        }

        Set<String> remindItems = groupManager.getBuyRemindItems(bot.getBotId());
        if (remindItems.isEmpty()) {
            return;
        }

        if (groupManager.buyRemindGroupEnabledMap.values().stream().noneMatch(Boolean::booleanValue)) {
            return;
        }

        customPool.submit(() -> processMarketMessage(bot, group, message, remindItems, pageKey));
    }

    private void processMarketMessage(Bot bot, Group group, String message,
            Set<String> remindItems, String pageKey) {
        long now = System.currentTimeMillis();

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

    private String resolvePageKey(Bot bot, Group group, MessageChain messageChain, String message) {
        long groupId = group.getGroupId();
        List<String> targetQQs = extractAtQQs(messageChain);

        if (!targetQQs.isEmpty()) {
            for (String targetQQ : targetQQs) {
                PageRequest request = takePageRequest(groupId, targetQQ);
                if (request != null) {
                    return request.pageKey;
                }
            }
            return extractPageKeyFromMarketMessage(message);
        }

        // 兼容没有 @ 的旧格式：当前收到消息的机器人可能就是查询发起者。
        PageRequest request = takePageRequest(groupId, String.valueOf(bot.getBotId()));
        if (request != null) {
            return request.pageKey;
        }

        // 群内只有一个待处理请求时可以安全关联，多个请求则不猜测。
        request = takeUniquePageRequest(groupId);
        if (request != null) {
            return request.pageKey;
        }

        return extractPageKeyFromMarketMessage(message);
    }

    private String extractPageKeyFromMarketMessage(String message) {
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

    private void enqueuePageRequest(long groupId, String senderQQ, String pageKey) {
        String key = pageScope(groupId, senderQQ);
        ConcurrentLinkedDeque<PageRequest> queue = pendingPageMap
                .computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        long now = System.currentTimeMillis();
        removeExpiredRequests(queue, now);
        queue.offerLast(new PageRequest(pageKey, now));

        while (queue.size() > MAX_PENDING_PAGE_COUNT) {
            queue.pollFirst();
        }
    }

    private PageRequest takePageRequest(long groupId, String senderQQ) {
        return takePageRequest(pageScope(groupId, senderQQ));
    }

    private PageRequest takePageRequest(String key) {
        ConcurrentLinkedDeque<PageRequest> queue = pendingPageMap.get(key);
        if (queue == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        PageRequest request;
        while ((request = queue.pollFirst()) != null) {
            if (now - request.createdAt <= PAGE_REQUEST_TTL_MS) {
                if (queue.isEmpty()) {
                    pendingPageMap.remove(key, queue);
                }
                return request;
            }
        }

        pendingPageMap.remove(key, queue);
        return null;
    }

    private PageRequest takeUniquePageRequest(long groupId) {
        String prefix = groupId + ":";
        String matchedKey = null;
        int matchedCount = 0;
        long now = System.currentTimeMillis();

        for (Map.Entry<String, ConcurrentLinkedDeque<PageRequest>> entry : pendingPageMap.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }

            ConcurrentLinkedDeque<PageRequest> queue = entry.getValue();
            removeExpiredRequests(queue, now);
            if (!queue.isEmpty()) {
                matchedKey = entry.getKey();
                matchedCount++;
                if (matchedCount > 1) {
                    return null;
                }
            }
        }

        return matchedKey == null ? null : takePageRequest(matchedKey);
    }

    private void removeExpiredRequests(ConcurrentLinkedDeque<PageRequest> queue, long now) {
        while (true) {
            PageRequest first = queue.peekFirst();
            if (first == null || now - first.createdAt <= PAGE_REQUEST_TTL_MS) {
                return;
            }
            queue.pollFirst();
        }
    }

    private String pageScope(long groupId, String senderQQ) {
        return groupId + ":" + senderQQ;
    }

    private List<String> extractAtQQs(MessageChain messageChain) {
        if (messageChain == null) {
            return Collections.emptyList();
        }

        List<AtMessage> atMessages = messageChain.getMessageByType(AtMessage.class);
        if (atMessages == null || atMessages.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        for (AtMessage atMessage : atMessages) {
            String qq = atMessage.getQq();
            if (StringUtils.isNotBlank(qq) && StringUtils.isNumeric(qq)) {
                result.add(qq);
            }
        }
        return result;
    }

    @Scheduled(fixedDelay = 30000L, initialDelay = 30000L)
    private void cleanExpiredPageRequests() {
        long now = System.currentTimeMillis();
        pendingPageMap.entrySet().removeIf(entry -> {
            ConcurrentLinkedDeque<PageRequest> queue = entry.getValue();
            removeExpiredRequests(queue, now);
            return queue.isEmpty();
        });
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

    private static class PageRequest {
        private final String pageKey;
        private final long createdAt;

        private PageRequest(String pageKey, long createdAt) {
            this.pageKey = pageKey;
            this.createdAt = createdAt;
        }
    }
}
