package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.constant.Constant;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.ProductPrice;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoBuyHerbsPurchaseConcurrencyTest {
    private static final long BOT_ID = 1001L;
    private static final long GROUP_ID = 2001L;

    @TempDir
    Path tempDir;

    private String originalTargetDir;
    private AutoBuyHerbs service;
    private Bot bot;
    private Group group;
    private BotConfig botConfig;
    private Config config;

    @BeforeEach
    void setUp() {
        originalTargetDir = Constant.targetDir;
        Constant.targetDir = tempDir.toString();
        service = new AutoBuyHerbs();
        service.danCalculator = mock(DanCalculator.class);
        config = new Config();
        config.setRandomDelay(0);
        when(service.danCalculator.getConfig(BOT_ID)).thenReturn(config);
        botConfig = new BotConfig();
        botConfig.setGroupId(GROUP_ID);
        botConfig.setAutoBuyHerbsMode(1);
        bot = mock(Bot.class);
        group = mock(Group.class);
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotConfig()).thenReturn(botConfig);
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        when(group.getGroupId()).thenReturn(GROUP_ID);
        AutoBuyHerbs.AUTO_BUY_HERBS.put(BOT_ID, new ConcurrentHashMap<>());
        rule("乌灵参", 80);
    }

    @AfterEach
    void tearDown() {
        ((ExecutorService) ReflectionTestUtils.getField(service, "customPool")).shutdownNow();
        AutoBuyHerbs.AUTO_BUY_HERBS.remove(BOT_ID);
        Constant.targetDir = originalTargetDir;
    }

    @Test
    void normalCandidatesWithSameCodeAreQueuedOnce() {
        ProductPrice rule = AutoBuyHerbs.AUTO_BUY_HERBS.get(BOT_ID).get("乌灵参");
        for (int i = 0; i < 2; i++) {
            ProductPrice candidate = ReflectionTestUtils.invokeMethod(
                    service, "createPurchaseCandidate", rule, "code-1", 60D);
            ReflectionTestUtils.invokeMethod(service, "enqueuePurchaseCandidate", BOT_ID, candidate);
        }

        assertEquals(1, queue().size());
    }

    @Test
    void repeatedMarketMessagesWaitForPurchaseReply() {
        market(row("乌灵参", "code-1", 60));
        market(row("乌灵参", "code-1", 60));

        assertEquals(List.of("坊市购买 code-1"), purchases());
        assertEquals(1, queue().size());
    }

    @Test
    void concurrentMarketMessagesSendOnlyOnePurchase() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = workers.submit(() -> processAfter(start));
            Future<?> second = workers.submit(() -> processAfter(start));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        assertEquals(List.of("坊市购买 code-1"), purchases());
        assertEquals(1, queue().size());
    }

    @Test
    void replyRemovesActualPendingPurchaseAfterNewCandidatesAreSorted() throws Exception {
        rule("血灵芝", 120);
        market(row("乌灵参", "code-1", 60));
        market(row("血灵芝", "code-2", 20));
        assertEquals(List.of("坊市购买 code-1"), purchases());

        success();

        assertEquals(List.of("坊市购买 code-1", "坊市购买 code-2"), purchases());
        assertEquals("code-2", queue().get(0).getCode());
        Map<Long, Map<String, ProductPrice>> packs = field("herbPackMapMap");
        assertEquals(1, packs.get(BOT_ID).get("乌灵参").getHerbCount());
        assertEquals(0, packs.get(BOT_ID).get("血灵芝").getHerbCount());
    }

    @Test
    void immediateMarketReplayAfterSuccessDoesNotBuyNormalCodeAgain() throws Exception {
        market(row("乌灵参", "code-1", 60));
        success();
        market(row("乌灵参", "code-1", 60));

        assertEquals(List.of("坊市购买 code-1"), purchases());
    }

    @Test
    void repeatPurchaseResendsSameCodeOnlyAfterReply() throws Exception {
        Map<String, Integer> repeatPrices = ReflectionTestUtils.invokeMethod(
                service, "getRepeatBuyPrices", BOT_ID);
        repeatPrices.put("乌灵参", 80);
        market(row("乌灵参", "code-1", 60));
        market(row("乌灵参", "code-1", 60));
        assertEquals(List.of("坊市购买 code-1"), purchases());

        success();

        assertEquals(List.of("坊市购买 code-1", "坊市购买 code-1"), purchases());
    }

    @Test
    void unsuccessfulSendDoesNotBlockNextAttempt() {
        when(bot.getGroup(GROUP_ID)).thenReturn(null);
        market(row("乌灵参", "code-1", 60));
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        ReflectionTestUtils.invokeMethod(service, "buyHerbs", bot);
        ReflectionTestUtils.invokeMethod(service, "buyHerbs", bot);

        assertEquals(List.of("坊市购买 code-1"), purchases());
    }

    @Test
    void marketMessageCannotBypassDelayBeforeNextPurchase() throws Exception {
        ExecutorService delayedExecutor = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "customPool", delayedExecutor);
        config.setRandomDelay(1);
        rule("血灵芝", 120);
        market(row("乌灵参", "code-1", 60));
        market(row("血灵芝", "code-2", 20));
        success();
        market(row("血灵芝", "code-2", 20));
        assertEquals(List.of("坊市购买 code-1"), purchases());

        ArgumentCaptor<Runnable> delayedTask = ArgumentCaptor.forClass(Runnable.class);
        verify(delayedExecutor).submit(delayedTask.capture());
        delayedTask.getValue().run();

        assertEquals(List.of("坊市购买 code-1", "坊市购买 code-2"), purchases());
    }

    @Test
    void resetDiscardsOldDelayedPurchase() throws Exception {
        ExecutorService delayedExecutor = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "customPool", delayedExecutor);
        config.setRandomDelay(1);
        rule("血灵芝", 120);
        market(row("乌灵参", "code-1", 60));
        market(row("血灵芝", "code-2", 20));
        success();
        ArgumentCaptor<Runnable> delayedTask = ArgumentCaptor.forClass(Runnable.class);
        verify(delayedExecutor).submit(delayedTask.capture());

        ReflectionTestUtils.invokeMethod(service, "resetPram", bot, botConfig);
        ProductPrice nextCandidate = ReflectionTestUtils.invokeMethod(service, "createPurchaseCandidate",
                AutoBuyHerbs.AUTO_BUY_HERBS.get(BOT_ID).get("血灵芝"), "code-2", 20D);
        ReflectionTestUtils.invokeMethod(service, "enqueuePurchaseCandidate", BOT_ID, nextCandidate);
        delayedTask.getValue().run();

        assertEquals(List.of("坊市购买 code-1"), purchases());
        ReflectionTestUtils.invokeMethod(service, "buyHerbs", bot);
        assertEquals(List.of("坊市购买 code-1", "坊市购买 code-2"), purchases());
    }

    @Test
    void normalCodeCanBePurchasedInLaterRoundAfterCooldown() throws Exception {
        market(row("乌灵参", "code-1", 60));
        success();
        Map<Long, Map<String, Long>> recentCodes = field("recentNormalBuyCodeMap");
        recentCodes.get(BOT_ID).put("code-1", System.currentTimeMillis() - 13000L);

        market(row("乌灵参", "code-1", 60));

        assertEquals(List.of("坊市购买 code-1", "坊市购买 code-1"), purchases());
    }

    private void processAfter(CountDownLatch start) {
        try {
            start.await();
            market(row("乌灵参", "code-1", 60));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private void rule(String name, int price) {
        ProductPrice rule = new ProductPrice();
        rule.setName(name);
        rule.setPrice(price);
        AutoBuyHerbs.AUTO_BUY_HERBS.get(BOT_ID).put(name, rule);
    }

    private String row(String name, String code, int price) {
        return "价格:" + price + "万 [" + name + "](mqqapi://test?command="
                + "%E5%9D%8A%E5%B8%82%E8%B4%AD%E4%B9%B0" + code + "&test=1)";
    }

    private void market(String rows) {
        ReflectionTestUtils.invokeMethod(service, "processMarketMessage", bot, group, rows);
    }

    private void success() throws InterruptedException {
        service.成功购买药材(bot, group, null, new MessageChain(),
                "道友成功购买乌灵参，消耗60万灵石", 123);
    }

    private List<String> purchases() {
        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, atLeastOnce()).sendMessage(sent.capture());
        return sent.getAllValues().stream()
                .map(chain -> chain.getMessageByType(TextMessage.class).stream()
                        .map(TextMessage::getText).collect(Collectors.joining()))
                .filter(text -> text.startsWith("坊市购买 "))
                .collect(Collectors.toList());
    }

    private CopyOnWriteArrayList<ProductPrice> queue() {
        Map<Long, CopyOnWriteArrayList<ProductPrice>> queues = field("autoBuyListMap");
        return queues.get(BOT_ID);
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) {
        return (T) ReflectionTestUtils.getField(service, name);
    }
}
