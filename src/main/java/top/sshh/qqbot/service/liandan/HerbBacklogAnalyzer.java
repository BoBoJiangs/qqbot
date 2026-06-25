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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 结合当前炼丹配方与背包数量，判断药材积压更像是搭配药买不到，还是自身采购价偏高。
 */
public class HerbBacklogAnalyzer {
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
        for (BacklogHerb backlogHerb : backlogHerbs) {
            HerbAnalysis analysis = analyzeOne(backlogHerb, limitHerbsCount, herbCounts, recipes, purchasePrices, marketPriceResolver, config.getAddPrice());
            if (analysis == null) {
                continue;
            }
            analyses.add(analysis);
            for (String command : analysis.commands) {
                if (isNotBlank(command)) {
                    commands.add(command);
                }
            }
        }

        if (analyses.isEmpty()) {
            return "检测到背包药材数量超限，但这些药材当前采购价未达到调价阈值（采购价 < 当前坊市价 + 降低采购药材价格），暂不输出建议";
        }

        return buildMessage(limitHerbsCount, analyses, commands, recipes.isEmpty());
    }

    private HerbAnalysis analyzeOne(BacklogHerb backlogHerb,
                                    int limitHerbsCount,
                                    Map<String, Integer> herbCounts,
                                    List<Recipe> recipes,
                                    Map<String, Integer> purchasePrices,
                                    MarketPriceResolver marketPriceResolver,
                                    int configuredPriceOffset) {
        String herbName = backlogHerb.name;
        Integer selfBuyPrice = purchasePrices.get(herbName);
        int selfMarketPrice = resolveMarketPrice(herbName, marketPriceResolver);
        if (!shouldOutputSelfSuggestion(selfBuyPrice, selfMarketPrice, configuredPriceOffset)) {
            return null;
        }
        String selfCommand = buildPurchaseCommand(herbName, selfMarketPrice);

        List<Recipe> relatedRecipes = recipes.stream()
                .filter(recipe -> recipe.containsHerb(herbName))
                .sorted(Comparator.comparingInt(Recipe::getProfit).reversed())
                .collect(Collectors.toList());

        if (relatedRecipes.isEmpty()) {
            return buildSelfHighAnalysis(backlogHerb, limitHerbsCount, null, selfBuyPrice, selfMarketPrice, selfCommand,
                    "当前炼丹配方中没有消耗【" + herbName + "】的组合，说明采购速度大于当前消耗需求。");
        }

        Recipe readyRecipe = relatedRecipes.stream()
                .filter(recipe -> recipe.canCraft(herbCounts))
                .findFirst()
                .orElse(null);
        if (readyRecipe != null) {
            return new HerbAnalysis(backlogHerb, limitHerbsCount, "可炼但未消耗",
                    formatRecipeBrief(readyRecipe),
                    "主药、药引、辅药背包数量均满足至少1次炼丹，但药材仍在累积。",
                    "检查自动炼丹状态；若暂不想继续囤该药材，可按当前坊市价降低本药材采购价。",
                    Collections.singletonList(selfCommand));
        }

        PartnerIssue partnerIssue = findPartnerIssue(herbName, relatedRecipes, herbCounts, purchasePrices, marketPriceResolver);
        if (partnerIssue != null) {
            String buyText = formatPrice(partnerIssue.buyPrice);
            String marketText = formatMarketPrice(partnerIssue.marketPrice);
            List<String> itemCommands = new ArrayList<>();
            itemCommands.add(selfCommand);
            String partnerCommand = buildPurchaseCommand(partnerIssue.ingredient.name, partnerIssue.suggestedPrice);
            if (isNotBlank(partnerCommand)) {
                itemCommands.add(partnerCommand);
            }
            return new HerbAnalysis(backlogHerb, limitHerbsCount, "搭配药价格过低导致累积",
                    formatRecipeBrief(partnerIssue.recipe),
                    herbName + "可参与该配方，但搭配药【" + partnerIssue.ingredient.name + "】背包不足（现有"
                            + partnerIssue.currentCount + "/需要" + partnerIssue.ingredient.count + "）；"
                            + partnerIssue.ingredient.name + "当前采购价" + buyText + "，最新坊市价" + marketText + "，较难买到。",
                    "本药材采购价已达到调价阈值，建议按当前坊市价降低本药材采购价，同时提高搭配药采购价。",
                    itemCommands);
        }

        Recipe bestRecipe = relatedRecipes.get(0);
        return buildSelfHighAnalysis(backlogHerb, limitHerbsCount, bestRecipe, selfBuyPrice, selfMarketPrice, selfCommand,
                "关联配方存在，但没有发现搭配药采购价低于坊市价；更像是本药材买入过快。");
    }

    private PartnerIssue findPartnerIssue(String herbName,
                                          List<Recipe> relatedRecipes,
                                          Map<String, Integer> herbCounts,
                                          Map<String, Integer> purchasePrices,
                                          MarketPriceResolver marketPriceResolver) {
        PartnerIssue best = null;
        for (Recipe recipe : relatedRecipes) {
            List<Ingredient> shortages = recipe.findShortages(herbCounts);
            for (Ingredient shortage : shortages) {
                if (Objects.equals(shortage.name, herbName)) {
                    continue;
                }

                Integer buyPrice = purchasePrices.get(shortage.name);
                int marketPrice = resolveMarketPrice(shortage.name, marketPriceResolver);
                boolean notConfigured = buyPrice == null || buyPrice <= 0;
                boolean belowMarket = marketPrice > 0 && (notConfigured || buyPrice < marketPrice);
                if (!notConfigured && !belowMarket) {
                    continue;
                }

                int suggestedPrice = chooseSuggestedPartnerPrice(shortage, buyPrice, marketPrice);
                int score = scorePartnerIssue(recipe, buyPrice, marketPrice);
                PartnerIssue candidate = new PartnerIssue(recipe, shortage, herbCounts.getOrDefault(shortage.name, 0),
                        buyPrice, marketPrice, suggestedPrice, score);
                if (best == null || candidate.score > best.score) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private int scorePartnerIssue(Recipe recipe, Integer buyPrice, int marketPrice) {
        int currentBuy = buyPrice == null ? 0 : buyPrice;
        int priceGap = marketPrice > 0 ? Math.max(0, marketPrice - currentBuy) : 0;
        int missingBonus = currentBuy <= 0 ? 100000 : 0;
        return missingBonus + priceGap + Math.max(recipe.profit, 0);
    }

    private HerbAnalysis buildSelfHighAnalysis(BacklogHerb backlogHerb,
                                               int limitHerbsCount,
                                               Recipe recipe,
                                               Integer buyPrice,
                                               int marketPrice,
                                               String command,
                                               String reasonPrefix) {
        String recipeText = recipe == null ? "未找到足够高收益消耗配方" : formatRecipeBrief(recipe);
        String reason = reasonPrefix + " 当前采购价" + formatPrice(buyPrice) + "，最新坊市价" + formatMarketPrice(marketPrice) + "。";
        return new HerbAnalysis(backlogHerb, limitHerbsCount, "本身采购价偏高导致累积",
                recipeText,
                reason,
                "按当前坊市价降低本药材采购价后观察背包变化。",
                Collections.singletonList(command));
    }

    private String buildMessage(int limitHerbsCount, List<HerbAnalysis> analyses, Set<String> commands, boolean recipeMissing) {
        StringBuilder sb = new StringBuilder();
        sb.append("背包药材分析完成\n");
        sb.append("背包限制：").append(limitHerbsCount).append("\n");
        sb.append("检测到超限药材：").append(analyses.size()).append("种");
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

    private int chooseSuggestedPartnerPrice(Ingredient ingredient, Integer buyPrice, int marketPrice) {
        if (marketPrice > 0) {
            return marketPrice;
        }
        if (buyPrice != null && buyPrice > 0) {
            return buyPrice;
        }
        return Math.max(ingredient.unitPrice, 0);
    }

    private String buildPurchaseCommand(String herbName, int suggestedPrice) {
        if (!isNotBlank(herbName) || suggestedPrice <= 0) {
            return null;
        }
        return "采购药材" + herbName + " " + suggestedPrice;
    }

    private int resolveMarketPrice(String herbName, MarketPriceResolver resolver) {
        if (resolver == null || !isNotBlank(herbName)) {
            return 0;
        }
        Integer price = resolver.resolve(herbName.trim());
        return price == null ? 0 : Math.max(price, 0);
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

        private HerbAnalysis(BacklogHerb herb,
                             int limitHerbsCount,
                             String cause,
                             String recipeText,
                             String reason,
                             String suggestion,
                             List<String> commands) {
            this.herb = herb;
            this.limitHerbsCount = limitHerbsCount;
            this.cause = cause;
            this.recipeText = recipeText;
            this.reason = reason;
            this.suggestion = suggestion;
            this.commands = commands == null ? Collections.emptyList() : commands;
        }
    }

    private static final class PartnerIssue {
        private final Recipe recipe;
        private final Ingredient ingredient;
        private final int currentCount;
        private final Integer buyPrice;
        private final int marketPrice;
        private final int suggestedPrice;
        private final int score;

        private PartnerIssue(Recipe recipe,
                             Ingredient ingredient,
                             int currentCount,
                             Integer buyPrice,
                             int marketPrice,
                             int suggestedPrice,
                             int score) {
            this.recipe = recipe;
            this.ingredient = ingredient;
            this.currentCount = currentCount;
            this.buyPrice = buyPrice;
            this.marketPrice = marketPrice;
            this.suggestedPrice = suggestedPrice;
            this.score = score;
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

        private boolean canCraft(Map<String, Integer> herbCounts) {
            for (Map.Entry<String, Integer> entry : needByName.entrySet()) {
                if (herbCounts.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                    return false;
                }
            }
            return true;
        }

        private List<Ingredient> findShortages(Map<String, Integer> herbCounts) {
            List<Ingredient> shortages = new ArrayList<>();
            for (Ingredient ingredient : ingredients) {
                int current = herbCounts.getOrDefault(ingredient.name, 0);
                if (current < needByName.getOrDefault(ingredient.name, ingredient.count)) {
                    shortages.add(ingredient);
                }
            }
            return shortages;
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
