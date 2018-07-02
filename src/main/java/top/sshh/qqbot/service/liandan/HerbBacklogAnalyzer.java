package top.sshh.qqbot.service.liandan;

import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.ProductPrice;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 结合当前炼丹配方与背包数量，判断药材积压更像是搭配药买不到，还是自身采购价偏高。
 */
public class HerbBacklogAnalyzer {
    private static final int PARTNER_MARKET_PRICE_PREMIUM = 30;
    private static final Pattern INGREDIENT_PATTERN = Pattern.compile("(主药|药引|辅药)([^\\s-]+)-(\\d+)&(-?\\d+)");
    private static final Pattern PROFIT_PATTERN = Pattern.compile("(炼金收益|坊市收益)(-?\\d+)");
    private static final Pattern DAN_LABEL_PATTERN = Pattern.compile("(\\d+丹\\s+\\S+)$");

    private final Path baseDir;

    public HerbBacklogAnalyzer() {
        this(Paths.get(""));
    }

    public HerbBacklogAnalyzer(Path baseDir) {
        this.baseDir = baseDir == null ? Paths.get("") : baseDir;
    }

    public String analyze(long botId,
                          Config config,
                          Map<String, ProductPrice> herbPackMap,
                          Map<String, ProductPrice> runtimePurchaseMap,
                          MarketPriceResolver marketPriceResolver) throws IOException {
        if (config == null) {
            return "配置信息获取失败，无法进行分析";
        }

        int limitHerbsCount = config.getLimitHerbsCount();
        Map<String, Integer> herbCounts = toHerbCounts(herbPackMap);
        List<BacklogHerb> backlogHerbs = herbCounts.entrySet()
                .stream()
                .filter(e -> e.getValue() > limitHerbsCount)
                .map(e -> new BacklogHerb(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(BacklogHerb::getCount).reversed().thenComparing(BacklogHerb::getName))
                .collect(Collectors.toList());

        if (backlogHerbs.isEmpty()) {
            return "当前背包药材数量均在限制范围内，无需调整价格";
        }

        List<Recipe> recipes = loadRecipes(botId);
        Map<String, Integer> purchasePrices = loadPurchasePrices(botId);
        if (runtimePurchaseMap != null) {
            for (ProductPrice productPrice : runtimePurchaseMap.values()) {
                if (productPrice != null && isNotBlank(productPrice.getName()) && productPrice.getPrice() > 0) {
                    purchasePrices.put(productPrice.getName().trim(), productPrice.getPrice());
                }
            }
        }

        List<HerbAnalysis> analyses = new ArrayList<>();
        Set<String> commands = new LinkedHashSet<>();
        Map<String, Integer> marketPriceCache = new HashMap<>();
        for (BacklogHerb backlogHerb : backlogHerbs) {
            HerbAnalysis analysis = analyzeOne(backlogHerb, limitHerbsCount, herbCounts, recipes,
                    purchasePrices, marketPriceResolver, marketPriceCache, config.getAddPrice());
            analyses.add(analysis);
            for (String command : analysis.commands) {
                if (isNotBlank(command)) {
                    commands.add(command);
                }
            }
        }

        return buildMessage(herbCounts.size(), limitHerbsCount, analyses, commands, recipes.isEmpty());
    }

    private HerbAnalysis analyzeOne(BacklogHerb backlogHerb,
                                    int limitHerbsCount,
                                    Map<String, Integer> herbCounts,
                                     List<Recipe> recipes,
                                     Map<String, Integer> purchasePrices,
                                     MarketPriceResolver marketPriceResolver,
                                     Map<String, Integer> marketPriceCache,
                                     int configuredPriceOffset) {
        String herbName = backlogHerb.name;
        Integer selfBuyPrice = purchasePrices.get(herbName);
        int selfMarketPrice = resolveMarketPrice(herbName, marketPriceResolver, marketPriceCache);
        boolean selfPriceActionable = shouldOutputSelfSuggestion(selfBuyPrice, selfMarketPrice, configuredPriceOffset);
        boolean missingPriceData = selfBuyPrice == null || selfBuyPrice <= 0 || selfMarketPrice <= 0;
        String selfCommand = selfPriceActionable ? buildPurchaseCommand(herbName, selfMarketPrice) : null;
        String selfPriceStatus = buildSelfPriceStatus(selfBuyPrice, selfMarketPrice, configuredPriceOffset);

        List<Recipe> relatedRecipes = recipes.stream()
                .filter(recipe -> recipe.containsHerb(herbName))
                .sorted(Comparator.comparingInt(Recipe::getProfit).reversed())
                .collect(Collectors.toList());

        if (relatedRecipes.isEmpty()) {
            List<String> itemCommands = commandList(selfCommand);
            String suggestion = selfPriceActionable
                    ? "当前没有可用消耗配方，建议按当前坊市价降低本药材采购价。"
                    : "当前没有可用消耗配方；暂不生成调价命令，请检查配方配置或停止采购该药材。";
            return new HerbAnalysis(backlogHerb, limitHerbsCount, "无可用消耗配方", "未找到消耗该药材的配方",
                    "当前炼丹配方中没有消耗【" + herbName + "】的组合。" + selfPriceStatus,
                    suggestion, itemCommands, missingPriceData);
        }

        RecipePlan bestPlan = relatedRecipes.stream()
                .map(recipe -> buildRecipePlan(recipe, backlogHerb, limitHerbsCount, herbCounts))
                .filter(plan -> plan != null)
                .sorted(recipePlanComparator())
                .findFirst()
                .orElse(null);
        if (bestPlan == null) {
            List<String> itemCommands = commandList(selfCommand);
            return new HerbAnalysis(backlogHerb, limitHerbsCount, "配方无法消化当前库存", formatRecipeBrief(relatedRecipes.get(0)),
                    "关联配方存在，但单炉所需的本药材数量大于当前库存。" + selfPriceStatus,
                    selfPriceActionable ? "建议先按坊市价降低采购价。" : "暂不生成调价命令，请继续观察库存。",
                    itemCommands, missingPriceData);
        }

        List<String> itemCommands = commandList(selfCommand);
        if (bestPlan.shortages.isEmpty()) {
            String suggestion = selfPriceActionable
                    ? "检查自动炼丹状态；若暂不想继续囤该药材，可按当前坊市价降低采购价。"
                    : "材料足以消化超限库存，建议优先检查自动炼丹是否运行、任务是否被暂停。";
            return new HerbAnalysis(backlogHerb, limitHerbsCount, "库存可消化但未执行",
                    formatRecipeBrief(bestPlan.recipe),
                    "要降至限制数量需炼" + bestPlan.targetBatches + "炉，当前库存可炼"
                            + bestPlan.craftableBatches + "炉。" + selfPriceStatus,
                    suggestion, itemCommands, missingPriceData);
        }

        boolean partnerPriceNeedsAdjustment = false;
        boolean partnerPriceMissing = false;
        List<String> shortageTexts = new ArrayList<>();
        for (RecipeShortage shortage : bestPlan.shortages) {
            Integer buyPrice = purchasePrices.get(shortage.name);
            int marketPrice = resolveMarketPrice(shortage.name, marketPriceResolver, marketPriceCache);
            boolean notConfigured = buyPrice == null || buyPrice <= 0;
            boolean priceMissing = marketPrice <= 0;
            boolean belowMarket = marketPrice > 0 && (notConfigured || buyPrice < marketPrice);
            partnerPriceNeedsAdjustment |= belowMarket;
            partnerPriceMissing |= priceMissing || notConfigured;

            shortageTexts.add("【" + shortage.name + "】现有" + shortage.currentCount + "/目标"
                    + shortage.requiredCount + "，缺" + shortage.missingCount + "；采购价"
                    + formatPrice(buyPrice) + "，坊市价" + formatMarketPrice(marketPrice));

            int suggestedPrice = chooseSuggestedPartnerPrice(shortage.unitPrice, buyPrice, marketPrice);
            if (belowMarket && suggestedPrice > 0) {
                addCommand(itemCommands, buildPurchaseCommand(shortage.name, suggestedPrice));
            }
        }

        String suggestion;
        if (partnerPriceNeedsAdjustment) {
            suggestion = "优先补齐或提高短缺搭配药采购价";
        } else if (partnerPriceMissing) {
            suggestion = "短缺搭配药的采购价或坊市价不完整，建议先刷新价格数据再决定调价";
        } else {
            suggestion = "搭配药库存不足但采购价并不低，建议等待成交或检查坊市刷新状态";
        }
        if (selfPriceActionable) {
            suggestion += "；同时可按当前坊市价降低本药材采购价。";
        } else {
            suggestion += "；本药材暂不生成降价命令。";
        }

        return new HerbAnalysis(backlogHerb, limitHerbsCount, "搭配药库存不足导致积压",
                formatRecipeBrief(bestPlan.recipe),
                "要降至限制数量需炼" + bestPlan.targetBatches + "炉，当前最多可炼"
                        + bestPlan.craftableBatches + "炉。短缺：" + String.join("；", shortageTexts)
                        + "。" + selfPriceStatus,
                suggestion, itemCommands, missingPriceData || partnerPriceMissing);
    }

    private RecipePlan buildRecipePlan(Recipe recipe,
                                       BacklogHerb backlogHerb,
                                       int limitHerbsCount,
                                       Map<String, Integer> herbCounts) {
        int selfNeedPerBatch = recipe.needByName.getOrDefault(backlogHerb.name, 0);
        int surplus = backlogHerb.count - limitHerbsCount;
        if (selfNeedPerBatch <= 0 || surplus <= 0) {
            return null;
        }

        int targetBatches = ceilDiv(surplus, selfNeedPerBatch);
        int selfRequired = targetBatches * selfNeedPerBatch;
        if (backlogHerb.count < selfRequired) {
            return null;
        }

        int craftableBatches = Integer.MAX_VALUE;
        List<RecipeShortage> shortages = new ArrayList<>();
        int totalMissing = 0;
        for (Map.Entry<String, Integer> requirement : recipe.needByName.entrySet()) {
            String name = requirement.getKey();
            int needPerBatch = requirement.getValue();
            if (needPerBatch <= 0) {
                continue;
            }
            int currentCount = herbCounts.getOrDefault(name, 0);
            craftableBatches = Math.min(craftableBatches, currentCount / needPerBatch);

            int requiredCount = targetBatches * needPerBatch;
            if (!name.equals(backlogHerb.name) && currentCount < requiredCount) {
                int missingCount = requiredCount - currentCount;
                Ingredient ingredient = recipe.firstIngredient(name);
                shortages.add(new RecipeShortage(name, currentCount, requiredCount, missingCount,
                        ingredient == null ? 0 : ingredient.unitPrice));
                totalMissing += missingCount;
            }
        }

        if (craftableBatches == Integer.MAX_VALUE) {
            craftableBatches = 0;
        }
        shortages.sort(Comparator.comparingInt(RecipeShortage::getMissingCount).reversed()
                .thenComparing(RecipeShortage::getName));
        return new RecipePlan(recipe, targetBatches, craftableBatches, shortages, totalMissing);
    }

    private Comparator<RecipePlan> recipePlanComparator() {
        return (left, right) -> {
            int result = Double.compare(right.getCompletionRatio(), left.getCompletionRatio());
            if (result != 0) return result;
            result = Integer.compare(left.shortages.size(), right.shortages.size());
            if (result != 0) return result;
            result = Integer.compare(left.totalMissing, right.totalMissing);
            if (result != 0) return result;
            return Integer.compare(right.recipe.profit, left.recipe.profit);
        };
    }

    private int ceilDiv(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    private String buildSelfPriceStatus(Integer buyPrice, int marketPrice, int configuredPriceOffset) {
        if (buyPrice == null || buyPrice <= 0) {
            return " 本药材尚未设置采购价。";
        }
        if (marketPrice <= 0) {
            return " 本药材当前采购价" + formatPrice(buyPrice) + "，但未查询到坊市价。";
        }
        int threshold = marketPrice + configuredPriceOffset;
        if (buyPrice >= threshold) {
            return " 本药材当前采购价" + formatPrice(buyPrice) + "，坊市价" + formatMarketPrice(marketPrice)
                    + "，已达到调价门槛" + threshold + "万。";
        }
        return " 本药材当前采购价" + formatPrice(buyPrice) + "，坊市价" + formatMarketPrice(marketPrice)
                + "，未达到调价门槛" + threshold + "万，暂不建议降价。";
    }

    private List<String> commandList(String command) {
        List<String> commands = new ArrayList<>();
        addCommand(commands, command);
        return commands;
    }

    private void addCommand(List<String> commands, String command) {
        if (isNotBlank(command) && !commands.contains(command)) {
            commands.add(command);
        }
    }

    private String buildMessage(int parsedHerbCount, int limitHerbsCount, List<HerbAnalysis> analyses,
                                Set<String> commands, boolean recipeMissing) {
        long actionableCount = analyses.stream().filter(analysis -> !analysis.commands.isEmpty()).count();
        long missingPriceCount = analyses.stream().filter(analysis -> analysis.missingPriceData).count();
        StringBuilder sb = new StringBuilder();
        sb.append("背包药材分析完成\n");
        sb.append("已解析药材：").append(parsedHerbCount).append("种\n");
        sb.append("背包限制：").append(limitHerbsCount).append("\n");
        sb.append("检测到超限药材：").append(analyses.size()).append("种\n");
        sb.append("生成调价建议：").append(actionableCount).append("种\n");
        sb.append("暂不调价/继续观察：").append(analyses.size() - actionableCount).append("种\n");
        sb.append("价格数据不完整：").append(missingPriceCount).append("种");
        if (recipeMissing) {
            sb.append("\n提示：未找到当前bot的炼丹配方.txt，本次只能按采购价给出保守建议。");
        }
        sb.append("\n\n");

        for (int i = 0; i < analyses.size(); i++) {
            HerbAnalysis analysis = analyses.get(i);
            sb.append(i + 1).append(". ")
                    .append(analysis.herb.name).append(" ")
                    .append(analysis.herb.count).append("/")
                    .append(analysis.limitHerbsCount).append("\n");
            sb.append("判断：").append(analysis.cause).append("\n");
            sb.append("关联配方：").append(analysis.recipeText).append("\n");
            sb.append("原因：").append(analysis.reason).append("\n");
            sb.append("建议：").append(analysis.suggestion).append("\n");
            for (String command : analysis.commands) {
                if (isNotBlank(command)) {
                    sb.append(command).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("可复制调整命令：\n");
        if (commands.isEmpty()) {
            sb.append("本次没有生成采购价调整命令。");
        } else {
            commands.forEach(command -> sb.append(command).append("\n"));
        }

        return sb.toString().trim();
    }

    private List<Recipe> loadRecipes(long botId) throws IOException {
        Path recipePath = resolveBotFile(botId, "炼丹配方.txt");
        if (!Files.exists(recipePath)) {
            return Collections.emptyList();
        }

        List<Recipe> recipes = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(recipePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Recipe recipe = parseRecipe(line);
                if (recipe != null) {
                    recipes.add(recipe);
                }
            }
        }
        return recipes;
    }

    private Recipe parseRecipe(String line) {
        if (!isNotBlank(line) || line.endsWith("配方")) {
            return null;
        }

        Matcher matcher = INGREDIENT_PATTERN.matcher(line);
        List<Ingredient> ingredients = new ArrayList<>();
        while (matcher.find()) {
            ingredients.add(new Ingredient(matcher.group(1), matcher.group(2),
                    safeParseInt(matcher.group(3), 0),
                    safeParseInt(matcher.group(4), 0)));
        }
        if (ingredients.size() < 3) {
            return null;
        }

        int profit = 0;
        Matcher profitMatcher = PROFIT_PATTERN.matcher(line);
        if (profitMatcher.find()) {
            profit = safeParseInt(profitMatcher.group(2), 0);
        }

        String danLabel = "";
        Matcher danMatcher = DAN_LABEL_PATTERN.matcher(line.trim());
        if (danMatcher.find()) {
            danLabel = danMatcher.group(1);
        }
        return new Recipe(danLabel, profit, ingredients);
    }

    private Map<String, Integer> loadPurchasePrices(long botId) throws IOException {
        Path pricePath = resolveBotFile(botId, "药材价格.txt");
        if (!Files.exists(pricePath)) {
            return new LinkedHashMap<>();
        }

        Map<String, Integer> prices = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(pricePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length < 2) {
                    continue;
                }
                int price = safeParseInt(parts[0], 0);
                String name = parts[1].trim();
                if (price > 0 && isNotBlank(name)) {
                    prices.put(name, price);
                }
            }
        }
        return prices;
    }

    private Path resolveBotFile(long botId, String fileName) {
        return baseDir.resolve(String.valueOf(botId)).resolve(fileName).normalize();
    }

    private Map<String, Integer> toHerbCounts(Map<String, ProductPrice> herbPackMap) {
        if (herbPackMap == null || herbPackMap.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ProductPrice productPrice : herbPackMap.values()) {
            if (productPrice != null && isNotBlank(productPrice.getName())) {
                counts.put(productPrice.getName().trim(), productPrice.getHerbCount());
            }
        }
        return counts;
    }

    private String formatRecipeBrief(Recipe recipe) {
        StringBuilder sb = new StringBuilder();
        if (isNotBlank(recipe.danLabel)) {
            sb.append(recipe.danLabel);
        } else {
            sb.append("未知丹药");
        }
        sb.append("（");
        sb.append(recipe.ingredients.stream()
                .map(i -> i.role + i.name + i.count)
                .collect(Collectors.joining(" ")));
        if (recipe.profit != 0) {
            sb.append("，收益").append(recipe.profit).append("w");
        }
        sb.append("）");
        return sb.toString();
    }

    private boolean shouldOutputSelfSuggestion(Integer selfBuyPrice, int selfMarketPrice, int configuredPriceOffset) {
        if (selfBuyPrice == null || selfBuyPrice <= 0 || selfMarketPrice <= 0) {
            return false;
        }
        return selfBuyPrice >= selfMarketPrice + configuredPriceOffset;
    }

    private int chooseSuggestedPartnerPrice(int recipeUnitPrice, Integer buyPrice, int marketPrice) {
        if (marketPrice > 0) {
            return (int) Math.min(Integer.MAX_VALUE, (long) marketPrice + PARTNER_MARKET_PRICE_PREMIUM);
        }
        if (buyPrice != null && buyPrice > 0) {
            return buyPrice;
        }
        return Math.max(recipeUnitPrice, 0);
    }

    private String buildPurchaseCommand(String herbName, int suggestedPrice) {
        if (!isNotBlank(herbName) || suggestedPrice <= 0) {
            return null;
        }
        return "采购药材" + herbName + " " + suggestedPrice;
    }

    private int resolveMarketPrice(String herbName, MarketPriceResolver resolver, Map<String, Integer> cache) {
        if (resolver == null || !isNotBlank(herbName)) {
            return 0;
        }
        String normalizedName = herbName.trim();
        if (cache != null && cache.containsKey(normalizedName)) {
            return cache.get(normalizedName);
        }
        int resolvedPrice;
        try {
            Integer price = resolver.resolve(normalizedName);
            resolvedPrice = price == null ? 0 : Math.max(price, 0);
        } catch (RuntimeException e) {
            resolvedPrice = 0;
        }
        if (cache != null) {
            cache.put(normalizedName, resolvedPrice);
        }
        return resolvedPrice;
    }

    private String formatPrice(Integer price) {
        return price == null || price <= 0 ? "未设置" : price + "万";
    }

    private String formatMarketPrice(int price) {
        return price <= 0 ? "未查询到" : price + "万";
    }

    private int safeParseInt(String text, int defaultValue) {
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean isNotBlank(String text) {
        return text != null && !text.trim().isEmpty();
    }

    @FunctionalInterface
    public interface MarketPriceResolver {
        Integer resolve(String herbName);
    }

    private static final class BacklogHerb {
        private final String name;
        private final int count;

        private BacklogHerb(String name, int count) {
            this.name = name;
            this.count = count;
        }

        private String getName() {
            return name;
        }

        private int getCount() {
            return count;
        }
    }

    private static final class HerbAnalysis {
        private final BacklogHerb herb;
        private final int limitHerbsCount;
        private final String cause;
        private final String recipeText;
        private final String reason;
        private final String suggestion;
        private final List<String> commands;
        private final boolean missingPriceData;

        private HerbAnalysis(BacklogHerb herb,
                             int limitHerbsCount,
                             String cause,
                             String recipeText,
                             String reason,
                             String suggestion,
                             List<String> commands,
                             boolean missingPriceData) {
            this.herb = herb;
            this.limitHerbsCount = limitHerbsCount;
            this.cause = cause;
            this.recipeText = recipeText;
            this.reason = reason;
            this.suggestion = suggestion;
            this.commands = commands == null ? Collections.emptyList() : commands;
            this.missingPriceData = missingPriceData;
        }
    }

    private static final class RecipePlan {
        private final Recipe recipe;
        private final int targetBatches;
        private final int craftableBatches;
        private final List<RecipeShortage> shortages;
        private final int totalMissing;

        private RecipePlan(Recipe recipe,
                           int targetBatches,
                           int craftableBatches,
                           List<RecipeShortage> shortages,
                           int totalMissing) {
            this.recipe = recipe;
            this.targetBatches = targetBatches;
            this.craftableBatches = craftableBatches;
            this.shortages = shortages;
            this.totalMissing = totalMissing;
        }

        private double getCompletionRatio() {
            if (targetBatches <= 0) {
                return 0D;
            }
            return Math.min(craftableBatches, targetBatches) / (double) targetBatches;
        }
    }

    private static final class RecipeShortage {
        private final String name;
        private final int currentCount;
        private final int requiredCount;
        private final int missingCount;
        private final int unitPrice;

        private RecipeShortage(String name,
                               int currentCount,
                               int requiredCount,
                               int missingCount,
                               int unitPrice) {
            this.name = name;
            this.currentCount = currentCount;
            this.requiredCount = requiredCount;
            this.missingCount = missingCount;
            this.unitPrice = unitPrice;
        }

        private String getName() {
            return name;
        }

        private int getMissingCount() {
            return missingCount;
        }
    }

    private static final class Recipe {
        private final String danLabel;
        private final int profit;
        private final List<Ingredient> ingredients;
        private final Map<String, Integer> needByName;

        private Recipe(String danLabel, int profit, List<Ingredient> ingredients) {
            this.danLabel = danLabel;
            this.profit = profit;
            this.ingredients = ingredients;
            this.needByName = new LinkedHashMap<>();
            for (Ingredient ingredient : ingredients) {
                this.needByName.merge(ingredient.name, ingredient.count, Integer::sum);
            }
        }

        private boolean containsHerb(String herbName) {
            return needByName.containsKey(herbName);
        }

        private Ingredient firstIngredient(String herbName) {
            for (Ingredient ingredient : ingredients) {
                if (ingredient.name.equals(herbName)) {
                    return ingredient;
                }
            }
            return null;
        }

        private int getProfit() {
            return profit;
        }
    }

    private static final class Ingredient {
        private final String role;
        private final String name;
        private final int count;
        private final int unitPrice;

        private Ingredient(String role, String name, int count, int unitPrice) {
            this.role = role;
            this.name = name;
            this.count = count;
            this.unitPrice = unitPrice;
        }
    }
}
