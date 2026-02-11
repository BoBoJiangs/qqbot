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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static top.sshh.qqbot.constant.Constant.targetDir;

@Component
public class DanRecipeQueryService {
    private static final Logger logger = LoggerFactory.getLogger(DanRecipeQueryService.class);
    private static final int DEFAULT_DAN_NUM = 6;
    private static final int OUTPUT_LIMIT = 20;

    private static final Pattern TRAILING_NUMBER_PATTERN = Pattern.compile("^(.*?)(\\d+)$");

    @Autowired
    private ProductPriceResponse productPriceResponse;

    private volatile Cache cache = Cache.empty();
    private final Map<Long, BotHerbPriceCache> botHerbPriceCacheMap = new ConcurrentHashMap<>();

    List<String> generateRecipeSignaturesForTest(String danName, int danNum) throws IOException {
        ensureLoaded();
        Dan dan = cache.danByName.get(danName);
        if (dan == null) return Collections.emptyList();
        List<RecipeCandidate> candidates = generateCandidates(dan, BotHerbPriceCache.empty(0), danNum);
        return candidates.stream().map(RecipeCandidate::signature).collect(Collectors.toList());
    }

    String generateRecipeTextForTest(String danName, int danNum, int limit) throws IOException {
        ensureLoaded();
        Dan dan = cache.danByName.get(danName);
        if (dan == null) return "";

        List<RecipeCandidate> candidates = generateCandidates(dan, BotHerbPriceCache.empty(0), danNum);
        if (candidates.isEmpty()) return "";

        candidates.sort(Comparator.comparingInt(RecipeCandidate::getTotalCount)
                .thenComparing(Comparator.comparingInt(RecipeCandidate::getProfit).reversed())
                .thenComparingInt(RecipeCandidate::getCost));
        int realLimit = Math.min(Math.max(1, limit), candidates.size());
        List<RecipeCandidate> top = candidates.subList(0, realLimit);

            String header = buildHeader(dan, danNum);
        String body = buildBody(top);
        return header + "\n\n" + body;
    }

    public void handleQuery(String message, Group group, Bot bot) {
        try {
            ensureLoaded();
            Query query = parseQuery(message);
            if (query == null || StringUtils.isBlank(query.danName)) {
                group.sendMessage(new MessageChain().text("用法：查丹方 <丹名> [成丹数]"));
                return;
            }

            Dan dan = cache.danByName.get(query.danName);
            if (dan == null) {
                group.sendMessage(new MessageChain().text("未找到丹药：“" + query.danName + "”。请检查丹名是否在 elixirproperties.txt 的丹药列表中。"));
                return;
            }

            BotHerbPriceCache herbPriceCache = getOrLoadBotHerbPriceCache(bot.getBotId());
            List<RecipeCandidate> candidates = generateCandidates(dan, herbPriceCache, query.danNum);
            if (candidates.isEmpty()) {
                group.sendMessage(new MessageChain().text("未能生成可用配方：丹药=" + query.danName + "。可能是药材属性/价格数据不完整。"));
                return;
            }

            candidates.sort(Comparator.comparingInt(RecipeCandidate::getTotalCount)
                    .thenComparing(Comparator.comparingInt(RecipeCandidate::getProfit).reversed())
                    .thenComparingInt(RecipeCandidate::getCost));

            int limit = Math.min(OUTPUT_LIMIT, candidates.size());
            List<RecipeCandidate> topCandidates = candidates.subList(0, limit);

            String header = buildHeader(dan, query.danNum);
            String body = buildBody(topCandidates);
            String footer = "已按用料总数从少到多排序，若相同则按利润从高到低排序，仅输出前 " + limit + " 条。\n药材价格优先取“药材价格.txt”，仅供参考，以实际坊市价格为准！";

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
    }

    private Query parseQuery(String message) {
        if (message == null) return null;
        int idx = message.indexOf("查丹方");
        if (idx < 0) return null;

        String args = message.substring(idx + "查丹方".length()).trim();
        if (StringUtils.isBlank(args)) return new Query("", DEFAULT_DAN_NUM);

        String[] tokens = args.split("\\s+");
        if (tokens.length == 1) {
            String token = tokens[0].trim();
            Matcher m = TRAILING_NUMBER_PATTERN.matcher(token);
            if (m.find()) {
                String possibleName = m.group(1);
                String possibleNum = m.group(2);
                if (StringUtils.isNotBlank(possibleName) && cache.danByName.containsKey(possibleName)) {
                    return new Query(possibleName, safeParseInt(possibleNum, DEFAULT_DAN_NUM));
                }
            }
            return new Query(token, DEFAULT_DAN_NUM);
        }

        String last = tokens[tokens.length - 1];
        if (last.matches("\\d+")) {
            int danNum = safeParseInt(last, DEFAULT_DAN_NUM);
            String danName = String.join(" ", java.util.Arrays.copyOf(tokens, tokens.length - 1)).trim();
            return new Query(danName, danNum);
        }
        return new Query(args, DEFAULT_DAN_NUM);
    }

    private int safeParseInt(String text, int defaultValue) {
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String buildHeader(Dan dan, int danNum) {
        PriceMode mode = decidePriceMode(dan.name);
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
            sb.append("配方").append(i + 1).append("（用料总数：").append(c.getTotalCount()).append("，利润：").append(c.getProfit()).append("w）\n");
            sb.append("主药：").append(c.mainName).append("×").append(c.mainCount)
                    .append("（").append(c.mainUnitPrice).append("w）\n");
            sb.append("药引：").append(c.leadName).append("×").append(c.leadCount)
                    .append("（").append(c.leadUnitPrice).append("w）\n");
            sb.append("辅药：").append(c.assistName).append("×").append(c.assistCount)
                    .append("（").append(c.assistUnitPrice).append("w）\n");
            sb.append("成本：").append(c.getCost()).append("w 收益：").append(c.revenue).append("w 利润：").append(c.getProfit()).append("w\n\n");
        }
        return sb.toString();
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

        List<RecipeCandidate> list = new ArrayList<>();
        for (Herb main : mains) {
            if (main.mainAttr2Value <= 0) continue;
            int mainCount = (int) Math.ceil((double) mainNeedValue / (double) main.mainAttr2Value);
            if (mainCount <= 0) continue;

            LeadRequirement leadReq = leadRequirementForMain(main);
            List<Herb> leads = cache.leadsByLeadAttrType.getOrDefault(leadReq.leadAttrType, Collections.emptyList());
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
                    PriceMode mode = decidePriceMode(dan.name);
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

    private PriceMode decidePriceMode(String danName) {
        Integer market = cache.danMarketValues.get(danName);
        if (market != null && market > 0) return PriceMode.MARKET;
        return PriceMode.ALCHEMY;
    }

    private int resolveDanUnitPrice(String danName, PriceMode mode) {
        if (mode == PriceMode.ALCHEMY) {
            return cache.danAlchemyValues.getOrDefault(danName, 0);
        }

        ProductPrice danPrice = productPriceResponse.getFirstByNameOrderByTimeDesc(danName);
        if (danPrice != null) return danPrice.getPrice();
        return cache.danMarketValues.getOrDefault(danName, 0);
    }

    private int resolveDanRevenue(String danName, PriceMode mode, int danNum) {
        int unitPrice = resolveDanUnitPrice(danName, mode);
        if (mode == PriceMode.ALCHEMY) return unitPrice * danNum;

        double feeRate = 1 - Utils.calculateFeeRate(unitPrice);
        return (int) (unitPrice * feeRate * danNum);
    }

    private int resolveMarketPrice(String productName) {
        ProductPrice price = productPriceResponse.getFirstByNameOrderByTimeDesc(productName);
        return price == null ? 0 : price.getPrice();
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

    private static final class LeadRequirement {
        private final String leadAttrType;

        private LeadRequirement(String leadAttrType) {
            this.leadAttrType = leadAttrType;
        }
    }

    private static final class Query {
        private final String danName;
        private final int danNum;

        private Query(String danName, int danNum) {
            this.danName = danName;
            this.danNum = danNum;
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

    private static final class Cache {
        private final Map<String, Dan> danByName;
        private final Map<String, Integer> danMarketValues;
        private final Map<String, Integer> danAlchemyValues;
        private final Map<String, List<Herb>> mainsByAttr2Type;
        private final Map<String, List<Herb>> assistsByAssistAttrType;
        private final Map<String, List<Herb>> leadsByLeadAttrType;
        private final Map<String, List<DanProfile>> danProfilesByTypePairKey;
        private final Map<Path, FileTime> fileTimes;

        private Cache(
                Map<String, Dan> danByName,
                Map<String, Integer> danMarketValues,
                Map<String, Integer> danAlchemyValues,
                Map<String, List<Herb>> mainsByAttr2Type,
                Map<String, List<Herb>> assistsByAssistAttrType,
                Map<String, List<Herb>> leadsByLeadAttrType,
                Map<String, List<DanProfile>> danProfilesByTypePairKey,
                Map<Path, FileTime> fileTimes
        ) {
            this.danByName = danByName;
            this.danMarketValues = danMarketValues;
            this.danAlchemyValues = danAlchemyValues;
            this.mainsByAttr2Type = mainsByAttr2Type;
            this.assistsByAssistAttrType = assistsByAssistAttrType;
            this.leadsByLeadAttrType = leadsByLeadAttrType;
            this.danProfilesByTypePairKey = danProfilesByTypePairKey;
            this.fileTimes = fileTimes;
        }

        static Cache empty() {
            return new Cache(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                    Collections.emptyMap(),
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

            Map<Path, FileTime> times = new HashMap<>();
            times.put(elixirPath, Files.exists(elixirPath) ? Files.getLastModifiedTime(elixirPath) : FileTime.fromMillis(0));
            times.put(danMarketPath, Files.exists(danMarketPath) ? Files.getLastModifiedTime(danMarketPath) : FileTime.fromMillis(0));
            times.put(danAlchemyPath, Files.exists(danAlchemyPath) ? Files.getLastModifiedTime(danAlchemyPath) : FileTime.fromMillis(0));

            logger.info("查丹方数据加载完成 herbs={} dans={} marketValues={} alchemyValues={}",
                    herbs.size(), danByName.size(), marketValues.size(), alchemyValues.size());
            return new Cache(danByName, marketValues, alchemyValues, mainsByAttr2Type, assistsByAssistAttrType, leadsByLeadAttrType, profilesByKey, times);
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
