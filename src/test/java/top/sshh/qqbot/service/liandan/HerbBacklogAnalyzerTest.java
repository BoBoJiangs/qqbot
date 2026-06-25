package top.sshh.qqbot.service.liandan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.ProductPrice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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
        assertTrue(output.contains("搭配药价格过低导致累积"));
        assertTrue(output.contains("搭配草当前采购价50万，最新坊市价80万"));
        assertTrue(output.contains("采购药材天问花 90"));
        assertTrue(output.contains("采购药材搭配草 80"));
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
        marketPrices.put("无用草", 120);

        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(), herbPack, Collections.emptyMap(), marketPrices::get);

        assertTrue(output.contains("本身采购价偏高导致累积"));
        assertTrue(output.contains("当前炼丹配方中没有消耗【无用草】的组合"));
        assertTrue(output.contains("采购药材无用草 120"));
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
        herbPack.put("搭配草", product("搭配草", 1));
        herbPack.put("辅草", product("辅草", 1));

        Map<String, Integer> marketPrices = new HashMap<>();
        marketPrices.put("天问花", 90);

        String output = new HerbBacklogAnalyzer(tempDir).analyze(BOT_ID, config(), herbPack, Collections.emptyMap(), marketPrices::get);

        assertTrue(output.contains("可炼但未消耗"));
        assertTrue(output.contains("采购药材天问花 90"));
    }

    @Test
    void analyze_ignoresBacklogHerbWhenSelfPriceBelowAdjustThreshold() throws Exception {
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

        assertTrue(output.contains("当前采购价未达到调价阈值"));
        assertFalse(output.contains("采购药材天问花"));
    }

    private void writeBotFiles(String recipeHeader, String recipeLine, String... priceLines) throws Exception {
        Path botDir = tempDir.resolve(String.valueOf(BOT_ID));
        Files.createDirectories(botDir);
        Files.write(botDir.resolve("炼丹配方.txt"), Arrays.asList(recipeHeader, recipeLine), StandardCharsets.UTF_8);
        Files.write(botDir.resolve("药材价格.txt"), Arrays.asList(priceLines), StandardCharsets.UTF_8);
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
