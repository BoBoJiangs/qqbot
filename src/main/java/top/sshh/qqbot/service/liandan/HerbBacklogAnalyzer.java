package top.sshh.qqbot.service.liandan;

import top.sshh.qqbot.service.storage.LocalDataFiles;

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
    private static final int PARTNER_LOW_PRICE_LIMIT = 500;
    private static final int PARTNER_LOW_PRICE_PREMIUM = 10;
    private static final int PARTNER_HIGH_PRICE_PREMIUM = 30;
    private static final String PARTNER_PRICE_RULE = "坊市价≤" + PARTNER_LOW_PRICE_LIMIT
            + "万加" + PARTNER_LOW_PRICE_PREMIUM + "万、>" + PARTNER_LOW_PRICE_LIMIT
            + "万加" + PARTNER_HIGH_PRICE_PREMIUM + "万";
    /** 只有预计炼丹净收益至少为 1 万时，才允许生成采购建议。 */
    private static final int MIN_RECIPE_PROFIT = 1;
    /** 展示少量代表方案，所有候选方案仍参与分析。 */
    private static final int MAX_RECIPE_DETAILS = 5;
    /** 短缺汇总最多展示的药材数量；超出时仍会显示总数。 */
    private static final int MAX_SHORTAGE_SUMMARY = 40;
    private static final Pattern INGREDIENT_PATTERN = Pattern.compile("(主药|药引|辅药)([^\\s-]+)-(\\d+)&(-?\\d+)");
    private static final Pattern PROFIT_PATTERN = Pattern.compile("(炼金收益|坊市收益)(-?\\d+)");
    private static final Pattern COST_PATTERN = Pattern.compile("花费(-?\\d+)");
    private static final Pattern DAN_LABEL_PATTERN = Pattern.compile("(\\d+)丹\\s+(\\S+)$");

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
        return analyze(botId, config, herbPackMap, runtimePurchaseMap, Collections.emptyMap(), marketPriceResolver);
    }

    public String analyze(long botId,
                          Config config,
                          Map<String, ProductPrice> herbPackMap,
                          Map<String, ProductPrice> runtimePurchaseMap,
                          Map<String, Integer> repeatPurchasePrices,
                          MarketPriceResolver marketPriceResolver) throws IOException {
        if (config == null) {
            return "配置信息获取失败，无法进行分析";
        }

        int limitHerbsCount = config.getLimitHerbsCount();
        Map<String, Integer> herbCounts = toHerbCounts(herbPackMap);
        // 重复采购配置仅用于展示对比，不参与普通采购建议和炼丹收益计算。
        Map<String, Integer> repeatPricesForDisplay = new LinkedHashMap<>();
        if (repeatPurchasePrices != null) {
            repeatPurchasePrices.forEach((name, price) -> {
                if (isNotBlank(name) && price != null && price > 0) {
                    repeatPricesForDisplay.put(name.trim(), price);
                }
            });
        }
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
        PriceAdvice advice = new PriceAdvice(purchasePrices, marketPriceResolver, marketPriceCache,
                Math.max(MIN_RECIPE_PROFIT, config.isAlchemy() ? config.getAlchemyNumber() : config.getMakeNumber()));
        // 先确定全部超限药材的降价，后续各丹方共用这张价格表并校验整批建议。
        for (BacklogHerb herb : backlogHerbs) {
            advice.backlogNames.add(herb.name);
            int marketPrice = resolveMarketPrice(herb.name, marketPriceResolver, marketPriceCache);
            if (shouldOutputSelfSuggestion(purchasePrices.get(herb.name), marketPrice, config.getAddPrice())) {
                advice.proposedPrices.put(herb.name, marketPrice);
            }
        }
        for (BacklogHerb backlogHerb : backlogHerbs) {
            analyzeOne(backlogHerb, limitHerbsCount, herbCounts, recipes,
                    purchasePrices, marketPriceResolver, marketPriceCache, config.getAddPrice(), advice);
        }
        // 完成全部报价后统一渲染，避免早先方案显示的收益没有计入后面药材的涨价。
        advice.finalized = true;
        for (BacklogHerb backlogHerb : backlogHerbs) {
            HerbAnalysis analysis = analyzeOne(backlogHerb, limitHerbsCount, herbCounts, recipes,
                    purchasePrices, marketPriceResolver, marketPriceCache, config.getAddPrice(), advice);
            analyses.add(analysis);
            for (String command : analysis.commands) {
                if (isNotBlank(command)) {
                    commands.add(command);
                }
            }
        }

        return buildMessage(herbCounts.size(), limitHerbsCount, analyses, commands, recipes.isEmpty(),
                advice, repeatPricesForDisplay);
    }

    private HerbAnalysis analyzeOne(BacklogHerb backlogHerb,
                                     int limitHerbsCount,
                                     Map<String, Integer> herbCounts,
                                     List<Recipe> recipes,
                                     Map<String, Integer> purchasePrices,
                                     MarketPriceResolver marketPriceResolver,
                                     Map<String, Integer> marketPriceCache,
                                     int configuredPriceOffset,
                                     PriceAdvice advice) {
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

        List<RecipePlan> allPlans = relatedRecipes.stream()
                .map(recipe -> buildRecipePlan(recipe, backlogHerb, limitHerbsCount, herbCounts))
                .filter(plan -> plan != null)
                .sorted(recipePlanComparator())
                .collect(Collectors.toList());
        if (allPlans.isEmpty()) {
            List<String> itemCommands = commandList(selfCommand);
            return new HerbAnalysis(backlogHerb, limitHerbsCount, "配方无法消化当前库存", formatRecipeBrief(relatedRecipes.get(0)),
                    "关联配方存在，但单炉所需的本药材数量大于当前库存。" + selfPriceStatus,
                    selfPriceActionable ? "建议先按坊市价降低采购价。" : "暂不生成调价命令，请继续观察库存。",
                    itemCommands, missingPriceData);
        }
        List<RecipePlan> plans = distinctRecipePlans(allPlans);
        for (RecipePlan plan : plans) {
            plan.pricing = evaluateRecipePrices(plan, advice);
        }
        plans.sort(Comparator.comparing((RecipePlan plan) -> !plan.pricing.affordable)
                .thenComparing(recipePlanComparator()));
        RecipePlan bestPlan = plans.get(0);
        List<String> itemCommands = commandList(selfCommand);
        List<RecipePlan> displayedPlans = plans.subList(0, Math.min(MAX_RECIPE_DETAILS, plans.size()));
        Map<String, RecipeShortage> shortageSummary = new LinkedHashMap<>();
        List<String> planShortageTexts = new ArrayList<>();
        for (int planIndex = 0; planIndex < plans.size(); planIndex++) {
            RecipePlan plan = plans.get(planIndex);
            for (RecipeShortage shortage : plan.shortages) {
                mergeShortage(shortageSummary, shortage);
            }
            missingPriceData |= plan.pricing.missingPrice;
            if (planIndex < displayedPlans.size()) {
                // 在已接受建议的基础上重新求整炉报价。新建议不得使前面任一丹方亏损。
                plan.pricing = evaluateRecipePrices(plan, advice);
                if (plan.pricing.affordable) {
                    if (!advice.finalized) {
                        advice.proposedPrices.putAll(plan.pricing.proposals);
                        advice.protectedRecipes.add(plan.recipe);
                    }
                    plan.pricing.proposals.forEach((name, price) ->
                            addCommand(itemCommands, buildPurchaseCommand(name, price)));
                }
                StringBuilder detail = new StringBuilder("方案").append(planIndex + 1)
                        .append("：需炼").append(plan.targetBatches).append("炉，现可炼")
                        .append(plan.craftableBatches).append("炉；").append(plan.pricing.status);
                for (RecipeShortage shortage : plan.shortages) {
                    detail.append("\n").append(formatShortage(shortage, purchasePrices,
                            marketPriceResolver, marketPriceCache));
                    Integer suggestedPrice = plan.pricing.proposals.get(shortage.name);
                    if (suggestedPrice != null) {
                        detail.append("；联合建议价").append(suggestedPrice).append("万");
                        int marketPrice = resolveMarketPrice(shortage.name, marketPriceResolver, marketPriceCache);
                        int targetPrice = chooseSuggestedPartnerPrice(marketPrice);
                        if (marketPrice > 0 && suggestedPrice < targetPrice) {
                            detail.append("（目标价").append(targetPrice).append("万，受整批收益预算限制）");
                        }
                    }
                }
                planShortageTexts.add(detail.toString());
            }
        }

        String cause;
        String reason;
        String suggestion;
        if (!bestPlan.pricing.affordable) {
            cause = missingPriceData ? "价格数据不足，无法确认收益" : "无可盈利消耗配方";
            reason = "继续提高搭配药采购价可能导致炼丹亏损；按当前报价未找到达到收益底线的方案。";
            suggestion = "先刷新价格/丹方或等待更低报价；不为未通过整炉收益校验的方案生成采购命令";
        } else if (bestPlan.shortages.isEmpty()) {
            cause = "库存可消化但未执行";
            reason = "要降至限制数量需炼" + bestPlan.targetBatches + "炉，当前库存可炼"
                    + bestPlan.craftableBatches + "炉。";
            suggestion = "材料足以消化超限库存，建议检查自动炼丹是否运行、任务是否被暂停";
        } else {
            cause = "搭配药库存不足导致积压";
            reason = "要降至限制数量需炼" + bestPlan.targetBatches + "炉，最佳单一方案当前可炼"
                    + bestPlan.craftableBatches + "炉。";
            suggestion = "按可盈利方案补齐搭配药；目标价为" + PARTNER_PRICE_RULE + "，实际建议受整批收益底线限制";
        }
        if (selfPriceActionable) {
            suggestion += "；同时可按当前坊市价降低本药材采购价。";
        } else {
            suggestion += "；本药材暂不生成降价命令。";
        }

        reason += "\n" + String.join("\n\n", planShortageTexts);
        reason += "\n候选方案短缺汇总（可选搭配，无需全部采购；同药取最大缺口）：\n"
                + formatShortageSummary(shortageSummary, purchasePrices, marketPriceResolver, marketPriceCache)
                + "\n" + selfPriceStatus;

        return new HerbAnalysis(backlogHerb, limitHerbsCount, cause,
                formatRecipePlans(displayedPlans, plans.size()),
                reason, suggestion, itemCommands, missingPriceData);
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

    private List<RecipePlan> distinctRecipePlans(List<RecipePlan> sortedPlans) {
        if (sortedPlans == null || sortedPlans.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, RecipePlan> uniquePlans = new LinkedHashMap<>();
        for (RecipePlan plan : sortedPlans) {
            uniquePlans.putIfAbsent(recipeSignature(plan.recipe), plan);
        }
        return new ArrayList<>(uniquePlans.values());
    }

    private String recipeSignature(Recipe recipe) {
        return recipe.danLabel + "|" + recipe.alchemy + "|" + recipe.needByName.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("|"));
    }

    private void mergeShortage(Map<String, RecipeShortage> summary, RecipeShortage shortage) {
        RecipeShortage existing = summary.get(shortage.name);
        if (existing == null
                || shortage.missingCount > existing.missingCount
                || (shortage.missingCount == existing.missingCount
                && shortage.requiredCount > existing.requiredCount)) {
            summary.put(shortage.name, shortage);
        }
    }

    private String formatRecipePlans(List<RecipePlan> plans, int totalPlanCount) {
        if (plans == null || plans.isEmpty()) {
            return "未找到可执行的配方方案";
        }
        if (totalPlanCount <= 1) {
            return formatRecipeBrief(plans.get(0).recipe);
        }
        StringBuilder sb = new StringBuilder("候选搭配方案").append(totalPlanCount)
                .append("个，展示前").append(plans.size()).append("个：");
        for (int i = 0; i < plans.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append("方案").append(i + 1).append(" ").append(formatRecipeBrief(plans.get(i).recipe));
        }
        return sb.toString();
    }

    /** 所有搭配药共用一炉的收益预算，并保护本次分析中此前已接受的配方。 */
    private RecipePricing evaluateRecipePrices(RecipePlan plan, PriceAdvice advice) {
        long revenue = recipeRevenue(plan.recipe, advice);
        if (revenue <= 0L) {
            return RecipePricing.blocked("收入数据不完整，暂不生成搭配药采购命令", true);
        }
        Map<String, Integer> trialPrices = new LinkedHashMap<>(advice.proposedPrices);
        long currentCost = recipeCost(plan.recipe, trialPrices, advice);
        if (currentCost < 0L) {
            return RecipePricing.blocked("药材报价不完整，暂不生成搭配药采购命令", true);
        }
        Map<String, Integer> preferredPrices = new LinkedHashMap<>();
        boolean missingMarketPrice = false;
        for (RecipeShortage shortage : plan.shortages) {
            int marketPrice = resolveMarketPrice(shortage.name, advice.resolver, advice.marketCache);
            missingMarketPrice |= marketPrice <= 0;
            // 已接受或超限药材不因另一个配方的缺口再次涨价，重复采购药材适用相同规则。
            if (advice.proposedPrices.containsKey(shortage.name) || advice.backlogNames.contains(shortage.name)) {
                continue;
            }
            Integer buyPrice = advice.purchasePrices.get(shortage.name);
            if (marketPrice > 0 && (buyPrice == null || buyPrice <= 0 || buyPrice < marketPrice)) {
                preferredPrices.put(shortage.name, chooseSuggestedPartnerPrice(marketPrice));
                // 先预留每种药材的实际坊市价，再共同分配按坊市价分档的额外溢价。
                trialPrices.put(shortage.name, marketPrice);
            }
        }
        if (advice.finalized) {
            if (!preferredPrices.isEmpty()) {
                return RecipePricing.blocked("此方案的涨价未通过整批收益校验；仅供查看搭配，不生成采购命令",
                        missingMarketPrice);
            }
            long profit = revenue - currentCost;
            if (profit < advice.minProfit) {
                return RecipePricing.blocked("当前整炉成本未达到收益底线，不生成此方案采购命令", missingMarketPrice);
            }
            Map<String, Integer> accepted = new LinkedHashMap<>();
            for (RecipeShortage shortage : plan.shortages) {
                Integer price = advice.proposedPrices.get(shortage.name);
                if (price != null && !advice.backlogNames.contains(shortage.name)) {
                    accepted.put(shortage.name, price);
                }
            }
            return new RecipePricing(true, missingMarketPrice, accepted,
                    "按本次最终建议价预计净收益" + profit + "万/炉，收益底线" + advice.minProfit + "万/炉");
        }
        List<Recipe> constraints = new ArrayList<>(advice.protectedRecipes);
        constraints.add(plan.recipe);
        for (Recipe recipe : constraints) {
            long profit = recipeRevenue(recipe, advice) - recipeCost(recipe, trialPrices, advice);
            if (profit < advice.minProfit) {
                return RecipePricing.blocked("坊市/采购成本超过收益预算，或与已建议配方冲突；不生成此方案采购命令",
                        missingMarketPrice);
            }
        }
        Map<String, Integer> proposals = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> preferred : preferredPrices.entrySet()) {
            long safePrice = preferred.getValue();
            for (Recipe recipe : constraints) {
                int need = recipe.needByName.getOrDefault(preferred.getKey(), 0);
                if (need <= 0) continue;
                long cost = recipeCost(recipe, trialPrices, advice);
                long otherCost = cost - (long) need * trialPrices.get(preferred.getKey());
                safePrice = Math.min(safePrice, (recipeRevenue(recipe, advice) - otherCost - advice.minProfit) / need);
            }
            int suggestedPrice = (int) safePrice;
            trialPrices.put(preferred.getKey(), suggestedPrice);
            proposals.put(preferred.getKey(), suggestedPrice);
        }
        long expectedProfit = revenue - recipeCost(plan.recipe, trialPrices, advice);
        String status = "预计净收益" + expectedProfit + "万/炉，收益底线" + advice.minProfit + "万/炉";
        if (missingMarketPrice) status += "（部分坊市价缺失，按现有采购价计算）";
        return new RecipePricing(true, missingMarketPrice, proposals, status);
    }

    private long recipeCost(Recipe recipe, Map<String, Integer> proposedPrices, PriceAdvice advice) {
        long cost = 0L;
        for (Map.Entry<String, Integer> requirement : recipe.needByName.entrySet()) {
            Integer price = proposedPrices.get(requirement.getKey());
            if (price == null) price = advice.purchasePrices.get(requirement.getKey());
            if (price == null || price <= 0) {
                price = resolveMarketPrice(requirement.getKey(), advice.resolver, advice.marketCache);
            }
            // 旧丹方中的单价无法代表当前采购成本；当前价格缺失时禁止推导安全上限。
            if (price <= 0) return -1L;
            cost += (long) requirement.getValue() * price;
        }
        return cost;
    }

    private long recipeRevenue(Recipe recipe, PriceAdvice advice) {
        if (recipe.alchemy) {
            return recipe.incomeKnown ? recipe.cost + recipe.profit : 0L;
        }
        int danPrice = resolveMarketPrice(recipe.danName, advice.resolver, advice.marketCache);
        if (danPrice <= 0 || recipe.danCount <= 0) return 0L;
        double feeRate = top.sshh.qqbot.service.utils.Utils.calculateFeeRate(danPrice);
        return (long) (danPrice * (1D - feeRate) * recipe.danCount);
    }

    private String formatShortage(RecipeShortage shortage,
                                  Map<String, Integer> purchasePrices,
                                  MarketPriceResolver marketPriceResolver,
                                  Map<String, Integer> marketPriceCache) {
        Integer buyPrice = purchasePrices.get(shortage.name);
        int marketPrice = resolveMarketPrice(shortage.name, marketPriceResolver, marketPriceCache);
        return "【" + shortage.name + "】现有" + shortage.currentCount + "/目标"
                + shortage.requiredCount + "，缺" + shortage.missingCount + "；采购价"
                + formatPrice(buyPrice) + "，坊市价" + formatMarketPrice(marketPrice);
    }

    private String formatShortageSummary(Map<String, RecipeShortage> summary,
                                         Map<String, Integer> purchasePrices,
                                         MarketPriceResolver marketPriceResolver,
                                         Map<String, Integer> marketPriceCache) {
        if (summary == null || summary.isEmpty()) {
            return "无";
        }
        List<RecipeShortage> shortages = new ArrayList<>(summary.values());
        int shownCount = Math.min(MAX_SHORTAGE_SUMMARY, shortages.size());
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < shownCount; i++) {
            texts.add(formatShortage(shortages.get(i), purchasePrices, marketPriceResolver, marketPriceCache));
        }
        if (shownCount < shortages.size()) {
            texts.add("共有" + shortages.size() + "种短缺搭配药，另" + (shortages.size() - shownCount)
                    + "种已参与计算，本消息省略明细");
        }
        return String.join("\n", texts);
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
        long threshold = (long) marketPrice + configuredPriceOffset;
        if (buyPrice <= marketPrice) {
            return " 本药材当前采购价" + formatPrice(buyPrice) + "，坊市价" + formatMarketPrice(marketPrice)
                    + "，无需降低采购价。";
        }
        if (shouldOutputSelfSuggestion(buyPrice, marketPrice, configuredPriceOffset)) {
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
                                Set<String> commands, boolean recipeMissing,
                                PriceAdvice advice, Map<String, Integer> repeatPricesForDisplay) {
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
        if (!repeatPricesForDisplay.isEmpty()) {
            long repeatedBacklogCount = analyses.stream()
                    .filter(analysis -> repeatPricesForDisplay.containsKey(analysis.herb.name)).count();
            sb.append("\n超限重复采购药材：").append(repeatedBacklogCount).append("种（统一按普通采购规则建议）");
            sb.append("\n收益与建议价按普通采购价计算；重复采购配置不变。");
        }
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
            String name = analysis.herb.name;
            sb.append("价格：坊市价").append(formatMarketPrice(resolveMarketPrice(name, advice.resolver, advice.marketCache)))
                    .append("；普通采购价").append(formatPrice(advice.purchasePrices.get(name)));
            if (repeatPricesForDisplay.containsKey(name)) {
                sb.append("；重复采购价").append(formatPrice(repeatPricesForDisplay.get(name)));
            }
            sb.append("\n");
            sb.append("判断：").append(analysis.cause).append("\n");
            sb.append("关联配方：").append(analysis.recipeText).append("\n");
            sb.append("原因：").append(analysis.reason).append("\n");
            sb.append("建议：").append(analysis.suggestion).append("\n");
            sb.append("\n");
        }

        sb.append("可复制调整命令：\n");
        if (commands.isEmpty()) {
            sb.append("本次没有生成采购价调整命令。");
        } else {
            sb.append("请整批应用以下建议，预计收益按这批价格共同计算：\n");
            commands.forEach(command -> sb.append(command).append("\n"));
        }

        return sb.toString().trim();
    }

    private List<Recipe> loadRecipes(long botId) throws IOException {
        Path recipePath = resolveBotFile(botId, "炼丹配方.txt");
        if (!LocalDataFiles.exists(recipePath)) {
            return Collections.emptyList();
        }

        List<Recipe> recipes = new ArrayList<>();
        try (BufferedReader reader = LocalDataFiles.newBufferedReader(recipePath, StandardCharsets.UTF_8)) {
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

        long cost = 0;
        Matcher costMatcher = COST_PATTERN.matcher(line);
        if (costMatcher.find()) {
            try {
                cost = Long.parseLong(costMatcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (cost <= 0) {
            cost = ingredients.stream()
                    .mapToLong(ingredient -> (long) Math.max(ingredient.count, 0) * Math.max(ingredient.unitPrice, 0))
                    .sum();
        }

        String danLabel = "";
        Matcher danMatcher = DAN_LABEL_PATTERN.matcher(line.trim());
        if (danMatcher.find()) {
            danLabel = danMatcher.group();
        }
        return new Recipe(danLabel, profit, cost, profitMatcher.reset().find(),
                line.contains("炼金收益"), ingredients);
    }

    private Map<String, Integer> loadPurchasePrices(long botId) throws IOException {
        Path pricePath = resolveBotFile(botId, "药材价格.txt");
        if (!LocalDataFiles.exists(pricePath)) {
            return new LinkedHashMap<>();
        }

        Map<String, Integer> prices = new LinkedHashMap<>();
        try (BufferedReader reader = LocalDataFiles.newBufferedReader(pricePath, StandardCharsets.UTF_8)) {
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
            sb.append("，基准净收益").append(recipe.profit).append("w");
        }
        sb.append("）");
        return sb.toString();
    }

    private boolean shouldOutputSelfSuggestion(Integer selfBuyPrice, int selfMarketPrice, int configuredPriceOffset) {
        if (selfBuyPrice == null || selfBuyPrice <= 0 || selfMarketPrice <= 0) {
            return false;
        }
        return selfBuyPrice > selfMarketPrice
                && selfBuyPrice >= (long) selfMarketPrice + configuredPriceOffset;
    }

    private int chooseSuggestedPartnerPrice(int marketPrice) {
        if (marketPrice <= 0) return 0;
        int premium = marketPrice <= PARTNER_LOW_PRICE_LIMIT
                ? PARTNER_LOW_PRICE_PREMIUM : PARTNER_HIGH_PRICE_PREMIUM;
        return (int) Math.min(Integer.MAX_VALUE, (long) marketPrice + premium);
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
        private RecipePricing pricing;

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

    private static final class PriceAdvice {
        private final Map<String, Integer> purchasePrices;
        private final MarketPriceResolver resolver;
        private final Map<String, Integer> marketCache;
        private final int minProfit;
        private final Map<String, Integer> proposedPrices = new LinkedHashMap<>();
        private final Set<String> backlogNames = new LinkedHashSet<>();
        private final Set<Recipe> protectedRecipes = new LinkedHashSet<>();
        private boolean finalized;

        private PriceAdvice(Map<String, Integer> purchasePrices, MarketPriceResolver resolver,
                            Map<String, Integer> marketCache, int minProfit) {
            this.purchasePrices = purchasePrices;
            this.resolver = resolver;
            this.marketCache = marketCache;
            this.minProfit = minProfit;
        }
    }

    private static final class RecipePricing {
        private final boolean affordable;
        private final boolean missingPrice;
        private final Map<String, Integer> proposals;
        private final String status;

        private RecipePricing(boolean affordable, boolean missingPrice,
                              Map<String, Integer> proposals, String status) {
            this.affordable = affordable;
            this.missingPrice = missingPrice;
            this.proposals = proposals;
            this.status = status;
        }

        private static RecipePricing blocked(String status, boolean missingPrice) {
            return new RecipePricing(false, missingPrice, Collections.emptyMap(), status);
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
        private final long cost;
        private final boolean incomeKnown;
        private final boolean alchemy;
        private final String danName;
        private final int danCount;
        private final List<Ingredient> ingredients;
        private final Map<String, Integer> needByName;

        private Recipe(String danLabel, int profit, long cost, boolean incomeKnown,
                       boolean alchemy, List<Ingredient> ingredients) {
            this.danLabel = danLabel;
            this.profit = profit;
            this.cost = cost;
            this.incomeKnown = incomeKnown;
            this.alchemy = alchemy;
            Matcher label = DAN_LABEL_PATTERN.matcher(danLabel);
            boolean hasLabel = label.find();
            this.danCount = hasLabel ? Integer.parseInt(label.group(1)) : 0;
            this.danName = hasLabel ? label.group(2) : "";
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
