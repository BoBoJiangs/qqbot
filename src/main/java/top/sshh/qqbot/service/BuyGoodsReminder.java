package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.UserRemindConfig;
import top.sshh.qqbot.service.GroupManager;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class BuyGoodsReminder {
    private static final Pattern YEMA = Pattern.compile("(查看坊市|坊市查看)\\s*([^\\s@]+)");
    private static final Pattern PAGE_KEY = Pattern.compile("(技能|装备|丹药|药材|道具)\\s*\\d+");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}):(\\d{1,2})-(\\d{1,2}):(\\d{1,2})");
    private static final long ITEM_REMIND_INTERVAL_MS = 10 * 60 * 1000L;
    /** 23:00-08:00 夜间时段同一物品提醒间隔放宽到1小时 */
    private static final long NIGHT_REMIND_INTERVAL_MS = 60 * 60 * 1000L;
    private static final int MAX_REMIND_ITEM_COUNT = 20;
    private static final long PAGE_REQUEST_TTL_MS = 15 * 1000L;
    private static final int MAX_PENDING_PAGE_COUNT = 20;
    private static final String[] REMIND_TEMPLATES = new String[]{
            "您订制的【%s】已送达，快去坊市查看吧！",
            "道友心心念念的【%s】现世了，速去坊市！",
            "叮！您订制的【%s】上架啦，手慢无！",
            "您的专属机缘【%s】已挂上坊市，快去抢购！",
            "恭喜道友！【%s】已到货，莫要错过！"
    };
    private static final String HELP_TEXT =
            "【专属提醒帮助】\n" +
                    "1、@我 发送【设置提醒物品】，按行填写物品名，可加一行提醒时段\n" +
                    "2、@我 发送【查看提醒示例】查看设置示例\n" +
                    "3、@我 发送【查询我的提醒】查看已设置的提醒\n" +
                    "4、@我 发送【关闭购买提醒】暂停提醒，关闭后不再艾特你\n" +
                    "5、@我 发送【开启购买提醒】恢复提醒\n" +
                    "提示：物品在坊市出现时，会在你设置提醒的群里艾特你\n" +
                    "管理员：@我 发送【开启/关闭本群购买提醒】控制本群提醒总开关";
    private static final String EXAMPLE_TEXT =
            "【专属提醒设置示例】\n" +
                    "@我 发送：\n" +
                    "设置提醒物品\n" +
                    "五指拳心剑\n" +
                    "坐忘论\n" +
                    "提醒时段：09:00 - 21:00\n" +
                    "说明：每行一个物品；提醒时段可省略（默认全天），支持跨零点如 22:00 - 06:00；最多" + MAX_REMIND_ITEM_COUNT + "个物品\n" +
                    "\n" +
                    "提醒效果示例：\n" +
                    "@你\n" +
                    "您订制的【坐忘论】已送达，快去坊市查看吧！\n" +
                    "物品：坐忘论\n" +
                    "价格：8亿\n" +
                    "页数：技能2\n" +
                    "时间：10点11分";
    private static final Logger log = LoggerFactory.getLogger(BuyGoodsReminder.class);

    private final ExecutorService customPool = Executors.newCachedThreadPool();
    /** 待回复的坊市查询，key 为 群号:发送者QQ，按发送顺序保存页码。 */
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<PageRequest>> pendingPageMap =
            new ConcurrentHashMap<>();
    /** 提醒去重，key 为 botId:用户QQ:物品名 */
    private final ConcurrentHashMap<String, Long> lastItemRemindTimeMap = new ConcurrentHashMap<>();

    @Autowired
    public GroupManager groupManager;

    /**
     * 本群提醒总开关，仅群主/群管理员或机器人控制者可操作。
     * IGNORE_ITSELF：处理群友消息、忽略机器人自己发的，ONLY_ITSELF 语义是"只处理机器人自己发出的消息"，用错会导致其他用户无法触发。
     */
    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.IGNORE_ITSELF)
    public void 开关本群购买提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        message = message.trim();
        boolean enable;
        if (message.contains("开启本群购买提醒")) {
            enable = true;
        } else if (message.contains("关闭本群购买提醒")) {
            enable = false;
        } else {
            return;
        }
        // 必须@机器人：多机器人同群时只有被@的那个响应，避免全体同时执行
        if (member == null || !isAtBot(bot, messageChain, message)) {
            return;
        }
        if (!isRemindManager(bot, member)) {
            return;
        }
        groupManager.setBuyRemindGroupEnabled(group.getGroupId(), enable);
        String text = enable
                ? "本群购买提醒已开启，群友可@我发送【查看提醒帮助】订制专属提醒"
                : "本群购买提醒已关闭，专属提醒将不再发送，@我发送【开启本群购买提醒】可恢复";
        group.sendMessage(new MessageChain().reply(messageId).text(text));
    }

    /**
     * 专属提醒命令入口，所有命令都需要@机器人，且本群提醒开关已开启。
     * IGNORE_ITSELF：处理群友消息、忽略机器人自己发的（自己发的提醒底部带at与命令文本，防止自触发）。
     */
    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.IGNORE_ITSELF)
    public void 专属提醒命令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (member == null || !isAtBot(bot, messageChain, message)) {
            return;
        }
        message = message.trim();
        if (!message.contains("设置提醒物品") && !message.contains("查看提醒帮助")
                && !message.contains("查看提醒示例") && !message.contains("查询我的提醒")
                && !message.contains("查看我的提醒") && !message.contains("关闭购买提醒")
                && !message.contains("开启购买提醒")) {
            return;
        }
        if (!groupManager.isBuyRemindGroupEnabled(group.getGroupId())) {
            group.sendMessage(new MessageChain().reply(messageId)
                    .text("本群专属提醒未开启，请联系管理员@我发送【开启本群购买提醒】开启后再使用"));
            return;
        }
        if (message.contains("设置提醒物品")) {
            设置提醒物品(bot, group, member, message, messageId);
        } else if (message.contains("查看提醒帮助")) {
            group.sendMessage(new MessageChain().reply(messageId).text(HELP_TEXT));
        } else if (message.contains("查看提醒示例")) {
            group.sendMessage(new MessageChain().reply(messageId).text(EXAMPLE_TEXT));
        } else if (message.contains("查询我的提醒") || message.contains("查看我的提醒")) {
            查询我的提醒(bot, group, member, messageId);
        } else if (message.contains("关闭购买提醒")) {
            开关购买提醒(bot, group, member, messageId, false);
        } else if (message.contains("开启购买提醒")) {
            开关购买提醒(bot, group, member, messageId, true);
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

        customPool.submit(() -> processMarketMessage(bot, message, pageKey));
    }

    private void 设置提醒物品(Bot bot, Group group, Member member, String message, Integer messageId) {
        String[] lines = message.split("\n");
        Set<String> items = new LinkedHashSet<>();
        String timeRangeLine = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.contains("提醒时段")) {
                timeRangeLine = line;
                continue;
            }
            String item = extractItemName(line);
            if (StringUtils.isNotBlank(item)) {
                items.add(item);
            }
        }

        if (items.isEmpty()) {
            group.sendMessage(new MessageChain().reply(messageId)
                    .text("未识别到提醒物品，@我 发送【查看提醒示例】查看设置格式"));
            return;
        }
        boolean truncated = items.size() > MAX_REMIND_ITEM_COUNT;
        if (truncated) {
            items = items.stream().limit(MAX_REMIND_ITEM_COUNT)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        UserRemindConfig config = new UserRemindConfig();
        config.setUserQq(member.getUserId());
        config.setGroupId(group.getGroupId());
        config.setEnabled(true);
        try {
            int[] range = parseTimeRange(timeRangeLine == null ? "提醒时段：全天" : timeRangeLine);
            if (range == null) {
                config.setAllDay(true);
                config.setTimeRangeText("全天");
            } else {
                config.setAllDay(false);
                config.setStartMinutes(range[0]);
                config.setEndMinutes(range[1]);
                config.setTimeRangeText(formatTimeRange(range[0], range[1]));
            }
        } catch (IllegalArgumentException e) {
            group.sendMessage(new MessageChain().reply(messageId)
                    .text("提醒时段" + e.getMessage() + "，正确示例：提醒时段：09:00 - 21:00，或 提醒时段：全天"));
            return;
        }
        config.setItems(items);
        groupManager.setUserRemindConfig(bot.getBotId(), member.getUserId(), config);

        StringBuilder reply = new StringBuilder("专属提醒设置成功！\n提醒物品：\n")
                .append(StringUtils.join(items, "\n"))
                .append("\n提醒时段：").append(config.getTimeRangeText())
                .append("\n状态：已开启");
        if (truncated) {
            reply.append("\n（物品最多").append(MAX_REMIND_ITEM_COUNT).append("个，超出部分未生效）");
        }
        group.sendMessage(new MessageChain().reply(messageId).text(reply.toString()));
    }

    private void 查询我的提醒(Bot bot, Group group, Member member, Integer messageId) {
        UserRemindConfig config = groupManager.getUserRemindConfig(bot.getBotId(), member.getUserId());
        if (config == null || config.getItems().isEmpty()) {
            group.sendMessage(new MessageChain().reply(messageId)
                    .text("您还没有设置专属提醒，@我 发送【查看提醒示例】查看设置方法"));
            return;
        }
        String text = "您的专属提醒：\n"
                + "状态：" + (config.isEnabled() ? "已开启" : "已关闭") + "\n"
                + "提醒时段：" + config.getTimeRangeText() + "\n"
                + "提醒物品：\n" + StringUtils.join(config.getItems(), "\n");
        group.sendMessage(new MessageChain().reply(messageId).text(text));
    }

    private void 开关购买提醒(Bot bot, Group group, Member member, Integer messageId, boolean enable) {
        UserRemindConfig config = groupManager.getUserRemindConfig(bot.getBotId(), member.getUserId());
        if (config == null || config.getItems().isEmpty()) {
            group.sendMessage(new MessageChain().reply(messageId)
                    .text("您还没有设置专属提醒，@我 发送【查看提醒示例】查看设置方法"));
            return;
        }
        if (config.isEnabled() == enable) {
            group.sendMessage(new MessageChain().reply(messageId)
                    .text("您的专属提醒已经是" + (enable ? "开启" : "关闭") + "状态，无需重复操作"));
            return;
        }
        config.setEnabled(enable);
        groupManager.setUserRemindConfig(bot.getBotId(), member.getUserId(), config);
        String text = enable
                ? "已开启专属提醒，坊市出现你订阅的物品时会艾特你"
                : "已关闭专属提醒，坊市出现物品时不再艾特你，发送【开启购买提醒】可恢复";
        group.sendMessage(new MessageChain().reply(messageId).text(text));
    }

    private void processMarketMessage(Bot bot, String message, String pageKey) {
        long now = System.currentTimeMillis();
        LocalTime nowTime = LocalTime.now();
        long remindIntervalMs = isNightTime(nowTime) ? NIGHT_REMIND_INTERVAL_MS : ITEM_REMIND_INTERVAL_MS;
        if (lastItemRemindTimeMap.size() > 5000) {
            lastItemRemindTimeMap.entrySet().removeIf(e -> now - e.getValue() > 2 * NIGHT_REMIND_INTERVAL_MS);
        }

        // 同一物品在同一条消息内保留最低价
        Map<String, MarketHit> hits = new HashMap<>();
        for (String line : message.split("\n")) {
            if (!line.startsWith("价格") || !line.contains("mqqapi")) {
                continue;
            }
            String[] parts = line.split("\\[|\\]");
            if (parts.length < 2) {
                continue;
            }
            String itemName = extractItemName(parts[1].trim());
            if (StringUtils.isBlank(itemName)) {
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
            // 找出订阅了该物品且当前可提醒的用户，按回发群分组（同群用户合并到一条消息）
            Map<Long, List<UserRemindConfig>> remindGroups = new LinkedHashMap<>();
            for (UserRemindConfig config : groupManager.getUserRemindConfigs(bot.getBotId()).values()) {
                if (!config.isEnabled() || config.getGroupId() == null || config.getUserQq() == null) {
                    continue;
                }
                // 回发群未开启提醒总开关时不发送，且不占用去重窗口，开启后能立即收到
                if (!groupManager.isBuyRemindGroupEnabled(config.getGroupId())) {
                    continue;
                }
                if (!config.matchTime(nowTime)) {
                    continue;
                }
                if (!config.getItems().contains(hit.itemName)) {
                    continue;
                }
                String dedupKey = bot.getBotId() + ":" + config.getUserQq() + ":" + hit.itemName;
                if (!allowAndUpdate(lastItemRemindTimeMap, dedupKey, now, remindIntervalMs)) {
                    continue;
                }
                remindGroups.computeIfAbsent(config.getGroupId(), k -> new ArrayList<>()).add(config);
            }

            for (Map.Entry<Long, List<UserRemindConfig>> entry : remindGroups.entrySet()) {
                MessageChain chain = new MessageChain();
                for (UserRemindConfig config : entry.getValue()) {
                    chain.at(config.getUserQq() + "");
                }
                // 底部真实艾特机器人，让其他群友也能看出如何订制专属提醒
                chain.text("\n" + buildRemindText(hit) + "\n")
                        .at(bot.getBotId() + "")
                        .text(" 发送【查看提醒帮助】，也可以订制你的专属提醒");
                try {
                    bot.sendGroupMessage(entry.getKey(), chain);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String buildRemindText(MarketHit hit) {
        String head = String.format(REMIND_TEMPLATES[new Random().nextInt(REMIND_TEMPLATES.length)], hit.itemName);
        return head + "\n"
                + "物品：" + hit.itemName + "\n"
                + "价格：" + formatPrice(hit.priceWan) + "\n"
                + "页数：" + hit.pageKey + "\n"
                + "时间：" + formatNowTime();
    }

    /**
     * 日志用消息摘要：压成单行并截断，避免多行命令刷屏。
     */
    private String brief(String message) {
        if (message == null) {
            return "null";
        }
        return StringUtils.abbreviate(message.replaceAll("\\s+", " ").trim(), 200);
    }

    /**
     * 23:00-08:00 视为夜间时段，提醒去重间隔放宽。
     */
    private boolean isNightTime(LocalTime now) {
        int cur = now.getHour() * 60 + now.getMinute();
        return cur >= 23 * 60 || cur < 8 * 60;
    }

    /**
     * 解析"提醒时段：09:00 - 21:00"行，返回 [开始分钟, 结束分钟]；"全天"返回 null。
     * 兼容全角冒号、~、—等分隔符与多余空格；支持跨零点时段（如 22:00 - 06:00）。
     */
    private int[] parseTimeRange(String timeRangeLine) {
        String content = timeRangeLine.replaceFirst(".*提醒时段[:：]?", "").trim();
        String normalized = content.replace("：", ":")
                .replaceAll("[～~—–－]", "-")
                .replace(" ", "")
                .replace("　", "");
        if (normalized.isEmpty() || normalized.contains("全天")) {
            return null;
        }
        Matcher matcher = TIME_RANGE.matcher(normalized);
        if (!matcher.find()) {
            throw new IllegalArgumentException("格式不正确：" + content);
        }
        int startHour = Integer.parseInt(matcher.group(1));
        int startMin = Integer.parseInt(matcher.group(2));
        int endHour = Integer.parseInt(matcher.group(3));
        int endMin = Integer.parseInt(matcher.group(4));
        if (startHour > 23 || endHour > 23 || startMin > 59 || endMin > 59) {
            throw new IllegalArgumentException("数值不正确：" + content);
        }
        return new int[]{startHour * 60 + startMin, endHour * 60 + endMin};
    }

    private String formatTimeRange(int startMinutes, int endMinutes) {
        return String.format(Locale.ROOT, "%02d:%02d - %02d:%02d",
                startMinutes / 60, startMinutes % 60, endMinutes / 60, endMinutes % 60);
    }

    private String formatNowTime() {
        LocalTime now = LocalTime.now();
        return now.getHour() + "点" + String.format(Locale.ROOT, "%02d", now.getMinute()) + "分";
    }

    private boolean isAtBot(Bot bot, MessageChain messageChain, String message) {
        String botId = String.valueOf(bot.getBotId());
        boolean hasAtMessage = false;
        if (messageChain != null) {
            List<AtMessage> atMessages = messageChain.getMessageByType(AtMessage.class);
            if (atMessages != null) {
                for (AtMessage atMessage : atMessages) {
                    if (botId.equals(atMessage.getQq())) {
                        return true;
                    }
                }
                hasAtMessage = !atMessages.isEmpty();
            }
        }
        // 链里没有标准at段（markdown mention等）时才按文本兜底；
        // 有at段但都不是本bot时不兜底，避免@别人的昵称文本与本bot昵称撞名误判（多机器人场景）
        if (hasAtMessage || message == null) {
            return false;
        }
        return message.contains(botId) || message.contains("@" + bot.getBotName());
    }

    /**
     * 是否可操作本群提醒开关：群主/群管理员，或机器人控制者（controlQQ，未配置时回退 masterQQ）。
     */
    private boolean isRemindManager(Bot bot, Member member) {
        if (member == null) {
            return false;
        }
        String role = member.getRole();
        if ("owner".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role)) {
            return true;
        }
        String controlQQ = bot.getBotConfig().getControlQQ();
        if (StringUtils.isNotBlank(controlQQ)) {
            return ("&" + controlQQ + "&").contains("&" + member.getUserId() + "&");
        }
        return bot.getBotConfig().getMasterQQ() == member.getUserId();
    }

    private String resolvePageKey(Bot bot, Group group, MessageChain messageChain, String message) {
        long groupId = group.getGroupId();
        // 优先按消息里@的对象关联页码。
        List<String> atQQs = extractAtQQs(messageChain);
        for (String atQQ : atQQs) {
            PageRequest request = takePageRequest(groupId, atQQ);
            if (request != null) {
                return request.pageKey;
            }
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
