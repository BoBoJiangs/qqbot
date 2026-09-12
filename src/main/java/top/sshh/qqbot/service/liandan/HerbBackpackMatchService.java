package top.sshh.qqbot.service.liandan;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zhuangxv.bot.api.ApiResult;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.ForwardMessage;
import com.zhuangxv.bot.message.support.ForwardNodeMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.ProductPriceResponse;
import top.sshh.qqbot.service.utils.GetForwardMsgApi;
import top.sshh.qqbot.service.utils.Utils;

import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用被引用的药材消息或多页药材合并转发，执行只读的贪心丹方匹配。 */
@Component
public class HerbBackpackMatchService {
    private static final Logger logger = LoggerFactory.getLogger(HerbBackpackMatchService.class);
    private static final int DEFAULT_DAN_COUNT = 6;
    private static final String FORWARD_NAME = "药材背包匹配助手";
    private static final String FURNACE = "丹炉寒铁铸心炉";
    private static final String PRICE_REFRESH_NOTE =
            "注：匹配结果会随药材和坊市丹药价格实时变动，为确保价格准确请刷新坊市后重试！";
    private static final String USAGE = "用法：引用药材消息或包含多页药材的合并转发后，发送“匹配炼金丹 [成丹数]”或“匹配坊市丹 [成丹数]”；成丹数必须为正整数。";
    private static final int MAX_FORWARD_DEPTH = 5;
    private static final int MAX_FORWARD_NODES = 500;
    private static final int MATCH_POOL_SIZE = 4;
    private static final int MATCH_QUEUE_CAPACITY = 100;
    private static final AtomicInteger MATCH_THREAD_NUMBER = new AtomicInteger();

    private static final Pattern COMMAND_PATTERN = Pattern.compile("^匹配(炼金丹|坊市丹)(?:\\s+(\\S+))?\\s*$");
    private static final Pattern NAME_PATTERN = Pattern.compile("名字\\s*[:：]\\s*(.+)");
    private static final Pattern COUNT_PATTERN = Pattern.compile("拥有数量\\s*[:：]\\s*(\\d+)");
    private static final Pattern INLINE_HERB_COUNT_PATTERN =
            Pattern.compile("^(.+?)\\s*[-－—]\\s*数量\\s*[:：]\\s*(\\d+)(?:\\D.*)?$");

    private static final Comparator<PricedRecipe> RECIPE_ORDER = Comparator
            .comparingLong(PricedRecipe::getUnitProfit).reversed()
            .thenComparingLong(PricedRecipe::getUnitCost)
            .thenComparingInt(c -> c.blueprint.getTotalCount())
            .thenComparing(c -> c.blueprint.getDanName())
            .thenComparing(c -> c.blueprint.signature());

    @Autowired
    private DanRecipeQueryService danRecipeQueryService;

    @Autowired
    private DanCalculator danCalculator;

    @Autowired
    private ProductPriceResponse productPriceResponse;

    /**
     * 背包匹配包含数据库查询和转发消息 API 调用，使用独立线程池避免多人匹配时占满自动炼丹线程池。
     * 有界队列用于限制突发请求占用的内存。
     */
    private final ThreadPoolExecutor matchExecutor = new ThreadPoolExecutor(
            MATCH_POOL_SIZE,
            MATCH_POOL_SIZE,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MATCH_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "herb-backpack-match-" + MATCH_THREAD_NUMBER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /**
     * 匹配流程（展开合并转发）会产生大量临时字符串，处理完成后延迟触发一次 GC，
     * 立即回收并促使 G1 把内存还给系统；配合 -XX:+ExplicitGCInvokesConcurrent 走并发回收，停顿很小。
     */
    private static final ScheduledExecutorService POST_MATCH_GC = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "herb-backpack-match-gc");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 多人同时匹配时，各自的 finally 都会尝试调度 GC；用此标记在同一窗口内合并为一次，
     * 避免连续触发多次并发回收周期。
     */
    private static final AtomicBoolean POST_MATCH_GC_PENDING = new AtomicBoolean();

    /** 提交实际群聊匹配任务；请求者信息在进入异步线程前完成快照。 */
    public void submitMatch(String message, MessageChain messageChain, Group group, Bot bot, Member member) {
        MatchRequester requester = MatchRequester.from(member);
        try {
            matchExecutor.execute(() -> handleMatch(message, messageChain, group, bot, requester));
        } catch (RejectedExecutionException e) {
            logger.warn("药材背包匹配队列已满，拒绝用户 {}({}) 的请求",
                    requester.displayName, requester.userId);
            group.sendMessage(new MessageChain().text("当前背包匹配任务较多，请稍后重试。"));
        }
    }

    @PreDestroy
    public void shutdownMatchExecutor() {
        matchExecutor.shutdown();
    }

    public void handleMatch(String message, MessageChain messageChain, Group group, Bot bot) {
        handleMatch(message, messageChain, group, bot, MatchRequester.unknown());
    }

    void handleMatch(
            String message,
            MessageChain messageChain,
            Group group,
            Bot bot,
            MatchRequester requester
    ) {
        try {
            Config config = danCalculator.getConfig(bot.getBotId());
            int configuredDanCount = config != null && config.getDanNumber() > 0
                    ? config.getDanNumber() : DEFAULT_DAN_COUNT;
            MatchCommand command = parseCommand(message, configuredDanCount);
            if (!command.valid) {
                group.sendMessage(new MessageChain().text(command.error + "\n" + USAGE));
                return;
            }

            String replyText = extractReplyText(messageChain, bot);
            Map<String, Long> inventory = parseBackpack(replyText);
            if (inventory.isEmpty()) {
                group.sendMessage(new MessageChain().text("未从引用消息中解析到药材。\n" + USAGE));
                return;
            }

            HerbBackpackMatchResult result = match(
                    inventory,
                    danRecipeQueryService.getAllRecipeBlueprints(),
                    command.mode,
                    command.danCount,
                    danRecipeQueryService.getDanAlchemyValues());
            sendResult(group, bot, command, result, requester);
        } catch (Exception e) {
            logger.error("药材背包匹配失败: {}", e.getMessage(), e);
            group.sendMessage(new MessageChain().text("药材背包匹配失败：" + e.getMessage()));
        } finally {
            if (POST_MATCH_GC_PENDING.compareAndSet(false, true)) {
                POST_MATCH_GC.schedule(() -> {
                    POST_MATCH_GC_PENDING.set(false);
                    System.gc();
                }, 3, TimeUnit.SECONDS);
            }
        }
    }

    MatchCommand parseCommand(String message, int configuredDanCount) {
        Matcher matcher = COMMAND_PATTERN.matcher(StringUtils.defaultString(message).trim());
        if (!matcher.find()) {
            return MatchCommand.invalid("命令格式不正确。");
        }

        MatchMode mode = "坊市丹".equals(matcher.group(1)) ? MatchMode.MARKET : MatchMode.ALCHEMY;
        String danCountText = matcher.group(2);
        if (StringUtils.isBlank(danCountText)) {
            return MatchCommand.valid(mode, configuredDanCount > 0 ? configuredDanCount : DEFAULT_DAN_COUNT);
        }
        if (!danCountText.matches("[1-9]\\d*")) {
            return MatchCommand.invalid("成丹数必须为正整数。");
        }
        try {
            return MatchCommand.valid(mode, Integer.parseInt(danCountText));
        } catch (NumberFormatException e) {
            return MatchCommand.invalid("成丹数过大，请输入有效的正整数。");
        }
    }

    /** 从普通引用中读取最后一个有效文本段。 */
    String extractReplyText(MessageChain messageChain) {
        return extractReplyText(messageChain, null);
    }

    /**
     * 读取引用正文；引用的是合并转发时，通过 get_forward_msg 展开所有节点。
     * 普通文本和 Markdown 仍沿用最后一个有效文本段的规则。
     */
    String extractReplyText(MessageChain messageChain, Bot bot) {
        if (messageChain == null) return "";
        List<ReplyMessage> replies = messageChain.getMessageByType(ReplyMessage.class);
        if (replies == null || replies.isEmpty()) return "";

        ReplyMessage reply = replies.get(replies.size() - 1);
        MessageChain replyChain = reply.getChain();
        String directText = lastNonBlankText(replyChain);
        if (StringUtils.isBlank(directText)) {
            directText = StringUtils.defaultString(reply.getText());
        }

        String forwardText = extractForwardText(replyChain, bot);
        if (StringUtils.isNotBlank(forwardText)) return forwardText;

        // 普通引用已经能解析药材时，不额外请求 get_msg。
        if (!parseBackpack(directText).isEmpty() || bot == null) return directText;

        MessageChain quotedChain = getQuotedMessageChain(bot, reply.getId());
        forwardText = extractForwardText(quotedChain, bot);
        if (StringUtils.isNotBlank(forwardText)) return forwardText;

        String fetchedText = lastNonBlankText(quotedChain);
        return StringUtils.isNotBlank(fetchedText) ? fetchedText : directText;
    }

    private String lastNonBlankText(MessageChain chain) {
        String lastText = "";
        if (chain == null) return lastText;
        for (TextMessage textMessage : chain.getMessageByType(TextMessage.class)) {
            if (StringUtils.isNotBlank(textMessage.getText())) {
                lastText = textMessage.getText();
            }
        }
        return lastText;
    }

    private MessageChain getQuotedMessageChain(Bot bot, String messageId) {
        if (bot == null || StringUtils.isBlank(messageId) || !messageId.matches("-?\\d+")) return null;
        try {
            return bot.getMessage(Integer.parseInt(messageId));
        } catch (Exception e) {
            logger.warn("读取被引用消息失败, messageId={}, reason={}", messageId, e.getMessage());
            return null;
        }
    }

    private String extractForwardText(MessageChain chain, Bot bot) {
        if (chain == null || bot == null) return "";
        List<ForwardMessage> forwards = chain.getMessageByType(ForwardMessage.class);
        if (forwards == null || forwards.isEmpty()) return "";

        StringBuilder text = new StringBuilder();
        ForwardReadContext context = new ForwardReadContext();
        for (ForwardMessage forward : forwards) {
            appendForwardById(bot, forward.getId(), 0, context, text);
        }
        return text.toString().trim();
    }

    private void appendForwardById(
            Bot bot,
            String forwardId,
            int depth,
            ForwardReadContext context,
            StringBuilder text
    ) {
        if (bot == null || StringUtils.isBlank(forwardId) || depth > MAX_FORWARD_DEPTH
                || context.nodeCount >= MAX_FORWARD_NODES || !context.visitedIds.add(forwardId)) {
            return;
        }
        JSONArray messages = fetchForwardMessages(bot, forwardId);
        if (messages == null) return;
        appendForwardPayload(bot, messages, depth, context, text);
    }

    private JSONArray fetchForwardMessages(Bot bot, String forwardId) {
        JSONArray messages = invokeForwardApi(bot, new GetForwardMsgApi(forwardId));
        if (messages != null && !messages.isEmpty()) return messages;

        // SnowLuma 文档允许 id；该次重试也兼容只接受 id 的旧版本。
        JSONArray aliasMessages = invokeForwardApi(bot, GetForwardMsgApi.withId(forwardId));
        return aliasMessages != null ? aliasMessages : messages;
    }

    private JSONArray invokeForwardApi(Bot bot, GetForwardMsgApi api) {
        try {
            ApiResult result = bot.invoke(api);
            if (result == null || result.getData() == null) return null;
            Object data = JSON.toJSON(result.getData());
            if (data instanceof JSONArray) return (JSONArray) data;
            if (data instanceof JSONObject) {
                Object messages = JSON.toJSON(((JSONObject) data).get("messages"));
                return messages instanceof JSONArray ? (JSONArray) messages : null;
            }
        } catch (Exception e) {
            logger.warn("读取合并转发失败, params={}, reason={}", api.getParams(), e.getMessage());
        }
        return null;
    }

    private void appendForwardPayload(
            Bot bot,
            Object payload,
            int depth,
            ForwardReadContext context,
            StringBuilder text
    ) {
        if (payload == null || depth > MAX_FORWARD_DEPTH || context.nodeCount >= MAX_FORWARD_NODES) return;
        Object value = JSON.toJSON(payload);
        if (value instanceof String) {
            String stringValue = ((String) value).trim();
            if ((stringValue.startsWith("[") && stringValue.endsWith("]"))
                    || (stringValue.startsWith("{") && stringValue.endsWith("}"))) {
                try {
                    Object parsed = JSON.parse(stringValue);
                    if (parsed instanceof JSONArray || parsed instanceof JSONObject) {
                        appendForwardPayload(bot, parsed, depth, context, text);
                        return;
                    }
                } catch (Exception ignore) {
                    // 普通文本可能恰好以方括号或花括号开头，按文本继续处理。
                }
            }
            appendText(text, stringValue);
            return;
        }
        if (value instanceof JSONArray) {
            for (Object item : (JSONArray) value) {
                if (context.nodeCount >= MAX_FORWARD_NODES) break;
                appendForwardPayload(bot, item, depth, context, text);
            }
            return;
        }
        if (!(value instanceof JSONObject)) return;

        JSONObject object = (JSONObject) value;
        String type = object.getString("type");
        if (StringUtils.isNotBlank(type)) {
            appendForwardSegment(bot, type, object.get("data"), depth, context, text);
            return;
        }

        context.nodeCount++;
        int originalLength = text.length();
        Object message = object.get("message");
        if (message != null) {
            appendForwardPayload(bot, message, depth, context, text);
        } else if (object.get("content") != null) {
            appendForwardPayload(bot, object.get("content"), depth, context, text);
        }
        if (text.length() == originalLength) {
            appendText(text, object.getString("raw_message"));
        }
    }

    private void appendForwardSegment(
            Bot bot,
            String type,
            Object rawData,
            int depth,
            ForwardReadContext context,
            StringBuilder text
    ) {
        Object convertedData = JSON.toJSON(rawData);
        if (!(convertedData instanceof JSONObject)) return;
        JSONObject data = (JSONObject) convertedData;

        if ("text".equals(type)) {
            appendText(text, data.getString("text"));
        } else if ("markdown".equals(type)) {
            String markdown = data.getString("content");
            appendText(text, StringUtils.isNotBlank(markdown) ? markdown : data.getString("text"));
        } else if ("node".equals(type)) {
            Object content = data.get("content");
            appendForwardPayload(bot, content != null ? content : data.get("message"), depth + 1, context, text);
        } else if ("forward".equals(type)) {
            Object content = data.get("content");
            if (content != null) {
                appendForwardPayload(bot, content, depth + 1, context, text);
            } else {
                String nestedId = data.getString("id");
                appendForwardById(bot, StringUtils.isNotBlank(nestedId)
                        ? nestedId : data.getString("message_id"), depth + 1, context, text);
            }
        }
    }

    private void appendText(StringBuilder target, String value) {
        if (StringUtils.isBlank(value)) return;
        if (target.length() > 0 && target.charAt(target.length() - 1) != '\n') target.append('\n');
        target.append(value.trim()).append('\n');
    }

    /** 解析名字/拥有数量对；重复药材行按数量累加。 */
    Map<String, Long> parseBackpack(String text) {
        if (StringUtils.isBlank(text)) return Collections.emptyMap();
        String normalized = Utils.stripMarkdownLink(text);
        if (StringUtils.isBlank(normalized)) return Collections.emptyMap();

        Map<String, Long> inventory = new LinkedHashMap<>();
        String currentName = null;
        for (String rawLine : normalized.split("\\R")) {
            String line = rawLine.trim();
            Matcher inlineMatcher = INLINE_HERB_COUNT_PATTERN.matcher(line);
            if (inlineMatcher.matches()) {
                String herbName = normalizeHerbName(inlineMatcher.group(1));
                addInventoryCount(inventory, herbName, inlineMatcher.group(2));
                currentName = null;
                continue;
            }

            Matcher nameMatcher = NAME_PATTERN.matcher(line);
            if (nameMatcher.find()) {
                currentName = normalizeHerbName(nameMatcher.group(1));
                continue;
            }
            if (currentName == null) continue;

            Matcher countMatcher = COUNT_PATTERN.matcher(line);
            if (countMatcher.find()) {
                addInventoryCount(inventory, currentName, countMatcher.group(1));
                currentName = null;
            }
        }
        return inventory;
    }

    private void addInventoryCount(Map<String, Long> inventory, String herbName, String countText) {
        if (StringUtils.isBlank(herbName)) return;
        try {
            long count = Long.parseLong(countText);
            inventory.merge(herbName, count, Math::addExact);
        } catch (NumberFormatException | ArithmeticException ignore) {
            // 当前条目无效时继续解析本页的后续条目。
        }
    }

    private String normalizeHerbName(String value) {
        if (value == null) return "";
        return Utils.stripMarkdownLink(value).replaceAll("\\s+", "");
    }

    HerbBackpackMatchResult match(
            Map<String, Long> inventory,
            List<DanRecipeBlueprint> blueprints,
            MatchMode mode,
            int danCount,
            Map<String, Integer> alchemyValues
    ) {
        Map<String, Long> remaining = new HashMap<>(inventory);
        Map<String, Optional<Integer>> latestPriceByName = new HashMap<>();
        Set<String> missingHerbPrices = new HashSet<>();
        Set<String> missingMarketDanPrices = new HashSet<>();
        Set<String> missingAlchemyValues = new HashSet<>();
        List<PricedRecipe> candidates = new ArrayList<>();

        for (DanRecipeBlueprint blueprint : blueprints) {
            if (maxFurnaces(remaining, blueprint) <= 0) continue;

            long cost = 0L;
            boolean missingHerbPrice = false;
            Map<String, Integer> herbUnitPrices = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> herb : blueprint.getHerbRequirements().entrySet()) {
                Optional<Integer> price = latestPrice(herb.getKey(), latestPriceByName);
                if (!price.isPresent()) {
                    missingHerbPrices.add(herb.getKey());
                    missingHerbPrice = true;
                } else {
                    herbUnitPrices.put(herb.getKey(), price.get());
                    cost = Math.addExact(cost, Math.multiplyExact((long) herb.getValue(), price.get().longValue()));
                }
            }

            Integer danUnitPrice = resolveDanUnitPrice(blueprint.getDanName(), mode, alchemyValues,
                    latestPriceByName, missingMarketDanPrices, missingAlchemyValues);
            if (missingHerbPrice || danUnitPrice == null) continue;

            long revenue = calculateRevenue(mode, danUnitPrice, danCount);
            long profit = revenue - cost;
            if (profit > 0L) {
                candidates.add(new PricedRecipe(blueprint, cost, profit, danUnitPrice, herbUnitPrices));
            }
        }

        candidates.sort(RECIPE_ORDER);
        List<HerbBackpackMatchResult.Allocation> allocations = new ArrayList<>();
        long totalFurnaces = 0L;
        long totalProfit = 0L;
        for (PricedRecipe candidate : candidates) {
            long furnaceCount = maxFurnaces(remaining, candidate.blueprint);
            if (furnaceCount <= 0L) continue;

            for (Map.Entry<String, Integer> herb : candidate.blueprint.getHerbRequirements().entrySet()) {
                long consumed = Math.multiplyExact(furnaceCount, herb.getValue().longValue());
                remaining.put(herb.getKey(), remaining.getOrDefault(herb.getKey(), 0L) - consumed);
            }
            HerbBackpackMatchResult.Allocation allocation = new HerbBackpackMatchResult.Allocation(
                    candidate.blueprint, furnaceCount, candidate.unitCost, candidate.unitProfit,
                    candidate.danUnitPrice, candidate.herbUnitPrices);
            allocations.add(allocation);
            totalFurnaces = Math.addExact(totalFurnaces, furnaceCount);
            totalProfit = Math.addExact(totalProfit, allocation.getTotalProfit());
        }

        return new HerbBackpackMatchResult(allocations, totalFurnaces, totalProfit,
                missingHerbPrices, missingMarketDanPrices, missingAlchemyValues);
    }

    private Optional<Integer> latestPrice(String name, Map<String, Optional<Integer>> latestPriceByName) {
        return latestPriceByName.computeIfAbsent(name, key -> {
            ProductPrice latest = productPriceResponse.findFirstByNameOrderByTimeDescIdDesc(key);
            return latest == null ? Optional.empty() : Optional.of(latest.getPrice());
        });
    }

    private Integer resolveDanUnitPrice(
            String danName,
            MatchMode mode,
            Map<String, Integer> alchemyValues,
            Map<String, Optional<Integer>> latestPriceByName,
            Set<String> missingMarketDanPrices,
            Set<String> missingAlchemyValues
    ) {
        if (mode == MatchMode.ALCHEMY) {
            Integer unitValue = alchemyValues.get(danName);
            if (unitValue == null) {
                missingAlchemyValues.add(danName);
                return null;
            }
            return unitValue;
        }

        Optional<Integer> unitPrice = latestPrice(danName, latestPriceByName);
        if (!unitPrice.isPresent()) {
            missingMarketDanPrices.add(danName);
            return null;
        }
        return unitPrice.get();
    }

    private long calculateRevenue(MatchMode mode, int unitPrice, int danCount) {
        if (mode == MatchMode.ALCHEMY) {
            return Math.multiplyExact((long) unitPrice, (long) danCount);
        }
        double netRate = 1D - Utils.calculateFeeRate(unitPrice);
        return (long) (unitPrice * netRate * danCount);
    }

    private long maxFurnaces(Map<String, Long> inventory, DanRecipeBlueprint blueprint) {
        long maximum = Long.MAX_VALUE;
        for (Map.Entry<String, Integer> herb : blueprint.getHerbRequirements().entrySet()) {
            if (herb.getValue() <= 0) return 0L;
            maximum = Math.min(maximum,
                    inventory.getOrDefault(herb.getKey(), 0L) / herb.getValue().longValue());
        }
        return maximum == Long.MAX_VALUE ? 0L : maximum;
    }

    private void sendResult(
            Group group,
            Bot bot,
            MatchCommand command,
            HerbBackpackMatchResult result,
            MatchRequester requester
    ) {
        String requesterName = requester == null || !requester.known ? "未知" : requester.displayName;
        String header = "药材背包匹配：" + command.mode.getDisplayName() + "｜每炉" + command.danCount
                + "丹｜按单炉净利润优先\n"
                + "请求者：" + requesterName + "\n\n"
                + "总炉数：" + result.getTotalFurnaces() + "炉  总利润："
                + formatWanAmount(result.getTotalProfit()) + "\n\n"
                + PRICE_REFRESH_NOTE;

        StringBuilder details = new StringBuilder();
        if (result.getAllocations().isEmpty()) {
            details.append("没有匹配到库存足够且单炉净利润为正的丹方。");
        } else {
            for (HerbBackpackMatchResult.Allocation allocation : result.getAllocations()) {
                DanRecipeBlueprint b = allocation.getBlueprint();
                String priceLabel = command.mode == MatchMode.ALCHEMY ? "炼金价" : "坊市价";
                if (details.length() > 0) details.append("\n\n");
                details.append("配方主药").append(b.getMainName()).append(b.getMainCount())
                        .append("药引").append(b.getLeadName()).append(b.getLeadCount())
                        .append("辅药").append(b.getAssistName()).append(b.getAssistCount()).append(FURNACE)
                        .append("\n可炼制：").append(allocation.getFurnaceCount()).append("炉  利润：")
                        .append(formatWanAmount(allocation.getTotalProfit()))
                        .append("\n丹药：").append(b.getDanName()).append("  ").append(priceLabel)
                        .append(formatWanAmount(allocation.getDanUnitPrice())).append("\n")
                        .append(formatHerbPrice("主药", b.getMainName(), b.getMainCount(), allocation)).append("\n")
                        .append(formatHerbPrice("药引", b.getLeadName(), b.getLeadCount(), allocation)).append("\n")
                        .append(formatHerbPrice("辅药", b.getAssistName(), b.getAssistCount(), allocation));
            }
        }

        StringBuilder footer = new StringBuilder();
        appendMissing(footer, "缺少药材价", result.getMissingHerbPrices());
        appendMissing(footer, "缺少坊市丹价", result.getMissingMarketDanPrices());
        appendMissing(footer, "缺少固定炼金值", result.getMissingAlchemyValues());
        if (footer.length() > 0) {
            if (details.length() > 0) details.append("\n\n");
            details.append(footer);
        }

        String senderId = String.valueOf(bot.getBotId());
        List<ForwardNodeMessage> nodes = new ArrayList<>(2);
        nodes.add(new ForwardNodeMessage(senderId, FORWARD_NAME, new MessageChain().text(header)));
        nodes.add(new ForwardNodeMessage(senderId, FORWARD_NAME,
                new MessageChain().text(details.toString())));
        group.sendGroupForwardMessage(nodes);
    }

    private String formatHerbPrice(
            String role,
            String herbName,
            int count,
            HerbBackpackMatchResult.Allocation allocation
    ) {
        return role + herbName + "*" + count + "（"
                + formatWanAmount(allocation.getHerbUnitPrice(herbName)) + "）";
    }

    /** 输入金额单位为万；达到一亿后改用亿，四舍五入并最多保留两位小数。 */
    static String formatWanAmount(long amountWan) {
        if (amountWan < 10000L) {
            return amountWan + "万";
        }
        return BigDecimal.valueOf(amountWan)
                .movePointLeft(4)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + "亿";
    }

    private void appendMissing(StringBuilder text, String label, Set<String> names) {
        if (names.isEmpty()) return;
        StringJoiner joiner = new StringJoiner("、");
        names.forEach(joiner::add);
        if (text.length() > 0) text.append('\n');
        text.append(label).append("：").append(joiner);
    }

    static final class MatchRequester {
        private final boolean known;
        private final String displayName;
        private final long userId;

        private MatchRequester(boolean known, String displayName, long userId) {
            this.known = known;
            this.displayName = displayName;
            this.userId = userId;
        }

        static MatchRequester from(Member member) {
            if (member == null) return unknown();
            String displayName = StringUtils.defaultIfBlank(member.getCard(), member.getNickname());
            displayName = StringUtils.defaultIfBlank(displayName, String.valueOf(member.getUserId()))
                    .replaceAll("[\\r\\n]+", " ")
                    .trim();
            return new MatchRequester(true, displayName, member.getUserId());
        }

        static MatchRequester of(String displayName, long userId) {
            String safeName = StringUtils.defaultIfBlank(displayName, String.valueOf(userId))
                    .replaceAll("[\\r\\n]+", " ")
                    .trim();
            return new MatchRequester(true, safeName, userId);
        }

        static MatchRequester unknown() {
            return new MatchRequester(false, "未知", 0L);
        }
    }

    enum MatchMode {
        ALCHEMY("炼金丹"),
        MARKET("坊市丹");

        private final String displayName;

        MatchMode(String displayName) {
            this.displayName = displayName;
        }

        String getDisplayName() {
            return displayName;
        }
    }

    static final class MatchCommand {
        private final boolean valid;
        private final MatchMode mode;
        private final int danCount;
        private final String error;

        private MatchCommand(boolean valid, MatchMode mode, int danCount, String error) {
            this.valid = valid;
            this.mode = mode;
            this.danCount = danCount;
            this.error = error;
        }

        static MatchCommand valid(MatchMode mode, int danCount) {
            return new MatchCommand(true, mode, danCount, "");
        }

        static MatchCommand invalid(String error) {
            return new MatchCommand(false, null, 0, error);
        }

        boolean isValid() {
            return valid;
        }

        MatchMode getMode() {
            return mode;
        }

        int getDanCount() {
            return danCount;
        }
    }

    private static final class PricedRecipe {
        private final DanRecipeBlueprint blueprint;
        private final long unitCost;
        private final long unitProfit;
        private final int danUnitPrice;
        private final Map<String, Integer> herbUnitPrices;

        private PricedRecipe(
                DanRecipeBlueprint blueprint,
                long unitCost,
                long unitProfit,
                int danUnitPrice,
                Map<String, Integer> herbUnitPrices
        ) {
            this.blueprint = blueprint;
            this.unitCost = unitCost;
            this.unitProfit = unitProfit;
            this.danUnitPrice = danUnitPrice;
            this.herbUnitPrices = new LinkedHashMap<>(herbUnitPrices);
        }

        long getUnitCost() {
            return unitCost;
        }

        long getUnitProfit() {
            return unitProfit;
        }
    }

    private static final class ForwardReadContext {
        private final Set<String> visitedIds = new HashSet<>();
        private int nodeCount;
    }
}
