package top.sshh.qqbot.service.liandan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.constant.Constant;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.ProductPrice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoBuyHerbsRepeatPurchaseTest {
    private static final long BOT_ID = 1001L;

    @TempDir
    Path tempDir;

    private String originalTargetDir;

    @BeforeEach
    void setUp() {
        originalTargetDir = Constant.targetDir;
        Constant.targetDir = tempDir.toString();
    }

    @AfterEach
    void tearDown() {
        Constant.targetDir = originalTargetDir;
    }

    @Test
    void repeatPurchaseConfigCanBePersistedAndLoaded() throws Exception {
        AutoBuyHerbs writer = new AutoBuyHerbs();
        Set<String> configured = repeatHerbs(writer);
        configured.add("乌灵参");
        repeatPrices(writer).put("乌灵参", 80);
        ReflectionTestUtils.invokeMethod(writer, "saveRepeatBuyConfig", BOT_ID);

        Path configPath = tempDir.resolve(String.valueOf(BOT_ID)).resolve("重复采购药材.txt");
        assertTrue(Files.exists(configPath));
        assertEquals("80 乌灵参", Files.readAllLines(configPath, StandardCharsets.UTF_8).get(0));
        assertFalse(Files.exists(tempDir.resolve(String.valueOf(BOT_ID)).resolve("药材价格.txt")));

        AutoBuyHerbs reader = new AutoBuyHerbs();
        assertTrue(repeatHerbs(reader).contains("乌灵参"));
        assertEquals(80, repeatPrices(reader).get("乌灵参"));
    }

    @Test
    void marketPriceMustBeLessThanOrEqualToConfiguredPrice() {
        AutoBuyHerbs service = new AutoBuyHerbs();
        ProductPrice rule = product("乌灵参", 80);

        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                service, "isMarketPriceAllowed", 80D, rule)));
        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                service, "isMarketPriceAllowed", 79D, rule)));
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                service, "isMarketPriceAllowed", 81D, rule)));
    }

    @Test
    void repeatPurchaseUsesOneCandidatePerHerbAndKeepsCodeSnapshot() {
        AutoBuyHerbs service = new AutoBuyHerbs();
        enableRepeat(service, "乌灵参", 80);

        ProductPrice rule = product("乌灵参", 80);
        ProductPrice first = candidate(service, rule, "code-1", 60);
        ProductPrice second = candidate(service, rule, "code-2", 55);

        ReflectionTestUtils.invokeMethod(service, "enqueuePurchaseCandidate", BOT_ID, first);
        ReflectionTestUtils.invokeMethod(service, "enqueuePurchaseCandidate", BOT_ID, second);

        Map<Long, CopyOnWriteArrayList<ProductPrice>> queues =
                field(service, "autoBuyListMap");
        CopyOnWriteArrayList<ProductPrice> queue = queues.get(BOT_ID);
        assertEquals(1, queue.size());
        assertEquals("code-1", queue.get(0).getCode());

        rule.setCode("new-config-code");
        assertEquals("code-1", queue.get(0).getCode());
    }

    @Test
    void repeatPurchaseIsPrioritizedOverNormalPurchaseOnSamePage() {
        AutoBuyHerbs service = new AutoBuyHerbs();
        enableRepeat(service, "乌灵参", 80);

        ProductPrice normal = candidate(service, product("血灵芝", 120), "normal-code", 20);
        normal.setId(1L);
        ProductPrice repeat = candidate(service, product("乌灵参", 80), "repeat-code", 70);
        repeat.setId(2L);

        ReflectionTestUtils.invokeMethod(service, "enqueuePurchaseCandidate", BOT_ID, normal);
        ReflectionTestUtils.invokeMethod(service, "enqueuePurchaseCandidate", BOT_ID, repeat);

        @SuppressWarnings("unchecked")
        Map<Long, CopyOnWriteArrayList<ProductPrice>> queues =
                (Map<Long, CopyOnWriteArrayList<ProductPrice>>) ReflectionTestUtils.getField(service, "autoBuyListMap");
        ReflectionTestUtils.invokeMethod(service, "sortPurchaseCandidates", BOT_ID, queues.get(BOT_ID));

        assertEquals("乌灵参", queues.get(BOT_ID).get(0).getName());
    }

    @Test
    void notFoundRemovesAllCandidatesForCurrentHerbOnly() {
        AutoBuyHerbs service = new AutoBuyHerbs();
        CopyOnWriteArrayList<ProductPrice> queue = new CopyOnWriteArrayList<>();
        queue.add(candidate(service, product("乌灵参", 80), "code-1", 60));
        queue.add(candidate(service, product("乌灵参", 80), "code-2", 59));
        queue.add(candidate(service, product("血灵芝", 120), "code-3", 100));

        ReflectionTestUtils.invokeMethod(service, "removeRepeatPurchaseCandidates", queue, queue.get(0));

        assertEquals(1, queue.size());
        assertEquals("血灵芝", queue.get(0).getName());
    }

    @Test
    void inventoryLimitStillRequiresAdditionalPriceReduction() {
        AutoBuyHerbs service = new AutoBuyHerbs();
        Config config = new Config();
        config.setLimitHerbsCount(3);
        config.setAddPrice(10);

        @SuppressWarnings("unchecked")
        Map<Long, Map<String, ProductPrice>> packs =
                (Map<Long, Map<String, ProductPrice>>) ReflectionTestUtils.getField(service, "herbPackMapMap");
        Map<String, ProductPrice> botPack = new java.util.concurrent.ConcurrentHashMap<>();
        ProductPrice pack = product("乌灵参", 0);
        pack.setHerbCount(4);
        botPack.put("乌灵参", pack);
        packs.put(BOT_ID, botPack);

        ProductPrice rule = product("乌灵参", 100);
        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                service, "canPurchaseHerb", BOT_ID, rule, "乌灵参", 90D, config)));
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                service, "canPurchaseHerb", BOT_ID, rule, "乌灵参", 91D, config)));
    }

    private Set<String> repeatHerbs(AutoBuyHerbs service) {
        return ReflectionTestUtils.invokeMethod(service, "getRepeatBuyHerbs", BOT_ID);
    }

    private Map<String, Integer> repeatPrices(AutoBuyHerbs service) {
        return ReflectionTestUtils.invokeMethod(service, "getRepeatBuyPrices", BOT_ID);
    }

    private void enableRepeat(AutoBuyHerbs service, String herbName, int price) {
        repeatHerbs(service).add(herbName);
        repeatPrices(service).put(herbName, price);
    }

    private ProductPrice candidate(AutoBuyHerbs service, ProductPrice rule, String code, double marketPrice) {
        return ReflectionTestUtils.invokeMethod(service, "createPurchaseCandidate", rule, code, marketPrice);
    }

    private ProductPrice product(String name, int price) {
        ProductPrice product = new ProductPrice();
        product.setName(name);
        product.setPrice(price);
        return product;
    }

    @SuppressWarnings("unchecked")
    private <T> T field(AutoBuyHerbs service, String name) {
        return (T) ReflectionTestUtils.getField(service, name);
    }
}
