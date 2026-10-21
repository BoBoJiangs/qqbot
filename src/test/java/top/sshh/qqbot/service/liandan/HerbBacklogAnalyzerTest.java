package top.sshh.qqbot.service.liandan;

import top.sshh.qqbot.service.storage.LocalDataFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.ProductPrice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HerbBacklogAnalyzerTest {
    private static final long BOT_ID = 1001L;

    @TempDir
    Path tempDir;

    @Test
    void analyze_reportsPartnerPriceTooLowWhenMatchedRecipeLacksPartner() throws Exception {
        writeBotFiles(
                "炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益60 6丹 测试丹",
                "100 天问花",
                "50 搭配草",
                "50 辅草"
        );

        Map<String, ProductPrice> herbPack = new LinkedHashMap<>();
        herbPack.put("天问花", product("天问花", 10));
        herbPack.put("辅草", product("辅草", 1));

        Map<String, Integer> marketPrices = new HashMap<>();
        marketPrices.put("天问花", 90);
        marketPrices.put("搭配草", 80);

        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(), herbPack, Collections.emptyMap(), marketPrices::get);

        assertTrue(output.contains("天问花 10/3"));
        assertTrue(output.contains("搭配药库存不足导致积压"));
        assertTrue(output.contains("要降至限制数量需炼7炉"));
        assertTrue(output.contains("【搭配草】现有0/目标7，缺7；采购价50万，坊市价80万"));
        assertTrue(output.contains("【辅草】现有1/目标7，缺6"));
        assertTrue(output.contains("采购药材天问花 90"));
        assertTrue(output.contains("采购药材搭配草 90"));
    }

    @Test
    void analyze_includesAlternativeRecipeShortagesInsteadOfOnlyBestRecipe() throws Exception {
        writeBotFiles(
                "炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益60 6丹 甲丹",
                "100 天问花"
        );
        LocalDataFiles.write(tempDir.resolve(String.valueOf(BOT_ID)).resolve("炼丹配方.txt"),
                Collections.singletonList("主药天问花-1&100 药引灵草-1&40 辅药灵木-1&40 花费180 炼金收益55 6丹 乙丹"),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Map<String, ProductPrice> herbPack = new LinkedHashMap<>();
        herbPack.put("天问花", product("天问花", 10));

        Map<String, Integer> marketPrices = new HashMap<>();
        marketPrices.put("天问花", 90);
        marketPrices.put("搭配草", 80);
        marketPrices.put("辅草", 70);
        marketPrices.put("灵草", 60);
        marketPrices.put("灵木", 50);

        String output = new HerbBacklogAnalyzer(tempDir).analyze(
                BOT_ID, config(), herbPack, Collections.emptyMap(), marketPrices::get);
        assertTrue(output.contains("候选搭配方案2个"));
        assertTrue(output.contains("搭配草"));
        assertTrue(output.contains("灵草"));
        assertTrue(output.contains("灵木"));
        Map<String, Integer> suggestions = commands(output);
        assertTrue(suggestions.containsKey("灵草"), output);
        assertTrue(suggestions.containsKey("灵木"), output);
        assertTrue(235L - 90 - suggestions.get("灵草") - suggestions.get("灵木") >= 30, output);
    }

    @Test
    void analyze_capsPartnerPriceAndExplainsWhenTargetPremiumWouldRemoveProfit() throws Exception {
        writeBotFiles(
                "炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益55 6丹 测试丹",
                "100 天问花",
                "50 辅草"
        );

        Map<String, ProductPrice> herbPack = new LinkedHashMap<>();
        herbPack.put("天问花", product("天问花", 10));
        Map<String, Integer> marketPrices = new HashMap<>();
        marketPrices.put("天问花", 90);
        marketPrices.put("搭配草", 80);

        String output = new HerbBacklogAnalyzer(tempDir).analyze(
                BOT_ID, config(), herbPack, Collections.emptyMap(), marketPrices::get);

        assertTrue(output.contains("收益底线30万/炉"));
        assertEquals(Map.of("天问花", 90, "搭配草", 85), commands(output));
        assertTrue(output.contains("联合建议价85万（目标价90万，受整批收益预算限制）"), output);
        assertTrue(255L - 90 - 50 - commands(output).get("搭配草") >= 30, output);
    }

    @ParameterizedTest
    @CsvSource({"1, 11", "499, 509", "500, 510", "501, 531", "1000, 1030"})
    void partnerTargetPremiumUsesMarketPriceTierIncludingFiveHundred(int marketPrice, int expectedPrice) throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益10000 6丹 测试丹",
                "100 天问花", "50 辅草");
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", marketPrice, "辅草", 50);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("天问花", product("天问花", 10)), Collections.emptyMap(),
                Map.of("搭配草", 5000), market::get);

        assertEquals(Map.of("搭配草", expectedPrice), commands(output));
        assertTrue(output.contains("坊市价≤500万加10万、>500万加30万"), output);
        assertTrue(output.contains("联合建议价" + expectedPrice + "万"), output);
        assertFalse(output.contains("受整批收益预算限制）"), output);
    }

    @Test
    void mixedPriceTiersShareOneProfitableBatchWithoutChangingBacklogReductionRule() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益1000 6丹 测试丹",
                "130 天问花", "50 搭配草", "50 辅草");
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 500, "辅草", 501);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("天问花", product("天问花", 10)), Collections.emptyMap(),
                Map.of("天问花", 900, "搭配草", 900, "辅草", 900), market::get);

        assertEquals(Map.of("天问花", 100, "搭配草", 510, "辅草", 531), commands(output));
        assertTrue(output.contains("预计净收益59万/炉"), output);
    }

    @Test
    void analyze_skipsRecipeWithNoPositiveProfit() throws Exception {
        writeBotFiles(
                "炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益0 6丹 测试丹",
                "100 天问花", "50 搭配草", "50 辅草"
        );

        Map<String, ProductPrice> herbPack = new LinkedHashMap<>();
        herbPack.put("天问花", product("天问花", 10));
        Map<String, Integer> marketPrices = new HashMap<>();
        marketPrices.put("天问花", 100);
        marketPrices.put("搭配草", 50);
        marketPrices.put("辅草", 50);

        String output = new HerbBacklogAnalyzer(tempDir).analyze(
                BOT_ID, config(), herbPack, Collections.emptyMap(), marketPrices::get);

        assertTrue(output.contains("无可盈利消耗配方"));
        assertTrue(output.contains("继续提高搭配药采购价可能导致炼丹亏损"));
        assertFalse(output.contains("采购药材搭配草"));
    }

    @Test
    void analyze_reportsSelfPriceTooHighWhenNoRecipeConsumesHerb() throws Exception {
        writeBotFiles(
                "炼金丹配方",
                "主药别草-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益60 6丹 测试丹",
                "100 无用草"
        );

        Map<String, ProductPrice> herbPack = new LinkedHashMap<>();
        herbPack.put("无用草", product("无用草", 10));

        Map<String, Integer> marketPrices = new HashMap<>();
        marketPrices.put("无用草", 90);

        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(), herbPack, Collections.emptyMap(), marketPrices::get);

        assertTrue(output.contains("无可用消耗配方"));
        assertTrue(output.contains("当前炼丹配方中没有消耗【无用草】的组合"));
        assertTrue(output.contains("采购药材无用草 90"));
    }

    @Test
    void analyze_reportsReadyButNotConsumedWhenRecipeCanCraft() throws Exception {
        writeBotFiles(
                "炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益60 6丹 测试丹",
                "100 天问花",
                "50 搭配草",
                "50 辅草"
        );

        Map<String, ProductPrice> herbPack = new LinkedHashMap<>();
        herbPack.put("天问花", product("天问花", 10));
        herbPack.put("搭配草", product("搭配草", 7));
        herbPack.put("辅草", product("辅草", 7));

        Map<String, Integer> marketPrices = new HashMap<>();
        marketPrices.put("天问花", 90);

        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(), herbPack, Collections.emptyMap(), marketPrices::get);

        assertTrue(output.contains("库存可消化但未执行"));
        assertTrue(output.contains("要降至限制数量需炼7炉，当前库存可炼7炉"));
        assertTrue(output.contains("采购药材天问花 90"));
    }

    @Test
    void analyze_reportsBacklogHerbWithoutCommandWhenSelfPriceBelowAdjustThreshold() throws Exception {
        writeBotFiles(
                "炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益60 6丹 测试丹",
                "70 天问花",
                "50 搭配草",
                "50 辅草"
        );

        Map<String, ProductPrice> herbPack = new LinkedHashMap<>();
        herbPack.put("天问花", product("天问花", 10));

        Map<String, Integer> marketPrices = new HashMap<>();
        marketPrices.put("天问花", 100);

        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(), herbPack, Collections.emptyMap(), marketPrices::get);

        assertTrue(output.contains("天问花 10/3"));
        assertTrue(output.contains("无需降低采购价"));
        assertFalse(output.contains("采购药材天问花"));
    }

    @Test
    void analyze_reportsEveryOverLimitHerbInSummary() throws Exception {
        writeBotFiles(
                "炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益60 6丹 测试丹",
                "70 天问花",
                "100 无用草",
                "50 搭配草",
                "50 辅草"
        );

        Map<String, ProductPrice> herbPack = new LinkedHashMap<>();
        herbPack.put("天问花", product("天问花", 10));
        herbPack.put("无用草", product("无用草", 8));

        Map<String, Integer> marketPrices = new HashMap<>();
        marketPrices.put("天问花", 100);
        marketPrices.put("无用草", 90);

        String output = new HerbBacklogAnalyzer(tempDir).analyze(
                BOT_ID, config(), herbPack, Collections.emptyMap(), marketPrices::get);

        assertTrue(output.contains("已解析药材：2种"));
        assertTrue(output.contains("检测到超限药材：2种"));
        assertTrue(output.contains("天问花 10/3"));
        assertTrue(output.contains("无用草 8/3"));
    }

    @Test
    void jointPriceSuggestionsReserveProfitForAllMissingIngredients() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益80 6丹 测试丹",
                "100 天问花", "50 搭配草", "50 辅草");
        Map<String, ProductPrice> pack = Map.of("天问花", product("天问花", 10));
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 70, "辅草", 70);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(), pack,
                Collections.emptyMap(), market::get);

        Map<String, Integer> commands = commands(output);
        assertEquals(2, commands.size());
        assertTrue(commands.get("搭配草") >= 70);
        assertTrue(commands.get("辅草") >= 70);
        long profit = 280L - 100 - commands.get("搭配草") - commands.get("辅草");
        assertTrue(profit >= 30, output);
        assertTrue(output.contains("预计净收益" + profit + "万/炉"), output);
    }

    @Test
    void sharedHerbPriceMustNotBreakAnEarlierRecipe() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益20 6丹 甲丹",
                "100 天问花", "50 搭配草", "50 辅草", "50 灵木");
        appendRecipe("主药天问花-1&100 药引搭配草-2&50 辅药灵木-1&50 花费250 炼金收益100 6丹 乙丹");
        Map<String, ProductPrice> pack = Map.of("天问花", product("天问花", 5),
                "搭配草", product("搭配草", 2), "辅草", product("辅草", 1), "灵木", product("灵木", 2));
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 80, "辅草", 60, "灵木", 50);
        Config settings = config();
        settings.setAlchemyNumber(1);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, settings, pack,
                Collections.emptyMap(), market::get);

        Map<String, Integer> commands = commands(output);
        assertEquals(69, commands.get("辅草"));
        assertFalse(commands.containsKey("搭配草"), output);
        assertTrue(output.contains("预计净收益1万/炉"), output);
    }

    @Test
    void doesNotChangePartnerPriceAlreadyAboveMarket() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益100 6丹 测试丹",
                "100 天问花", "90 搭配草", "50 辅草");
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 80, "辅草", 50);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("天问花", product("天问花", 10)), Collections.emptyMap(), market::get);
        assertTrue(commands(output).isEmpty(), output);
    }

    @Test
    void blocksPurchaseWhenProfitablePriceWouldBeBelowMarket() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益30 6丹 测试丹",
                "100 天问花", "50 搭配草", "50 辅草");
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 120, "辅草", 100);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("天问花", product("天问花", 10)), Collections.emptyMap(), market::get);
        assertTrue(commands(output).isEmpty(), output);
        assertTrue(output.contains("无可盈利消耗配方"), output);
    }

    @Test
    void missingCurrentPriceCannotBeReplacedByAnOldRecipePrice() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益100 6丹 测试丹",
                "100 天问花", "50 搭配草");
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 80);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("天问花", product("天问花", 10)), Collections.emptyMap(), market::get);
        assertTrue(commands(output).isEmpty(), output);
        assertTrue(output.contains("价格数据不足"), output);
    }

    @Test
    void negativeOffsetDoesNotRaiseTheOverstockedHerbPrice() throws Exception {
        writeBotFiles("炼金丹配方", "", "90 天问花");
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("天问花", product("天问花", 10)), Collections.emptyMap(), name -> 100);
        assertTrue(commands(output).isEmpty(), output);
    }

    @Test
    void usesCurrentMarketDanRevenueInsteadOfOldRecipeProfit() throws Exception {
        writeBotFiles("坊市丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 坊市收益1000 6丹 测试丹",
                "100 天问花", "50 搭配草", "50 辅草");
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 80, "辅草", 80, "测试丹", 30);
        Config settings = config();
        settings.setAlchemy(false);
        settings.setMakeNumber(1);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, settings,
                Map.of("天问花", product("天问花", 10)), Collections.emptyMap(), market::get);
        assertTrue(commands(output).isEmpty(), output);
        assertTrue(output.contains("无可盈利消耗配方"), output);
    }

    @Test
    void ordinaryPriceRemainsTheCostWhenRepeatedPriceIsLower() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益80 6丹 测试丹",
                "100 天问花", "90 搭配草", "50 辅草");
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 80, "辅草", 60);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("天问花", product("天问花", 10)), Collections.emptyMap(),
                Map.of("搭配草", 10), market::get);
        Map<String, Integer> prices = commands(output);
        assertFalse(prices.containsKey("搭配草"), output);
        assertEquals(60, prices.get("辅草"));
        assertTrue(280L - 100 - 90 - prices.get("辅草") >= 30, output);
    }

    @Test
    void repeatedBacklogHerbsReceiveTheSameOrdinaryPriceAdvice() throws Exception {
        writeBotFiles("炼金丹配方", "", "100 天问花", "950 玄冰花");
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("天问花", product("天问花", 10), "玄冰花", product("玄冰花", 76)),
                Collections.emptyMap(), Map.of("玄冰花", 900), name -> 80);

        assertEquals(Map.of("天问花", 80, "玄冰花", 80), commands(output));
        assertTrue(output.contains("检测到超限药材：2种"), output);
        assertTrue(output.contains("超限重复采购药材：1种（统一按普通采购规则建议）"), output);
        assertTrue(output.contains("生成调价建议：2种"), output);
        assertTrue(output.contains("玄冰花 76/3"), output);
        assertTrue(output.contains("价格：坊市价80万；普通采购价950万；重复采购价900万"), output);
        assertFalse(output.contains("采购价锁定"), output);
        assertFalse(output.contains("重复采购药材玄冰花"), output);
    }

    @Test
    void onlyRepeatedHerbsOverLimitStillShowRecipesAndOrdinaryPartnerPriceAdvice() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药玄冰花-1&900 药引搭配草-1&50 辅药辅草-1&50 花费1000 炼金收益1000 6丹 测试丹",
                "900 玄冰花", "50 搭配草", "50 辅草");
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("玄冰花", product("玄冰花", 76)), Collections.emptyMap(),
                Map.of(" 玄冰花 ", 900), name -> 80);

        assertTrue(output.startsWith("背包药材分析完成\n"), output);
        assertTrue(output.contains("玄冰花 76/3"), output);
        assertTrue(output.contains("关联配方：6丹 测试丹"), output);
        assertTrue(output.contains("【搭配草】现有0/目标73，缺73"), output);
        assertEquals(Map.of("玄冰花", 80, "搭配草", 90, "辅草", 90), commands(output));
        assertFalse(output.contains("采购价格保持不变"), output);
    }

    @Test
    void repeatedPartnersShareTheSameOrdinaryPriceProfitBudget() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益80 6丹 测试丹",
                "100 天问花", "10 搭配草", "50 辅草");
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 70, "辅草", 70);
        Map<String, ProductPrice> pack = Map.of("天问花", product("天问花", 10));
        HerbBacklogAnalyzer analyzer = new HerbBacklogAnalyzer(tempDir);
        String ordinary = analyzer.analyze(BOT_ID, config(), pack, Collections.emptyMap(), market::get);
        String output = analyzer.analyze(BOT_ID, config(), pack, Collections.emptyMap(),
                Map.of("搭配草", 50, "辅草", 5000), market::get);

        Map<String, Integer> prices = commands(output);
        assertEquals(commands(ordinary), prices, output);
        assertEquals(2, prices.size(), output);
        assertTrue(prices.get("搭配草") >= 70 && prices.get("搭配草") <= 80, output);
        assertTrue(prices.get("辅草") >= 70 && prices.get("辅草") <= 80, output);
        long profit = 280L - 100 - prices.get("搭配草") - prices.get("辅草");
        assertTrue(profit >= 30, output);
        assertTrue(output.contains("预计净收益" + profit + "万/炉"), output);
        assertFalse(output.contains("不参与调价"), output);
        assertFalse(output.contains("非重复采购搭配药"), output);
    }

    @Test
    void expensiveRepeatedPriceDoesNotOverrideOrdinaryBacklogReductionOrBlockProfit() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益80 6丹 测试丹",
                "100 天问花", "200 搭配草", "50 辅草");
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 20, "辅草", 80);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("天问花", product("天问花", 10), "搭配草", product("搭配草", 4)),
                Collections.emptyMap(), Map.of("搭配草", 2000), market::get);

        assertEquals(Map.of("搭配草", 20, "辅草", 90), commands(output));
        assertFalse(output.contains("无可盈利消耗配方"), output);
        assertTrue(output.contains("价格：坊市价20万；普通采购价200万；重复采购价2000万"), output);
        assertTrue(280L - 100 - commands(output).get("搭配草") - commands(output).get("辅草") >= 30, output);
        assertTrue(output.contains("检测到超限药材：2种"), output);
    }

    @Test
    void repeatedHerbsAreAnalyzedAlongsideOrdinaryHerbsAndContributeTheirInventory() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益80 6丹 测试丹",
                "100 天问花", "50 搭配草", "50 辅草");
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 80, "辅草", 80);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("天问花", product("天问花", 10), "搭配草", product("搭配草", 7), "辅草", product("辅草", 7)),
                Collections.emptyMap(), Map.of("搭配草", 50, "辅草", 50), market::get);

        assertTrue(commands(output).isEmpty(), output);
        assertTrue(output.contains("库存可消化但未执行"), output);
        assertTrue(output.contains("当前库存可炼7炉"), output);
        assertTrue(output.contains("检测到超限药材：3种"), output);
        assertTrue(output.contains("超限重复采购药材：2种（统一按普通采购规则建议）"), output);
        assertTrue(output.contains("搭配草 7/3"), output);
        assertTrue(output.contains("辅草 7/3"), output);
    }

    @Test
    void summaryAndDetailsShowDistinctCurrentMarketOrdinaryAndRepeatPrices() throws Exception {
        writeBotFiles("炼金丹配方", "", "100 玄冰花");
        ProductPrice runtimePrice = product("玄冰花", 0);
        runtimePrice.setPrice(150);
        Map<String, ProductPrice> runtimePrices = Map.of("玄冰花", runtimePrice);
        Map<String, Integer> repeats = Map.of(" 玄冰花 ", 2000);
        Map<String, Integer> lookupCounts = new HashMap<>();

        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("玄冰花", product("玄冰花", 76)), runtimePrices, repeats, name -> {
                    lookupCounts.merge(name, 1, Integer::sum);
                    return 90;
                });
        HerbAnalysisMessageFormatter.Report report = HerbAnalysisMessageFormatter.format(output);
        String priceLine = "价格：坊市价90万；普通采购价150万；重复采购价2000万";
        assertTrue(String.join("\n", report.getSummaries()).contains(priceLine), output);
        assertTrue(String.join("\n", report.getDetails()).contains(priceLine), output);
        assertTrue(String.join("\n", report.getCommands()).contains("采购药材玄冰花 90"), output);
        assertEquals(Map.of("玄冰花", 90), commands(output));
        assertEquals(1, lookupCounts.get("玄冰花"), "展示价格应复用本次查询缓存");
        assertEquals(150, runtimePrice.getPrice(), "分析不自动修改普通采购价");
        assertEquals(2000, repeats.get(" 玄冰花 "), "分析不自动修改重复采购价");
    }

    @Test
    void repeatPriceIsNotUsedAsFallbackForMissingOrdinaryAndMarketPrices() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药玄冰花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益80 6丹 测试丹",
                "50 搭配草", "50 辅草");
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("玄冰花", product("玄冰花", 76)), Collections.emptyMap(),
                Map.of("玄冰花", 900), name -> null);

        assertTrue(output.contains("价格：坊市价未查询到；普通采购价未设置；重复采购价900万"), output);
        assertTrue(output.contains("价格数据不足，无法确认收益"), output);
        assertTrue(commands(output).isEmpty(), output);
    }

    @Test
    void repeatedPartnersAreStillBlockedWhenOrdinaryJointCostCannotMeetProfitFloor() throws Exception {
        writeBotFiles("炼金丹配方",
                "主药天问花-1&100 药引搭配草-1&50 辅药辅草-1&50 花费200 炼金收益80 6丹 测试丹",
                "100 天问花", "10 搭配草", "50 辅草");
        Map<String, Integer> market = Map.of("天问花", 100, "搭配草", 100, "辅草", 80);
        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(),
                Map.of("天问花", product("天问花", 10)), Collections.emptyMap(),
                Map.of("搭配草", 1, "辅草", 1), market::get);

        assertTrue(commands(output).isEmpty(), output);
        assertTrue(output.contains("无可盈利消耗配方"), output);
    }

    private Map<String, Integer> commands(String output) {
        Map<String, Integer> commands = new LinkedHashMap<>();
        String summary = output.substring(output.lastIndexOf("可复制调整命令："));
        Matcher matcher = Pattern.compile("(?m)^采购药材(\\S+) (\\d+)$").matcher(summary);
        while (matcher.find()) {
            String name = matcher.group(1);
            assertFalse(commands.containsKey(name), "同一药材生成了多个价格：" + output);
            commands.put(name, Integer.parseInt(matcher.group(2)));
        }
        return commands;
    }

    private void appendRecipe(String recipe) throws Exception {
        LocalDataFiles.write(tempDir.resolve(String.valueOf(BOT_ID)).resolve("炼丹配方.txt"),
                Collections.singletonList(recipe), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private void writeBotFiles(String recipeHeader, String recipeLine, String... priceLines) throws Exception {
        Path botDir = tempDir.resolve(String.valueOf(BOT_ID));
        Files.createDirectories(botDir);
        LocalDataFiles.write(botDir.resolve("炼丹配方.txt"), Arrays.asList(recipeHeader, recipeLine), StandardCharsets.UTF_8);
        LocalDataFiles.write(botDir.resolve("药材价格.txt"), Arrays.asList(priceLines), StandardCharsets.UTF_8);
    }

    private Config config() {
        Config config = new Config();
        config.setLimitHerbsCount(3);
        config.setAddPrice(-20);
        return config;
    }

    private ProductPrice product(String name, int count) {
        ProductPrice productPrice = new ProductPrice();
        productPrice.setName(name);
        productPrice.setHerbCount(count);
        return productPrice;
    }
}
