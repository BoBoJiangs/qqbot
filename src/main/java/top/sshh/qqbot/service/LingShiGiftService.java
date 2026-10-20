package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.service.utils.Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 新模式只读写独立配置，不修改旧模式的 lingShiQQ、lingShiNum、lingShiTotal。 */
@Component
public class LingShiGiftService {
    private static final Logger log = LoggerFactory.getLogger(LingShiGiftService.class);
    private static final long REQUEST_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long RECEIPT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);
    private static final Pattern CODE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern MENTION = Pattern.compile(
            "(?:@!?|at_tinyid=|\\[CQ:at,qq=)([0-9]+)(?![0-9])");
    private final LingShiGiftStore store;
    private final LongSupplier clock;
    // 单次申请保留到成功回执或1分钟超时；同一机器人在该群有申请时暂停专属QQ自动赠送。
    private final Map<String, TemporaryGift> temporaryGifts = new HashMap<>();
    private final List<SentGift> sentGifts = new ArrayList<>();
    private final Map<String, SentGift> receiptRoutes = new LinkedHashMap<>();

    @Autowired
    public LingShiGiftService(LingShiGiftStore store) {
        this(store, System::currentTimeMillis);
    }

    LingShiGiftService(LingShiGiftStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.NOT_IGNORE)
    public synchronized void 新模式赠送指令(Bot bot, Group group, Member member, MessageChain chain,
            String message, Integer messageId) {
        String command = Utils.getMessageText(chain).trim();
        if (command.isEmpty()) {
            command = StringUtils.defaultString(message).trim();
        }
        boolean configure = command.equals("设置赠送灵石") || command.startsWith("设置赠送灵石\n")
                || command.startsWith("设置赠送灵石\r\n");
        boolean request = command.startsWith("申请灵石");
        boolean query = command.equals("查询灵石赠送");
        boolean viewSettings = command.equals("查看赠送灵石设置");
        if ((!configure && !request && !query && !viewSettings) || member == null) {
            return;
        }
        boolean mentioned = isForUser(chain, message, bot.getBotId(), false);
        boolean self = member.getUserId() == bot.getBotId();
        // 配置及查看设置只接受机器人账号本人发送；群成员的单次申请仍须 @ 本号。
        if (configure || viewSettings ? !self : !mentioned && !(self && query)) {
            return;
        }
        try {
            expire();
            if (configure) {
                configure(bot, group, command);
                return;
            }
            if (viewSettings) {
                showSettings(bot, group);
                return;
            }
            Map<Long, Long> recipients = store.recipientsFor(group.getGroupId());
            Long groupTotal = store.totalFor(bot.getBotId(), group.getGroupId());
            if (!recipients.containsValue(bot.getBotId()) && groupTotal == null) {
                group.sendMessage(new MessageChain().text("本号尚未在本群设置新模式赠送灵石。"));
                return;
            }
            if (query) {
                group.sendMessage(new MessageChain().text(groupTotal == null ? "本号在本群未启用新模式灵石累计。"
                        : groupTotalText(groupTotal)));
                return;
            }
            String value = command.substring("申请灵石".length()).trim();
            if (!value.matches("[0-9]+")) {
                throw new IllegalArgumentException("格式：@机器人 申请灵石100（单位：万，单次生效）");
            }
            long amount = Math.multiplyExact(Long.parseLong(value), 10000L);
            if (amount <= 0) {
                throw new IllegalArgumentException("单次申请的数量必须大于0（单位：万）。");
            }
            temporaryGifts.put(key(group.getGroupId(), member.getUserId()),
                    new TemporaryGift(bot.getBotId(), amount, clock.getAsLong() + REQUEST_TIMEOUT_MS));
            group.sendMessage(new MessageChain().at(String.valueOf(member.getUserId()))
                    .text("已受理单次赠送" + value + "万灵石，请在1分钟内获取你本人的接收码并完成接收；"
                            + "仅本次生效，期间暂停本号在本群的专属QQ自动赠送。"));
        } catch (IllegalArgumentException | ArithmeticException e) {
            group.sendMessage(new MessageChain().text(e instanceof NumberFormatException
                    || e instanceof ArithmeticException ? "数量超出支持范围，请检查输入。" : e.getMessage()));
        } catch (IOException e) {
            log.warn("新模式赠送配置读取或保存失败: botId={}, groupId={}", bot.getBotId(), group.getGroupId(), e);
            group.sendMessage(new MessageChain().text("新模式赠送配置读取或保存失败，请检查本地配置文件。"));
        }
    }

    private void configure(Bot bot, Group group, String command) throws IOException {
        Map<String, Long> fields = new HashMap<>();
        String[] lines = command.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] pair = line.split("[:：]", 2);
            if (pair.length != 2) {
                throw new IllegalArgumentException("格式：设置赠送灵石\nQQ：收款人QQ\n群号：群号\n灵石：0（可省略）");
            }
            String name = pair[0].trim().toUpperCase(java.util.Locale.ROOT);
            String value = pair[1].trim();
            if (!(name.equals("QQ") || name.equals("群号") || name.equals("灵石"))
                    || fields.containsKey(name) || !value.matches("[0-9]+")) {
                throw new IllegalArgumentException("仅支持QQ、群号和可选的灵石字段，字段不能重复，数量须为非负整数。");
            }
            fields.put(name, Long.parseLong(value));
        }
        if (!fields.containsKey("QQ") || !fields.containsKey("群号")) {
            throw new IllegalArgumentException("请填写QQ（收款人）和群号；灵石为可选的累计初始值（单位：万）。");
        }
        long groupId = fields.get("群号");
        long recipient = fields.get("QQ");
        Long initialTotal = fields.containsKey("灵石") ? Math.multiplyExact(fields.get("灵石"), 10000L) : null;
        store.configure(groupId, recipient, bot.getBotId(), initialTotal);
        group.sendMessage(new MessageChain().text("已保存新模式赠送：群" + groupId + "，收款人QQ " + recipient
                + "，自动按接收码中的今日剩余最大可收额度赠送。\n"
                + (initialTotal != null ? "本号在该群累计初始值：" + Utils.formatNumberWithUnit(initialTotal) + "灵石（含手续费）。"
                : "本号在该群未启用累计统计。")));
    }

    private void showSettings(Bot bot, Group group) throws IOException {
        Map<Long, List<Long>> settings = store.recipientsByGroupFor(bot.getBotId());
        if (settings.isEmpty()) {
            group.sendMessage(new MessageChain().text("本号尚未设置新模式赠送灵石。"));
            return;
        }
        StringBuilder text = new StringBuilder("本号新模式赠送设置\n默认赠送：按接收码中的今日剩余最大可收额度\n");
        for (Map.Entry<Long, List<Long>> entry : settings.entrySet()) {
            Long total = store.totalFor(bot.getBotId(), entry.getKey());
            text.append("\n群号：").append(entry.getKey())
                    .append("\nQQ：").append(entry.getValue().isEmpty() ? "无（当前仅累计统计）"
                            : entry.getValue().stream().map(String::valueOf).collect(Collectors.joining("、")))
                    .append(total == null ? "\n累计统计：未启用\n"
                            : "\n当前累计（含手续费）：" + Utils.formatNumberWithUnit(total) + "\n");
        }
        group.sendMessage(new MessageChain().text(text.toString().trim()));
    }

    /** 返回 true 表示此事件属于新模式或其他号的回执，旧模式无需再次处理。 */
    public synchronized boolean handleGameMessage(Bot bot, Group group, MessageChain chain,
            String text, Integer messageId) {
        if (!text.contains("您的接收码为") && !(text.contains("共赠送") && text.contains("手续费"))) {
            return false;
        }
        long groupId = group.getGroupId();
        expire();
        try {
            Map<Long, Long> recipients = store.recipientsFor(groupId);
            if (text.contains("共赠送") && text.contains("手续费")) {
                long amount = TestService.extractGiftAmount(text);
                String eventKey = eventKey(groupId, chain, text, messageId);
                Long total = store.recordReceipt(bot.getBotId(), groupId, eventKey, amount);
                if (total != null) {
                    MessageChain notification = new MessageChain();
                    if (messageId != null && messageId != 0) {
                        notification.reply(messageId);
                    }
                    group.sendMessage(notification.text(groupTotalText(total)));
                }
                SentGift route = receiptRoutes.get(eventKey);
                if (route == null) {
                    route = resolveReceipt(groupId, chain, text, amount);
                    if (route != null) {
                        sentGifts.remove(route);
                        receiptRoutes.put(eventKey, route);
                        while (receiptRoutes.size() > 2048) {
                            receiptRoutes.remove(receiptRoutes.keySet().iterator().next());
                        }
                    }
                }
                if (route != null && route.temporaryRequest != null && amount > 0) {
                    // 旧申请的迟到回执不能移除同一收款人后来重新发起的申请。
                    temporaryGifts.remove(route.requestKey, route.temporaryRequest);
                }
                if (route != null) {
                    if (route.newMode || route.botId != bot.getBotId() || route.counted) {
                        return true;
                    }
                    route.counted = true;
                    return false;
                }
                // 兼容升级前已发出的旧赠送回执，同时避免给其他号的回执串入旧累计。
                return !isForUser(chain, text, bot.getBotId(), true);
            }
            if (!text.contains("您的接收码为")) {
                return false;
            }
            if (store.hasClaimedCode(eventKey(groupId, chain, text, messageId))) {
                return true;
            }
            Map<Long, Long> owners = new HashMap<>(recipients);
            for (Map.Entry<String, TemporaryGift> entry : temporaryGifts.entrySet()) {
                String prefix = groupId + ":";
                if (entry.getKey().startsWith(prefix)) {
                    owners.put(Long.parseLong(entry.getKey().substring(prefix.length())), entry.getValue().botId);
                }
            }
            Long recipientId = null;
            for (Long candidate : owners.keySet()) {
                if (isForUser(chain, text, candidate, true)) {
                    if (recipientId != null) {
                        return true;
                    }
                    recipientId = candidate;
                }
            }
            if (recipientId == null) {
                return false;
            }
            if (owners.get(recipientId) != bot.getBotId()) {
                return true;
            }
            String requestKey = key(groupId, recipientId);
            TemporaryGift request = temporaryGifts.get(requestKey);
            if (request != null ? request.sent : hasTemporaryRequest(groupId, bot.getBotId())) {
                // 暂停的专属QQ接收码也不能回退到旧模式；等待实际接收完成后再恢复自动赠送。
                return true;
            }
            Matcher matcher = CODE.matcher(text.substring(text.indexOf("您的接收码为")));
            if (!matcher.find()) {
                return true;
            }
            long amount = request == null ? TestService.extractGiftReceiveLimit(text) : request.amount;
            if (amount <= 0) {
                group.sendMessage(new MessageChain().at(String.valueOf(recipientId)).text(amount == 0
                        ? "今日剩余可收额度为0，未发起赠送。" : "未识别到今日剩余可收额度，请重新获取接收码。"));
                return true;
            }
            if (!store.claimCode(eventKey(groupId, chain, text, messageId))) {
                return true;
            }
            int sentId;
            try {
                sentId = group.sendMessage(new MessageChain().at("3889001741")
                        .text("赠送灵石 " + matcher.group() + " " + amount));
            } catch (RuntimeException e) {
                log.warn("新模式赠送指令发送失败: botId={}, groupId={}", bot.getBotId(), groupId, e);
                return true;
            }
            sentGifts.add(new SentGift(groupId, bot.getBotId(), amount, sentId, true,
                    clock.getAsLong() + RECEIPT_TIMEOUT_MS, requestKey, request));
            if (request != null) {
                request.sent = true;
            }
            return true;
        } catch (IOException | ArithmeticException e) {
            log.warn("新模式灵石赠送处理失败: botId={}, groupId={}", bot.getBotId(), groupId, e);
            // 配置损坏时不能回退到旧模式发送，否则可能把新模式接收码送给错误的账号。
            return text.contains("您的接收码为");
        }
    }

    public synchronized void recordLegacyGift(long groupId, long botId, long amount, int sentMessageId) {
        expire();
        sentGifts.add(new SentGift(groupId, botId, amount, sentMessageId, false,
                clock.getAsLong() + RECEIPT_TIMEOUT_MS, null, null));
    }

    private SentGift resolveReceipt(long groupId, MessageChain chain, String text, long amount) {
        List<SentGift> candidates = sentGifts.stream().filter(gift -> gift.groupId == groupId)
                .collect(Collectors.toList());
        if (chain != null) {
            for (ReplyMessage reply : chain.getMessageByType(ReplyMessage.class)) {
                for (SentGift gift : candidates) {
                    if (gift.sentMessageId != 0 && String.valueOf(gift.sentMessageId).equals(reply.getId())) {
                        return gift;
                    }
                }
            }
        }
        List<SentGift> addressed = candidates.stream()
                .filter(gift -> isForUser(chain, text, gift.botId, true)).collect(Collectors.toList());
        if (!addressed.isEmpty()) {
            candidates = addressed;
        } else if (hasMention(chain, text)) {
            return null;
        }
        for (SentGift gift : candidates) {
            if (gift.amount == amount) {
                return gift;
            }
        }
        return null;
    }

    private void expire() {
        long now = clock.getAsLong();
        temporaryGifts.values().removeIf(gift -> gift.expiresAt <= now);
        sentGifts.removeIf(gift -> gift.expiresAt <= now);
    }

    private boolean hasTemporaryRequest(long groupId, long botId) {
        String prefix = groupId + ":";
        return temporaryGifts.entrySet().stream()
                .anyMatch(entry -> entry.getKey().startsWith(prefix) && entry.getValue().botId == botId);
    }

    static boolean isForUser(MessageChain chain, String message, long userId, boolean allowReply) {
        if (chain != null) {
            List<AtMessage> mentions = chain.getMessageByType(AtMessage.class);
            if (!mentions.isEmpty()) {
                return mentions.stream().anyMatch(at -> String.valueOf(userId).equals(at.getQq()));
            }
        }
        String text = Utils.getMessageText(chain);
        if (StringUtils.isBlank(text)) {
            text = StringUtils.defaultString(message);
        }
        Matcher matcher = MENTION.matcher(text);
        boolean hasMention = false;
        while (matcher.find()) {
            hasMention = true;
            if (String.valueOf(userId).equals(matcher.group(1))) {
                return true;
            }
        }
        if (!hasMention && allowReply && chain != null) {
            for (ReplyMessage reply : chain.getMessageByType(ReplyMessage.class)) {
                if (String.valueOf(userId).equals(reply.getQq())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasMention(MessageChain chain, String text) {
        return chain != null && !chain.getMessageByType(AtMessage.class).isEmpty() || MENTION.matcher(text).find();
    }

    private static String key(long groupId, long recipientId) {
        return groupId + ":" + recipientId;
    }

    private static String groupTotalText(long total) {
        return "本号在本群赠送灵石累计（含手续费）：" + Utils.formatNumberWithUnit(total);
    }

    private static String eventKey(long groupId, MessageChain chain, String text, Integer messageId) {
        if (messageId != null && messageId != 0) {
            return groupId + ":message:" + messageId;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (String.valueOf(chain) + text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (byte value : digest) {
                hash.append(String.format("%02x", value & 0xff));
            }
            return groupId + ":text:" + hash;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class TemporaryGift {
        final long botId;
        final long amount;
        final long expiresAt;
        boolean sent;

        TemporaryGift(long botId, long amount, long expiresAt) {
            this.botId = botId;
            this.amount = amount;
            this.expiresAt = expiresAt;
        }
    }

    private static final class SentGift {
        private final long groupId;
        private final long botId;
        private final long amount;
        private final int sentMessageId;
        private final boolean newMode;
        private final long expiresAt;
        private final String requestKey;
        private final TemporaryGift temporaryRequest;
        private boolean counted;

        SentGift(long groupId, long botId, long amount, int sentMessageId, boolean newMode,
                long expiresAt, String requestKey, TemporaryGift temporaryRequest) {
            this.groupId = groupId;
            this.botId = botId;
            this.amount = amount;
            this.sentMessageId = sentMessageId;
            this.newMode = newMode;
            this.expiresAt = expiresAt;
            this.requestKey = requestKey;
            this.temporaryRequest = temporaryRequest;
        }
    }
}
