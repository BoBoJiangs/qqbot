package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.ForwardNodeMessage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.Dan;
import top.sshh.qqbot.data.Herb;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.ProductPriceResponse;
import top.sshh.qqbot.service.utils.Utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static top.sshh.qqbot.constant.Constant.MAKE_DAN_SET;
import static top.sshh.qqbot.constant.Constant.targetDir;

@Component
public class DanRecipeQueryService {
    private static final Logger logger = LoggerFactory.getLogger(DanRecipeQueryService.class);
    private static final int DEFAULT_DAN_NUM = 6;
    private static final int OUTPUT_LIMIT = 20;
    private static final long PRICE_CACHE_TTL_MS = 60_000L;

    private static final Comparator<RecipeCandidate> RECIPE_RANKING = Comparator
            .comparingInt(RecipeCandidate::getProfit).reversed()
            .thenComparingInt(RecipeCandidate::getCost)
            .thenComparingInt(RecipeCandidate::getTotalCount);

    private static final Pattern TRAILING_NUMBER_PATTERN = Pattern.compile("^(.*?)(\\d+)$");

    @Autowired
    private ProductPriceResponse productPriceResponse;

    private volatile Cache cache = Cache.empty();
    private final Map<Long, BotHerbPriceCache> botHerbPriceCacheMap = new ConcurrentHashMap<>();
    private final Map<String, SkeletonCacheEntry> skeletonCacheMap = new ConcurrentHashMap<>();
    private final Map<String, CachedInt> productPriceCache = new ConcurrentHashMap<>();

    List<String> generateRecipeSignaturesForTest(String danName, int danNum) throws IOException {
        ensureLoaded();
        Dan dan = cache.danByName.get(danName);
        if (dan == null) return Collections.emptyList();
        List<RecipeSkeleton> skeletons = getOrBuildSkeletons(dan);
        return skeletons.stream().map(RecipeSkeleton::signature).collect(Collectors.toList());
    }

    String generateRecipeTextForTest(String danName, int danNum, int limit) throws IOException {
        ensureLoaded();
        Dan dan = cache.danByName.get(danName);
        if (dan == null) return "";

        int realLimit = Math.max(1, limit);
        PriceMode mode = resolvePriceMode(dan.name, QueryMode.AUTO);
        List<RecipeCandidate> top = computeTopCandidates(dan, BotHerbPriceCache.empty(0), danNum, realLimit, mode);
        if (top.isEmpty()) return "";

        String header = buildHeader(dan, danNum, mode);
        String body = buildBody(top);
        return header + "\n\n" + body;
    }

    public void handleQuery(String message, Group group, Bot bot) {
        try {
            ensureLoaded();
            Query query = parseQuery(message);
            if (query == null || StringUtils.isBlank(query.danName)) {
                group.sendMessage(new MessageChain().text("用法：查丹方 <丹名> [成丹数] [坊市丹/炼金丹]（不填成丹数默认6）"));
                return;
            }

            Dan dan = cache.danByName.get(query.danName);
            if (dan == null) {
                group.sendMessage(new MessageChain().text("未找到丹药：“" + query.danName + "”。请检查丹名是否在丹药列表中。"));
                return;
            }

            PriceMode mode = resolvePriceMode(dan.name, query.mode);
            BotHerbPriceCache herbPriceCache = getOrLoadBotHerbPriceCache(bot.getBotId());
            List<RecipeCandidate> topCandidates = computeTopCandidates(dan, herbPriceCache, query.danNum, OUTPUT_LIMIT, mode);
            if (topCandidates.isEmpty()) {
                group.sendMessage(new MessageChain().text("未能生成可用配方：丹药=" + query.danName + "。可能是药材属性/价格数据不完整。"));
                return;
            }

            int limit = Math.min(OUTPUT_LIMIT, topCandidates.size());

            String header = buildHeader(dan, query.danNum, mode);
            String body = buildBody(topCandidates);
            String footer = "模式：" + (mode == PriceMode.MARKET ? "坊市丹" : "炼金丹") + "（可在命令末尾加“坊市丹/炼金丹”强制指定）\n"
                    + "已按收益从高到低排序，仅输出前 " + limit + " 条。\n"
                    + "药材价格仅供参考，以实际坊市价格为准！！";

            List<ForwardNodeMessage> forwardNodes = new ArrayList<>();
            forwardNodes.add(new ForwardNodeMessage(String.valueOf(bot.getBotId()), "丹方查询助手", new MessageChain().text(header + "\n\n" + body)));
            forwardNodes.add(new ForwardNodeMessage(String.valueOf(bot.getBotId()), "丹方查询助手", new MessageChain().text(footer)));
            group.sendGroupForwardMessage(forwardNodes);
        } catch (Exception e) {
            logger.error("查丹方处理失败: {}", e.getMessage(), e);
            group.sendMessage(new MessageChain().text("查丹方处理失败：" + e.getMessage()));
        }
    }

    private void ensureLoaded() throws IOException {
        Cache current = this.cache;
        if (!current.isEmpty() && !current.isStale()) return;
        this.cache = Cache.load();
        skeletonCacheMap.clear();
        productPriceCache.clear();
    }

    private Query parseQuery(String message) {
        if (message == null) return null;
        int idx = message.indexOf("查丹方");
        if (idx < 0) return null;

        String args = message.substring(idx + "查丹方".length()).trim();
        if (StringUtils.isBlank(args)) return new Query("", DEFAULT_DAN_NUM, QueryMode.AUTO);

        QueryMode mode = QueryMode.AUTO;
        int marketIdx = args.lastIndexOf("坊市丹");
        int alchemyIdx = args.lastIndexOf("炼金丹");
        if (marketIdx >= 0 || alchemyIdx >= 0) {
            mode = marketIdx > alchemyIdx ? QueryMode.MARKET : QueryMode.ALCHEMY;
            args = args.replace("坊市丹", "").replace("炼金丹", "").trim();
        }
        if (StringUtils.isBlank(args)) return new Query("", DEFAULT_DAN_NUM, mode);

        String[] tokens = args.split("\\s+");
        if (tokens.length == 1) {
            String token = tokens[0].trim();
            Matcher m = TRAILING_NUMBER_PATTERN.matcher(token);
            if (m.find()) {
                String possibleName = m.group(1);
                String possibleNum = m.group(2);
                if (StringUtils.isNotBlank(possibleName) && cache.danByName.containsKey(possibleName)) {
                    return new Query(possibleName, safeParseInt(possibleNum, DEFAULT_DAN_NUM), mode);
                }
            }
            return new Query(token, DEFAULT_DAN_NUM, mode);
        }

        String last = tokens[tokens.length - 1];
        if (last.matches("\\d+")) {
            int danNum = safeParseInt(last, DEFAULT_DAN_NUM);
            String danName = String.join(" ", java.util.Arrays.copyOf(tokens, tokens.length - 1)).trim();
            return new Query(danName, danNum, mode);
        }
        return new Query(args, DEFAULT_DAN_NUM, mode);
    }

    private int safeParseInt(String text, int defaultValue) {
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String buildHeader(Dan dan, int danNum, PriceMode mode) {
        int unitPrice = resolveDanUnitPrice(dan.name, mode);
        String modeText = mode == PriceMode.MARKET ? "坊市" : "炼金";
        return dan.name + "（" + modeText + "单价：" + unitPrice + "w/个）\n成丹数：" + danNum + "\n需求：" + formatRequirements(dan);
    }

    private String formatRequirements(Dan dan) {
        if (dan == null || dan.requirements == null || dan.requirements.isEmpty()) return "无";
        return dan.requirements.entrySet()
                .stream()
                .map(e -> e.getKey() + e.getValue())
                .collect(Collectors.joining(" "));
    }

    private String buildBody(List<RecipeCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            RecipeCandidate c = candidates.get(i);
            sb.append("配方").append(i + 1).append(" (收益：").append(c.getProfit()).append("w)\n");
            sb.append("配方主药").append(c.mainName).append(c.mainCount)
                    .append("药引").append(c.leadName).append(c.leadCount)
                    .append("辅药").append(c.assistName).append(c.assistCount).append("\n");
            sb.append("主药:").append(c.mainUnitPrice).append("w")
                    .append("药引:").append(c.leadUnitPrice).append("w")
                    .append("辅药:").append(c.assistUnitPrice).append("w").append("\n");
            sb.append("成本:").append(c.getCost()).append("w 收益:").append(c.getProfit()).append("w\n\n");
        }
        return sb.toString();
    }

    private List<RecipeCandidate> computeTopCandidates(Dan dan, BotHerbPriceCache herbPriceCache, int danNum, int limit, PriceMode mode) {
        if (dan == null) return Collections.emptyList();
        int realLimit = Math.max(1, limit);

        List<RecipeSkeleton> skeletons = getOrBuildSkeletons(dan);
        if (skeletons.isEmpty()) return Collections.emptyList();

        int revenue = resolveDanRevenue(dan.name, mode, danNum);

        PriorityQueue<RecipeCandidate> heap = new PriorityQueue<>(realLimit, RECIPE_RANKING.reversed());
        for (RecipeSkeleton s : skeletons) {
            int mainUnitPrice = herbPriceCache.getPrice(s.mainName).orElseGet(() -> resolveMarketPrice(s.mainName));
            int leadUnitPrice = herbPriceCache.getPrice(s.leadName).orElseGet(() -> resolveMarketPrice(s.leadName));
            int assistUnitPrice = herbPriceCache.getPrice(s.assistName).orElseGet(() -> resolveMarketPrice(s.assistName));

            int cost = s.mainCount * mainUnitPrice + s.leadCount * leadUnitPrice + s.assistCount * assistUnitPrice;
            int profit = revenue - cost;

            RecipeCandidate candidate = new RecipeCandidate(dan.name,
                    s.mainName, s.mainCount, mainUnitPrice,
                    s.leadName, s.leadCount, leadUnitPrice,
                    s.assistName, s.assistCount, assistUnitPrice,
                    cost, revenue, profit);

            if (heap.size() < realLimit) {
                heap.offer(candidate);
            } else if (RECIPE_RANKING.compare(candidate, heap.peek()) < 0) {
                heap.poll();
                heap.offer(candidate);
            }
        }

        List<RecipeCandidate> result = new ArrayList<>(heap);
        result.sort(RECIPE_RANKING);
        return result;
    }

    private List<RecipeCandidate> generateCandidates(Dan dan, BotHerbPriceCache herbPriceCache, int danNum) {
        if (dan == null || dan.requirements == null || dan.requirements.isEmpty()) return Collections.emptyList();

        List<Map.Entry<String, Integer>> reqList = new ArrayList<>(dan.requirements.entrySet());
        if (reqList.size() != 2) {
            logger.warn("暂不支持需求属性数量!=2的丹药: {} requirements={}", dan.name, dan.requirements);
            return Collections.emptyList();
        }

        Map.Entry<String, Integer> r1 = reqList.get(0);
        Map.Entry<String, Integer> r2 = reqList.get(1);

        List<RecipeCandidate> result = new ArrayList<>();
        result.addAll(generateCandidatesForAssignment(dan, herbPriceCache, danNum, r1.getKey(), r1.getValue(), r2.getKey(), r2.getValue()));
        result.addAll(generateCandidatesForAssignment(dan, herbPriceCache, danNum, r2.getKey(), r2.getValue(), r1.getKey(), r1.getValue()));

        return result.stream()
                .collect(Collectors.toMap(RecipeCandidate::signature, c -> c, (a, b) -> a.getProfit() >= b.getProfit() ? a : b))
                .values()
                .stream()
                .collect(Collectors.toList());
    }

    private List<RecipeSkeleton> getOrBuildSkeletons(Dan dan) {
        SkeletonCacheEntry current = skeletonCacheMap.get(dan.name);
        if (current != null && Objects.equals(current.cacheKey, cache.cacheKey)) {
            return current.skeletons;
        }

        List<RecipeSkeleton> skeletons = buildSkeletons(dan);
        skeletonCacheMap.put(dan.name, new SkeletonCacheEntry(cache.cacheKey, skeletons));
        return skeletons;
    }

    private List<RecipeSkeleton> buildSkeletons(Dan dan) {
        if (dan == null || dan.requirements == null || dan.requirements.size() != 2) return Collections.emptyList();
        List<Map.Entry<String, Integer>> reqList = new ArrayList<>(dan.requirements.entrySet());
        Map.Entry<String, Integer> r1 = reqList.get(0);
        Map.Entry<String, Integer> r2 = reqList.get(1);

        List<RecipeSkeleton> all = new ArrayList<>();
        all.addAll(buildSkeletonsForAssignment(dan, r1.getKey(), r1.getValue(), r2.getKey(), r2.getValue()));
        all.addAll(buildSkeletonsForAssignment(dan, r2.getKey(), r2.getValue(), r1.getKey(), r1.getValue()));

        return all.stream()
                .collect(Collectors.toMap(RecipeSkeleton::signature, s -> s, (a, b) -> a.totalCount <= b.totalCount ? a : b))
                .values()
                .stream()
                .collect(Collectors.toList());
    }

    private List<RecipeSkeleton> buildSkeletonsForAssignment(
            Dan dan,
            String mainNeedType,
            int mainNeedValue,
            String assistNeedType,
            int assistNeedValue
    ) {
        if (mainNeedValue <= 0 || assistNeedValue <= 0) return Collections.emptyList();
        List<Herb> mains = cache.mainsByAttr2Type.getOrDefault(mainNeedType, Collections.emptyList());
        List<Herb> assists = cache.assistsByAssistAttrType.getOrDefault(assistNeedType, Collections.emptyList());
        if (mains.isEmpty() || assists.isEmpty()) return Collections.emptyList();

        int nextAssistThreshold = cache.nextDanThresholdForAssist(mainNeedType, mainNeedValue, assistNeedType, assistNeedValue);
        int nextMainThreshold = cache.nextDanThresholdForMain(mainNeedType, mainNeedValue, assistNeedType, assistNeedValue);
        long maxMainProvided = nextMainThreshold == Integer.MAX_VALUE ? Long.MAX_VALUE : (long) nextMainThreshold * 2L;

        List<RecipeSkeleton> list = new ArrayList<>();
        for (Herb main : mains) {
            if (main.mainAttr2Value <= 0) continue;
            int mainCount = (int) Math.ceil((double) mainNeedValue / (double) main.mainAttr2Value);
            if (mainCount <= 0) continue;
            long mainProvided = (long) mainCount * (long) main.mainAttr2Value;
            if (mainProvided > maxMainProvided) continue;

            LeadRequirement leadReq = leadRequirementForMain(main);
            List<Herb> leads = cache.leadsByLeadAttrType.getOrDefault(leadReq.leadAttrType, Collections.emptyList());
            if ("性平".equals(leadReq.leadAttrType) && !cache.pingLeadNames.isEmpty()) {
                leads = leads.stream().filter(h -> cache.pingLeadNames.contains(h.name)).collect(Collectors.toList());
            }
            if (leads.isEmpty()) continue;

            for (Herb lead : leads) {
                if (lead.leadAttrValue <= 0) continue;
                if (Objects.equals(main.name, lead.name)) continue;
                int leadCount = computeLeadCount(main, mainCount, lead);
                if (leadCount <= 0) continue;
                if (mainCount + leadCount >= 100) continue;

                for (Herb assist : assists) {
                    if (assist.assistAttrValue <= 0) continue;
                    if (Objects.equals(main.name, assist.name) || Objects.equals(lead.name, assist.name)) continue;
                    if (assist.assistAttrValue / assistNeedValue >= 2) continue;

                    int assistCount = (int) Math.ceil((double) assistNeedValue / (double) assist.assistAttrValue);
                    if (assistCount <= 0) continue;
                    if (mainCount + leadCount + assistCount >= 100) continue;

                    int assistProvided = assistCount * assist.assistAttrValue;
                    if (assistProvided >= nextAssistThreshold) continue;

                    list.add(new RecipeSkeleton(
                            dan.name,
                            main.name, mainCount,
                            lead.name, leadCount,
                            assist.name, assistCount
                    ));
                }
            }
        }
        return list;
    }

    private List<RecipeCandidate> generateCandidatesForAssignment(
            Dan dan,
            BotHerbPriceCache herbPriceCache,
            int danNum,
            String mainNeedType,
            int mainNeedValue,
            String assistNeedType,
            int assistNeedValue
    ) {
        if (mainNeedValue <= 0 || assistNeedValue <= 0) return Collections.emptyList();
        List<Herb> mains = cache.mainsByAttr2Type.getOrDefault(mainNeedType, Collections.emptyList());
        List<Herb> assists = cache.assistsByAssistAttrType.getOrDefault(assistNeedType, Collections.emptyList());
        if (mains.isEmpty() || assists.isEmpty()) return Collections.emptyList();

        int nextAssistThreshold = cache.nextDanThresholdForAssist(mainNeedType, mainNeedValue, assistNeedType, assistNeedValue);
        int nextMainThreshold = cache.nextDanThresholdForMain(mainNeedType, mainNeedValue, assistNeedType, assistNeedValue);
        long maxMainProvided = nextMainThreshold == Integer.MAX_VALUE ? Long.MAX_VALUE : (long) nextMainThreshold * 2L;

        List<RecipeCandidate> list = new ArrayList<>();
        for (Herb main : mains) {
            if (main.mainAttr2Value <= 0) continue;
            int mainCount = (int) Math.ceil((double) mainNeedValue / (double) main.mainAttr2Value);
            if (mainCount <= 0) continue;
            long mainProvided = (long) mainCount * (long) main.mainAttr2Value;
            if (mainProvided > maxMainProvided) continue;

            LeadRequirement leadReq = leadRequirementForMain(main);
            List<Herb> leads = cache.leadsByLeadAttrType.getOrDefault(leadReq.leadAttrType, Collections.emptyList());
            if ("性平".equals(leadReq.leadAttrType) && !cache.pingLeadNames.isEmpty()) {
                leads = leads.stream().filter(h -> cache.pingLeadNames.contains(h.name)).collect(Collectors.toList());
            }
            if (leads.isEmpty()) continue;

            for (Herb lead : leads) {
                if (lead.leadAttrValue <= 0) continue;
                if (Objects.equals(main.name, lead.name)) continue;
                int leadCount = computeLeadCount(main, mainCount, lead);
                if (leadCount <= 0) continue;
                if (mainCount + leadCount >= 100) continue;

                for (Herb assist : assists) {
                    if (assist.assistAttrValue <= 0) continue;
                    if (Objects.equals(main.name, assist.name) || Objects.equals(lead.name, assist.name)) continue;
                    if (assist.assistAttrValue / assistNeedValue >= 2) continue;

                    int assistCount = (int) Math.ceil((double) assistNeedValue / (double) assist.assistAttrValue);
                    if (assistCount <= 0) continue;
                    if (mainCount + leadCount + assistCount >= 100) continue;
                    int assistProvided = assistCount * assist.assistAttrValue;
                    if (assistProvided >= nextAssistThreshold) continue;

                    int mainUnitPrice = herbPriceCache.getPrice(main.name).orElseGet(() -> resolveMarketPrice(main.name));
                    int leadUnitPrice = herbPriceCache.getPrice(lead.name).orElseGet(() -> resolveMarketPrice(lead.name));
                    int assistUnitPrice = herbPriceCache.getPrice(assist.name).orElseGet(() -> resolveMarketPrice(assist.name));

                    int cost = mainCount * mainUnitPrice + leadCount * leadUnitPrice + assistCount * assistUnitPrice;
                    PriceMode mode = resolvePriceMode(dan.name, QueryMode.AUTO);
                    int revenue = resolveDanRevenue(dan.name, mode, danNum);
                    int profit = revenue - cost;

                    list.add(new RecipeCandidate(dan.name, main.name, mainCount, mainUnitPrice, lead.name, leadCount, leadUnitPrice, assist.name, assistCount, assistUnitPrice, cost, revenue, profit));
                }
            }
        }

        return list;
    }

    private LeadRequirement leadRequirementForMain(Herb main) {
        String type = main.mainAttr1Type;
        if (type == null) return new LeadRequirement("性平");
        if (type.startsWith("性寒")) return new LeadRequirement("性热");
        if (type.startsWith("性热")) return new LeadRequirement("性寒");
        return new LeadRequirement("性平");
    }

    private int computeLeadCount(Herb main, int mainCount, Herb lead) {
        if (!checkBalance(main, lead)) return 0;
        if (main.mainAttr1Type != null && main.mainAttr1Type.equals("性平")) return 1;
        int leadNumber = main.mainAttr1Value * mainCount;
        if (leadNumber < lead.leadAttrValue) return 0;
        int leadCount = leadNumber / lead.leadAttrValue;
        if (leadNumber != leadCount * lead.leadAttrValue) return 0;
        return leadCount;
    }

    private boolean checkBalance(Herb main, Herb lead) {
        if (main == null || lead == null) return false;
        if (main.mainAttr1Type == null || lead.leadAttrType == null) return false;
        return (main.mainAttr1Type.startsWith("性寒") && lead.leadAttrType.startsWith("性热")) ||
                (main.mainAttr1Type.startsWith("性热") && lead.leadAttrType.startsWith("性寒")) ||
                (main.mainAttr1Type.startsWith("性平") && lead.leadAttrType.startsWith("性平"));
    }

    private PriceMode resolvePriceMode(String danName, QueryMode queryMode) {
        if (queryMode == QueryMode.MARKET) return PriceMode.MARKET;
        if (queryMode == QueryMode.ALCHEMY) return PriceMode.ALCHEMY;
        return MAKE_DAN_SET.contains(danName) ? PriceMode.MARKET : PriceMode.ALCHEMY;
    }

    private int resolveDanUnitPrice(String danName, PriceMode mode) {
        if (mode == PriceMode.ALCHEMY) {
            return cache.danAlchemyValues.getOrDefault(danName, 0);
        }

        Integer cached = getCachedPrice(danName);
        if (cached != null) return cached;

        ProductPrice danPrice = productPriceResponse.getFirstByNameOrderByTimeDesc(danName);
        int price = danPrice != null ? danPrice.getPrice() : cache.danMarketValues.getOrDefault(danName, 0);
        putCachedPrice(danName, price);
        return price;
    }

    private int resolveDanRevenue(String danName, PriceMode mode, int danNum) {
        int unitPrice = resolveDanUnitPrice(danName, mode);
        if (mode == PriceMode.ALCHEMY) return unitPrice * danNum;

        double feeRate = 1 - Utils.calculateFeeRate(unitPrice);
        return (int) (unitPrice * feeRate * danNum);
    }

    private int resolveMarketPrice(String productName) {
        Integer cached = getCachedPrice(productName);
        if (cached != null) return cached;

        ProductPrice price = productPriceResponse.getFirstByNameOrderByTimeDesc(productName);
        int v = price == null ? 0 : price.getPrice();
        putCachedPrice(productName, v);
        return v;
    }

    private Integer getCachedPrice(String name) {
        CachedInt cached = productPriceCache.get(name);
        if (cached == null) return null;
        if (System.currentTimeMillis() > cached.expiresAtMs) {
            productPriceCache.remove(name);
            return null;
        }
        return cached.value;
    }

    private void putCachedPrice(String name, int value) {
        productPriceCache.put(name, new CachedInt(value, System.currentTimeMillis() + PRICE_CACHE_TTL_MS));
    }

    private BotHerbPriceCache getOrLoadBotHerbPriceCache(long botId) {
        return botHerbPriceCacheMap.compute(botId, (k, current) -> {
            try {
                if (current != null && !current.isStale()) return current;
                return BotHerbPriceCache.load(botId);
            } catch (Exception e) {
                logger.warn("读取药材价格.txt失败 botId={} err={}", botId, e.getMessage());
                return BotHerbPriceCache.empty(botId);
            }
        });
    }

    private enum PriceMode {
        MARKET, ALCHEMY
    }

    private enum QueryMode {
        AUTO, MARKET, ALCHEMY
    }

    private static final class LeadRequirement {
        private final String leadAttrType;

        private LeadRequirement(String leadAttrType) {
            this.leadAttrType = leadAttrType;
        }
    }

    private static final class Query {
        private final String danName;
        private final int danNum;
        private final QueryMode mode;

        private Query(String danName, int danNum, QueryMode mode) {
            this.danName = danName;
            this.danNum = danNum;
            this.mode = mode == null ? QueryMode.AUTO : mode;
        }
    }

    private static final class RecipeCandidate {
        private final String danName;
        private final String mainName;
        private final int mainCount;
        private final int mainUnitPrice;
        private final String leadName;
        private final int leadCount;
        private final int leadUnitPrice;
        private final String assistName;
        private final int assistCount;
        private final int assistUnitPrice;
        private final int cost;
        private final int revenue;
        private final int profit;

        private RecipeCandidate(
                String danName,
                String mainName,
                int mainCount,
                int mainUnitPrice,
                String leadName,
                int leadCount,
                int leadUnitPrice,
                String assistName,
                int assistCount,
                int assistUnitPrice,
                int cost,
                int revenue,
                int profit
        ) {
            this.danName = danName;
            this.mainName = mainName;
            this.mainCount = mainCount;
            this.mainUnitPrice = mainUnitPrice;
            this.leadName = leadName;
            this.leadCount = leadCount;
            this.leadUnitPrice = leadUnitPrice;
            this.assistName = assistName;
            this.assistCount = assistCount;
            this.assistUnitPrice = assistUnitPrice;
            this.cost = cost;
            this.revenue = revenue;
            this.profit = profit;
        }

        int getCost() {
            return cost;
        }

        int getProfit() {
            return profit;
        }

        int getTotalCount() {
            return mainCount + leadCount + assistCount;
        }

        String signature() {
            return String.join("|",
                    danName,
                    mainName, String.valueOf(mainCount),
                    leadName, String.valueOf(leadCount),
                    assistName, String.valueOf(assistCount));
        }
    }

    private static final class RecipeSkeleton {
        private final String danName;
        private final String mainName;
        private final int mainCount;
        private final String leadName;
        private final int leadCount;
        private final String assistName;
        private final int assistCount;
        private final int totalCount;

        private RecipeSkeleton(
                String danName,
                String mainName,
                int mainCount,
                String leadName,
                int leadCount,
                String assistName,
                int assistCount
        ) {
            this.danName = danName;
            this.mainName = mainName;
            this.mainCount = mainCount;
            this.leadName = leadName;
            this.leadCount = leadCount;
            this.assistName = assistName;
            this.assistCount = assistCount;
            this.totalCount = mainCount + leadCount + assistCount;
        }

        String signature() {
            return String.join("|",
                    danName,
                    mainName, String.valueOf(mainCount),
                    leadName, String.valueOf(leadCount),
                    assistName, String.valueOf(assistCount));
        }
    }

    private static final class SkeletonCacheEntry {
        private final String cacheKey;
        private final List<RecipeSkeleton> skeletons;

        private SkeletonCacheEntry(String cacheKey, List<RecipeSkeleton> skeletons) {
            this.cacheKey = cacheKey;
            this.skeletons = skeletons;
        }
    }

    private static final class CachedInt {
        private final int value;
        private final long expiresAtMs;

        private CachedInt(int value, long expiresAtMs) {
            this.value = value;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static final class Cache {
        private final String cacheKey;
        private final Map<String, Dan> danByName;
        private final Map<String, Integer> danMarketValues;
        private final Map<String, Integer> danAlchemyValues;
        private final Map<String, List<Herb>> mainsByAttr2Type;
        private final Map<String, List<Herb>> assistsByAssistAttrType;
        private final Map<String, List<Herb>> leadsByLeadAttrType;
        private final Map<String, List<DanProfile>> danProfilesByTypePairKey;
        private final Set<String> pingLeadNames;
        private final Map<Path, FileTime> fileTimes;

        private Cache(
                String cacheKey,
                Map<String, Dan> danByName,
                Map<String, Integer> danMarketValues,
                Map<String, Integer> danAlchemyValues,
                Map<String, List<Herb>> mainsByAttr2Type,
                Map<String, List<Herb>> assistsByAssistAttrType,
                Map<String, List<Herb>> leadsByLeadAttrType,
                Map<String, List<DanProfile>> danProfilesByTypePairKey,
                Set<String> pingLeadNames,
                Map<Path, FileTime> fileTimes
        ) {
            this.cacheKey = cacheKey;
            this.danByName = danByName;
            this.danMarketValues = danMarketValues;
            this.danAlchemyValues = danAlchemyValues;
            this.mainsByAttr2Type = mainsByAttr2Type;
            this.assistsByAssistAttrType = assistsByAssistAttrType;
            this.leadsByLeadAttrType = leadsByLeadAttrType;
            this.danProfilesByTypePairKey = danProfilesByTypePairKey;
            this.pingLeadNames = pingLeadNames;
            this.fileTimes = fileTimes;
        }

        static Cache empty() {
            return new Cache("",
                    Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptySet(),
                    Collections.emptyMap());
        }

        boolean isEmpty() {
            return danByName.isEmpty();
        }

        boolean isStale() throws IOException {
            for (Map.Entry<Path, FileTime> e : fileTimes.entrySet()) {
                Path p = e.getKey();
                if (!Files.exists(p)) continue;
                FileTime current = Files.getLastModifiedTime(p);
                if (!Objects.equals(current, e.getValue())) return true;
            }
            return false;
        }

        static Cache load() throws IOException {
            Path elixirPath = Paths.get(targetDir, "properties", "elixirproperties.txt");
            Path danMarketPath = Paths.get(targetDir, "properties", "丹药坊市价值.txt");
            Path danAlchemyPath = Paths.get(targetDir, "properties", "丹药炼金价值.txt");
            Path pingPath = Paths.get(targetDir, "properties", "性平.txt");

            List<Herb> herbs = new ArrayList<>();
            Map<String, Dan> danByName = new HashMap<>();
            if (Files.exists(elixirPath)) {
                boolean isHerbSection = false;
                try (BufferedReader br = Files.newBufferedReader(elixirPath, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("-----药材列表-----")) {
                            isHerbSection = true;
                            continue;
                        }
                        if (line.startsWith("-----丹药列表-----")) {
                            isHerbSection = false;
                            continue;
                        }
                        if (StringUtils.isBlank(line)) continue;

                        String[] parts = line.split("\t");
                        if (isHerbSection) {
                            if (parts.length < 5) {
                                logger.warn("跳过异常药材行（字段不足）: {}", line);
                                continue;
                            }
                            herbs.add(new Herb(parts));
                        } else {
                            if (parts.length < 2) {
                                logger.warn("跳过异常丹药行（字段不足）: {}", line);
                                continue;
                            }
                            Dan dan = new Dan(parts);
                            danByName.put(dan.name, dan);
                        }
                    }
                }
            } else {
                logger.warn("未找到 elixirproperties.txt: {}", elixirPath.toAbsolutePath());
            }

            Map<String, Integer> marketValues = new LinkedHashMap<>();
            loadSimpleValueFile(danMarketPath, marketValues);
            Map<String, Integer> alchemyValues = new LinkedHashMap<>();
            loadSimpleValueFile(danAlchemyPath, alchemyValues);

            Map<String, List<Herb>> mainsByAttr2Type = herbs.stream()
                    .filter(h -> h.mainAttr2Type != null)
                    .collect(Collectors.groupingBy(h -> h.mainAttr2Type));
            Map<String, List<Herb>> assistsByAssistAttrType = herbs.stream()
                    .filter(h -> h.assistAttrType != null)
                    .collect(Collectors.groupingBy(h -> h.assistAttrType));
            Map<String, List<Herb>> leadsByLeadAttrType = herbs.stream()
                    .filter(h -> h.leadAttrType != null)
                    .collect(Collectors.groupingBy(h -> h.leadAttrType));

            Map<String, List<DanProfile>> profilesByKey = buildDanProfilesByKey(danByName);
            Set<String> pingLeadNames = loadNameSet(pingPath);

            Map<Path, FileTime> times = new HashMap<>();
            FileTime elixirTime = Files.exists(elixirPath) ? Files.getLastModifiedTime(elixirPath) : FileTime.fromMillis(0);
            FileTime marketTime = Files.exists(danMarketPath) ? Files.getLastModifiedTime(danMarketPath) : FileTime.fromMillis(0);
            FileTime alchemyTime = Files.exists(danAlchemyPath) ? Files.getLastModifiedTime(danAlchemyPath) : FileTime.fromMillis(0);
            FileTime pingTime = Files.exists(pingPath) ? Files.getLastModifiedTime(pingPath) : FileTime.fromMillis(0);
            times.put(elixirPath, elixirTime);
            times.put(danMarketPath, marketTime);
            times.put(danAlchemyPath, alchemyTime);
            times.put(pingPath, pingTime);
            String cacheKey = elixirTime.toMillis() + "|" + marketTime.toMillis() + "|" + alchemyTime.toMillis() + "|" + pingTime.toMillis();

            logger.info("查丹方数据加载完成 herbs={} dans={} marketValues={} alchemyValues={}",
                    herbs.size(), danByName.size(), marketValues.size(), alchemyValues.size());
            return new Cache(cacheKey, danByName, marketValues, alchemyValues, mainsByAttr2Type, assistsByAssistAttrType, leadsByLeadAttrType, profilesByKey, pingLeadNames, times);
        }

        int nextDanThresholdForAssist(String mainType, int mainValue, String assistType, int assistValue) {
            String key = normalizePairKey(mainType, assistType);
            List<DanProfile> profiles = danProfilesByTypePairKey.getOrDefault(key, Collections.emptyList());
            int best = Integer.MAX_VALUE;
            for (DanProfile p : profiles) {
                Integer candidateAssist = p.getValue(assistType);
                Integer candidateMain = p.getValue(mainType);
                if (candidateAssist == null || candidateMain == null) continue;
                if (candidateAssist > assistValue && candidateMain >= mainValue) {
                    best = Math.min(best, candidateAssist);
                }
            }
            return best;
        }

        int nextDanThresholdForMain(String mainType, int mainValue, String assistType, int assistValue) {
            String key = normalizePairKey(mainType, assistType);
            List<DanProfile> profiles = danProfilesByTypePairKey.getOrDefault(key, Collections.emptyList());
            int best = Integer.MAX_VALUE;
            for (DanProfile p : profiles) {
                Integer candidateMain = p.getValue(mainType);
                Integer candidateAssist = p.getValue(assistType);
                if (candidateAssist == null || candidateMain == null) continue;
                if (candidateMain > mainValue && candidateAssist >= assistValue) {
                    best = Math.min(best, candidateMain);
                }
            }
            return best;
        }

        private static Map<String, List<DanProfile>> buildDanProfilesByKey(Map<String, Dan> danByName) {
            if (danByName == null || danByName.isEmpty()) return Collections.emptyMap();
            Map<String, List<DanProfile>> map = new HashMap<>();
            for (Dan dan : danByName.values()) {
                if (dan == null || dan.requirements == null || dan.requirements.size() != 2) continue;
                List<Map.Entry<String, Integer>> req = new ArrayList<>(dan.requirements.entrySet());
                Map.Entry<String, Integer> a = req.get(0);
                Map.Entry<String, Integer> b = req.get(1);
                if (a.getKey() == null || b.getKey() == null) continue;
                DanProfile profile = new DanProfile(dan.name, a.getKey(), a.getValue(), b.getKey(), b.getValue());
                String key = normalizePairKey(a.getKey(), b.getKey());
                map.computeIfAbsent(key, k -> new ArrayList<>()).add(profile);
            }
            return map;
        }

        private static String normalizePairKey(String typeA, String typeB) {
            if (typeA == null || typeB == null) return "";
            return typeA.compareTo(typeB) <= 0 ? typeA + "|" + typeB : typeB + "|" + typeA;
        }

        private static Set<String> loadNameSet(Path path) throws IOException {
            if (!Files.exists(path)) return Collections.emptySet();
            HashSet<String> set = new HashSet<>();
            try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    String v = line.trim();
                    if (!v.isEmpty()) set.add(v);
                }
            }
            return set;
        }

        private static void loadSimpleValueFile(Path filePath, Map<String, Integer> out) throws IOException {
            out.clear();
            if (!Files.exists(filePath)) return;
            try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (StringUtils.isBlank(line)) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length < 2) continue;
                    int value = safeParseIntStatic(parts[0]);
                    String name = parts[1].trim();
                    out.put(name, value);
                }
            }
        }

        private static int safeParseIntStatic(String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private static final class DanProfile {
        private final String danName;
        private final String type1;
        private final int value1;
        private final String type2;
        private final int value2;

        private DanProfile(String danName, String type1, int value1, String type2, int value2) {
            this.danName = danName;
            this.type1 = type1;
            this.value1 = value1;
            this.type2 = type2;
            this.value2 = value2;
        }

        Integer getValue(String type) {
            if (type == null) return null;
            if (type.equals(type1)) return value1;
            if (type.equals(type2)) return value2;
            return null;
        }
    }

    private static final class BotHerbPriceCache {
        private final long botId;
        private final Map<String, Integer> herbPrices;
        private final Path filePath;
        private final FileTime lastModified;

        private BotHerbPriceCache(long botId, Map<String, Integer> herbPrices, Path filePath, FileTime lastModified) {
            this.botId = botId;
            this.herbPrices = herbPrices;
            this.filePath = filePath;
            this.lastModified = lastModified;
        }

        static BotHerbPriceCache empty(long botId) {
            return new BotHerbPriceCache(botId, Collections.emptyMap(), Paths.get(String.valueOf(botId), "药材价格.txt"), FileTime.fromMillis(0));
        }

        static BotHerbPriceCache load(long botId) throws IOException {
            Path p = Paths.get(String.valueOf(botId), "药材价格.txt");
            if (!Files.exists(p)) return empty(botId);

            Map<String, Integer> map = new HashMap<>();
            try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (StringUtils.isBlank(line)) continue;
                    String[] parts = line.split("\\s+", 2);
                    if (parts.length < 2) continue;
                    int price = Cache.safeParseIntStatic(parts[0]);
                    String name = parts[1].trim();
                    map.put(name, price);
                }
            }
            return new BotHerbPriceCache(botId, map, p, Files.getLastModifiedTime(p));
        }

        boolean isStale() throws IOException {
            if (!Files.exists(filePath)) return !herbPrices.isEmpty();
            return !Objects.equals(Files.getLastModifiedTime(filePath), lastModified);
        }

        Optional<Integer> getPrice(String herbName) {
            return Optional.ofNullable(herbPrices.get(herbName));
        }
    }
}
