package top.sshh.qqbot.service.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import top.sshh.qqbot.data.ProductPrice;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MarketPriceUtilsTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void parsesSnowLumaImageHeaderWithoutPollutingItemName(boolean escapedNewlines) throws Exception {
        String card = new String(getClass().getResourceAsStream("/market/seven-color-orchid.md").readAllBytes(),
                StandardCharsets.UTF_8);
        if (!escapedNewlines) card = card.replace("\\n", "\n");

        ProductPrice product = MarketPriceUtils.parseMarketInfo(card);

        assertNotNull(product);
        assertEquals("七彩月兰", product.getName());
        assertEquals("药材", product.getCategory());
        assertEquals(580, product.getPrice());
        assertEquals(3800000L, product.getMarketMinPrice());
        assertEquals(4600000L, product.getMarketMaxPrice());
    }

    @ParameterizedTest
    @CsvSource({"!物品价格 #3321px #1724px七彩月兰,七彩月兰",
            "!物品价格 #100px #200px 离火梧桐芝,离火梧桐芝", "七彩月兰,七彩月兰"})
    void normalizesStoredImageTitlePrefixes(String stored, String expected) {
        assertEquals(expected, MarketPriceUtils.normalizeStoredMarketName(stored));
    }

    @Test
    void parsesMarketLimitsInsteadOfHistoricalHighAndLow() {
        ProductPrice product = MarketPriceUtils.parseMarketInfo(
                "离火梧桐芝 所在位置：坊市第6页\n分类：药材\n统计信息（每小时刷新）\n"
                        + "当前价格: 980万 点击上架 | 点击购买\n> 万宝楼每100积分收购价：393万\n"
                        + "平均价格: 986万 点击上架\n最高价格: 1080万\n最低价格: 720万\n"
                        + "市场限价：760.00万 ~ 1120.00万 | 🔺\n收藏此物品 | 我的收藏");

        assertNotNull(product);
        assertEquals("离火梧桐芝", product.getName());
        assertEquals("药材", product.getCategory());
        assertEquals(980, product.getPrice());
        assertEquals(7600000L, product.getMarketMinPrice());
        assertEquals(11200000L, product.getMarketMaxPrice());
    }

    @Test
    void parsesMarkdownAndMixedUnitsWithoutLosingFractionalWan() {
        ProductPrice product = MarketPriceUtils.parseMarketInfo(
                "@道友\n## **[离火梧桐芝](mqqapi://item)** 所在位置：坊市第6页\\\n"
                        + "当前价格：0.098亿\n市场限价：760.25万 \\~ 0.112075亿 | 🔺");

        assertNotNull(product);
        assertEquals("离火梧桐芝", product.getName());
        assertEquals(980, product.getPrice());
        assertEquals(7602500L, product.getMarketMinPrice());
        assertEquals(11207500L, product.getMarketMaxPrice());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "最高价格: 1080万\n最低价格: 720万", "市场限价：1120万 ~ 760万",
            "市场限价：0万 ~ 1120万", "市场限价：-760万 ~ 1120万",
            "市场限价：760万 ~ 无", "市场限价：760万",
            "市场限价：760万 ~ 999999999999999999亿"
    })
    void ignoresMissingOrMalformedMarketLimits(String limit) {
        assertNull(MarketPriceUtils.parseMarketInfo("离火梧桐芝 所在位置：坊市第6页\n" + limit));
    }

    @ParameterizedTest
    @CsvSource({"7000000,7602500", "7602500,7602500", "9800000,9800000",
            "11207500,11207500", "15000000,11207500"})
    void clampsToInclusiveBoundsInBaseCurrency(long proposed, long expected) {
        ProductPrice product = new ProductPrice();
        product.setMarketMinPrice(7602500L);
        product.setMarketMaxPrice(11207500L);
        assertEquals(expected, MarketPriceUtils.limitListingPrice(product, proposed));
    }

    @Test
    void leavesLegacyProductsWithoutLimitsUnchanged() {
        assertEquals(9700000L, MarketPriceUtils.limitListingPrice(new ProductPrice(), 9700000L));
    }
}
