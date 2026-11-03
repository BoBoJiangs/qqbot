package top.sshh.qqbot.service.liandan;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.constant.Constant;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.Utils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoBuyHerbsSleepTest {
    private static final long BOT_ID = 812345L;
    private static final long GROUP_ID = 912345L;
    private static final String HEADER = "不鼓励不保障任何第三方交易行为\n";
    @TempDir Path tempDir;

    private final AutoBuyHerbs service = new AutoBuyHerbs();
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final BotConfig botConfig = new BotConfig();
    private final Config config = new Config();
    private ExecutorService originalPool;
    private String previousTargetDir;
    private Map<String, ProductPrice> previousRules;
    private int messageId;

    @BeforeEach
    void setUp() {
        previousTargetDir = Constant.targetDir;
        Constant.targetDir = tempDir.toString();
        previousRules = AutoBuyHerbs.AUTO_BUY_HERBS.put(BOT_ID, new ConcurrentHashMap<>());
        ProductPrice rule = new ProductPrice();
        rule.setName("乌灵参");
        rule.setPrice(80);
        AutoBuyHerbs.AUTO_BUY_HERBS.get(BOT_ID).put(rule.getName(), rule);
        botConfig.setEnableAlchemy(true);
        botConfig.setGroupId(GROUP_ID);
        botConfig.setAutoBuyHerbsMode(1);
        botConfig.setTaskStatusHerbs(1);
        config.setPurchaseSleepThreshold(1);
        config.setPurchaseSleepMinutes(10);
        service.danCalculator = mock(DanCalculator.class);
        when(service.danCalculator.getConfig(BOT_ID)).thenReturn(config);
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotConfig()).thenReturn(botConfig);
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        when(group.getGroupId()).thenReturn(GROUP_ID);
        originalPool = field("customPool");
        ExecutorService immediatePool = mock(ExecutorService.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return null;
        }).when(immediatePool).submit(any(Runnable.class));
        ReflectionTestUtils.setField(service, "customPool", immediatePool);
    }

    @AfterEach
    void tearDown() {
        originalPool.shutdownNow();
        if (previousRules == null) {
            AutoBuyHerbs.AUTO_BUY_HERBS.remove(BOT_ID);
        } else {
            AutoBuyHerbs.AUTO_BUY_HERBS.put(BOT_ID, previousRules);
        }
        Constant.targetDir = previousTargetDir;
    }

    @Test
    void completesAllEightPagesBeforeSleepingAndResumesWithFreshQuotes() {
        refresh();
        for (int page = 1; page <= 7; page++) {
            market(row("expensive-" + page, 100));
            assertFalse(sleeping());
        }
        long before = System.currentTimeMillis();
        market(row("expensive-8", 100));

        assertTrue(sleeping());
        long wakeAt = deadlines().get(BOT_ID);
        assertTrue(wakeAt >= before + 600000L && wakeAt <= System.currentTimeMillis() + 600000L);
        assertEquals(List.of("查看坊市药材1", "查看坊市药材2", "查看坊市药材3", "查看坊市药材4",
                "查看坊市药材5", "查看坊市药材6", "查看坊市药材7", "查看坊市药材8"), queries());
        assertTrue(texts().stream().anyMatch(text -> text.contains("本轮查询8页，成功采购0个，平均每页0.00个")));

        clearInvocations(group);
        market(row("must-not-buy", 50));
        ReflectionTestUtils.invokeMethod(service, "buyHerbs", bot);
        refresh();
        checkAt(wakeAt - 1);
        verify(group, never()).sendMessage(any(MessageChain.class));

        checkAt(wakeAt);
        checkAt(wakeAt + 1);
        assertFalse(sleeping());
        assertEquals(List.of("查看坊市药材1"), queries());
        market(row("fresh-code", 50));
        assertEquals(List.of("坊市购买 fresh-code"), purchases());
    }

    @ParameterizedTest
    @CsvSource({"1.0,false", "1.1,true"})
    void countsLastPageSuccessAndUsesStrictFractionalThreshold(double threshold, boolean shouldSleep) throws Exception {
        specifiedPages(3, 6);
        config.setPurchaseSleepThreshold(threshold);
        refresh();
        market(row("first", 50));
        result("道友成功购买乌灵参");
        market(row("last", 50));
        assertFalse(sleeping(), "最后一页的购买回执到达前不能休眠");
        assertEquals(List.of("查看坊市药材3", "查看坊市药材6"), queries());

        result("道友成功购买乌灵参");

        assertEquals(shouldSleep, sleeping());
        assertEquals(shouldSleep ? 2 : 3, queries().size());
        if (shouldSleep) {
            assertTrue(texts().stream().anyMatch(text -> text.contains("成功采购2个，平均每页1.00个")));
        } else {
            market(row("next-round-3", 100));
            market(row("next-round-6", 100));
            assertTrue(sleeping(), "新一轮不能继续累加上一轮的成功数");
        }
    }

    @Test
    void failedPurchaseDoesNotCountAsSuccess() throws Exception {
        specifiedPages(5);
        refresh();
        market(row("failed", 50));
        result("卖家正在进行其他操作");

        assertTrue(sleeping());
        assertTrue(texts().stream().anyMatch(text -> text.contains("本轮查询1页，成功采购0个")));
    }

    @Test
    void countsRepeatedPurchasesIndividuallyAndIgnoresDuplicateSuccessReply() throws Exception {
        specifiedPages(4);
        config.setPurchaseSleepThreshold(2.1);
        Map<String, Integer> repeats = ReflectionTestUtils.invokeMethod(service, "getRepeatBuyPrices", BOT_ID);
        repeats.put("乌灵参", 80);
        refresh();
        market(row("repeat", 50));
        int firstReplyId = ++messageId;
        result("道友成功购买乌灵参", firstReplyId);
        result("道友成功购买乌灵参", firstReplyId);
        assertEquals(2, purchases().size());
        result("道友成功购买乌灵参");
        result("未查询到该物品");

        assertTrue(sleeping());
        assertEquals(3, purchases().size());
        assertTrue(texts().stream().anyMatch(text -> text.contains("成功采购2个，平均每页2.00个")));
    }

    @Test
    void duplicateMarketMessageDoesNotCountAsNextPage() {
        specifiedPages(2, 7);
        refresh();
        int firstReplyId = ++messageId;
        market(row("expensive", 100), firstReplyId);
        market(row("expensive", 100), firstReplyId);
        assertFalse(sleeping());
        assertEquals(List.of("查看坊市药材2", "查看坊市药材7"), queries());

        market(row("expensive-next", 100));
        assertTrue(sleeping());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[CQ:at,qq=812346]", "at_tinyid=812346)"})
    void otherAccountsRepliesInSameGroupCannotAdvanceRoundOrCompletePurchase(String otherRecipient) throws Exception {
        specifiedPages(5);
        config.setPurchaseSleepThreshold(2);
        refresh();
        market(otherRecipient + "\n" + row("other-bot", 50));
        assertEquals(1, queries().size());
        assertTrue(purchases().isEmpty());
        market(row("own-bot", 50));
        result(otherRecipient + " 道友成功购买乌灵参");
        assertFalse(sleeping());
        result("道友成功购买乌灵参");

        assertTrue(sleeping());
        assertTrue(texts().stream().anyMatch(text -> text.contains("成功采购1个")));
    }

    @Test
    void timeoutAndUnparseableReplyRetrySamePageWithoutCountingIt() {
        specifiedPages(3, 8);
        refresh();
        market("无法读取的坊市消息");
        checkAt(botConfig.getAutoTaskRefreshTime() + 20000L);
        market(row("bad-price", 100).replace("100万", "错误万"));
        checkAt(botConfig.getAutoTaskRefreshTime() + 20000L);
        assertEquals(List.of("查看坊市药材3", "查看坊市药材3", "查看坊市药材3"), queries());
        assertFalse(sleeping());

        market(row("valid-3", 100));
        assertFalse(sleeping());
        market(row("valid-8", 100));
        assertTrue(sleeping());
        assertTrue(texts().stream().anyMatch(text -> text.contains("本轮查询2页")));
    }

    @Test
    void explicitlyEmptyMarketStillCountsAsValidPage() {
        specifiedPages(1);
        refresh();
        market("坊市暂无药材");
        assertTrue(sleeping());
    }

    @Test
    void stoppingCancelsAutomaticWakeUp() {
        sleepAfterOnePage();
        long wakeAt = deadlines().get(BOT_ID);
        command("停止采购药材");
        clearInvocations(group);

        checkAt(wakeAt);

        assertEquals(0, botConfig.getAutoBuyHerbsMode());
        assertFalse(sleeping());
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void switchingToAlchemyDoesNotWakePurchases() {
        sleepAfterOnePage();
        long wakeAt = deadlines().get(BOT_ID);
        botConfig.setAutoBuyHerbsMode(0);
        botConfig.setStartAuto(true);
        clearInvocations(group);

        checkAt(wakeAt);

        assertFalse(sleeping());
        assertTrue(botConfig.isStartAuto());
        assertEquals(0, botConfig.getAutoBuyHerbsMode());
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @ParameterizedTest
    @CsvSource({"关闭采购休眠,7", "设置采购休眠 0 10,10"})
    void disablingSleepEndsCurrentSleepAndPersistsSettings(String closeCommand, int expectedMinutes) {
        config.setPurchaseSleepMinutes(7);
        sleepAfterOnePage();
        clearInvocations(group);
        command(closeCommand);

        assertFalse(sleeping());
        assertEquals(0, config.getPurchaseSleepThreshold());
        assertEquals(expectedMinutes, config.getPurchaseSleepMinutes());
        verify(service.danCalculator).saveConfig(config, BOT_ID);
        assertEquals(List.of("查看坊市药材5"), queries());
        assertTrue(texts().get(0).contains("采购自动休眠已关闭，配置已保存"));
    }

    @Test
    void closingSleepDoesNotClearCaptchaPause() {
        sleepAfterOnePage();
        botConfig.setAutoVerifyModel(1);
        String captcha = "at_tinyid=" + BOT_ID + ") 请点击第2个表情";
        service.验证码判断(bot, group, null, new MessageChain().text(captcha), captcha, ++messageId);
        clearInvocations(group);

        command("关闭采购休眠");

        assertFalse(sleeping());
        assertEquals(0, config.getPurchaseSleepThreshold());
        assertTrue(botConfig.isStop());
        assertTrue(queries().isEmpty());
        Map<Long, Long> captchaDeadlines = field("captchaWaitUntilMap");
        assertTrue(captchaDeadlines.containsKey(BOT_ID));
    }

    @Test
    void configurationCommandSupportsDecimalsAndSurvivesSerialization() {
        botConfig.setAutoBuyHerbsMode(0);
        command("设置采购休眠 0.5 7");

        verify(service.danCalculator).saveConfig(config, BOT_ID);
        Config restored = JSON.parseObject(JSON.toJSONString(config), Config.class);
        assertEquals(0.5, restored.getPurchaseSleepThreshold());
        assertEquals(7, restored.getPurchaseSleepMinutes());
        assertEquals(0, JSON.parseObject("{}", Config.class).getPurchaseSleepThreshold());
        assertTrue(texts().get(0).contains("配置已保存"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"设置采购休眠", "设置采购休眠 1", "设置采购休眠 -1 10", "设置采购休眠 NaN 10",
            "设置采购休眠 Infinity 10", "设置采购休眠 1 0", "设置采购休眠 1 -1", "设置采购休眠 1 0.5",
            "设置采购休眠 1 9999999999999", "设置采购休眠 1 10 多余参数"})
    void invalidConfigurationDoesNotChangeOrSaveSettings(String invalid) {
        command(invalid);

        assertEquals(1, config.getPurchaseSleepThreshold());
        assertEquals(10, config.getPurchaseSleepMinutes());
        verify(service.danCalculator, never()).saveConfig(any(), anyLong());
        assertTrue(texts().get(0).contains("格式：设置采购休眠"));
    }

    @Test
    void queryShowsConfiguredValuesAndBeijingWakeTime() {
        sleepAfterOnePage();
        command("查询采购休眠");

        String status = texts().get(texts().size() - 1);
        assertTrue(status.contains("每页平均成功数阈值：1.0"));
        assertTrue(status.contains("休眠分钟：10"));
        assertTrue(status.contains("休眠中，预计恢复时间："));
        assertTrue(status.contains("北京时间"));
    }

    @Test
    void expiredSleepCannotClearManualPauseOrCaptchaWait() {
        sleepAfterOnePage();
        long wakeAt = deadlines().get(BOT_ID);
        botConfig.setStop(true);
        clearInvocations(group);
        checkAt(wakeAt);
        assertTrue(sleeping());
        assertTrue(botConfig.isStop());
        verify(group, never()).sendMessage(any(MessageChain.class));

        botConfig.setAutoVerifyModel(1);
        String captcha = "at_tinyid=" + BOT_ID + ") 请点击第2个表情";
        service.验证码判断(bot, group, null, new MessageChain().text(captcha), captcha, ++messageId);
        botConfig.setStop(false);
        checkAt(wakeAt + 1);
        assertTrue(sleeping());
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void restartingInvalidatesOldQueuedMarketMessage() {
        ExecutorService delayedPool = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "customPool", delayedPool);
        specifiedPages(5);
        refresh();
        service.自动购买药材(bot, group, HEADER + row("old", 100), ++messageId);
        ArgumentCaptor<Runnable> queued = ArgumentCaptor.forClass(Runnable.class);
        verify(delayedPool).submit(queued.capture());
        command("停止采购药材");
        botConfig.setAutoBuyHerbsMode(1);
        botConfig.setStop(false);
        refresh();
        queued.getValue().run();

        assertFalse(sleeping());
        assertEquals(List.of("查看坊市药材5", "查看坊市药材5"), queries());
    }

    @Test
    void sleepIsIsolatedBetweenBots() {
        sleepAfterOnePage();
        Bot otherBot = mock(Bot.class);
        Group otherGroup = mock(Group.class);
        BotConfig otherConfig = new BotConfig();
        otherConfig.setGroupId(123L);
        otherConfig.setAutoBuyHerbsMode(1);
        when(otherBot.getBotId()).thenReturn(812346L);
        when(otherBot.getBotConfig()).thenReturn(otherConfig);
        when(otherBot.getGroup(123L)).thenReturn(otherGroup);
        when(service.danCalculator.getConfig(812346L)).thenReturn(config);

        ReflectionTestUtils.invokeMethod(service, "refreshHerbsIndex", otherBot);

        verify(otherGroup).sendMessage(any(MessageChain.class));
        assertEquals(1, deadlines().size());
        assertTrue(sleeping());
    }

    private void sleepAfterOnePage() {
        specifiedPages(5);
        refresh();
        market(row("expensive", 100));
        assertTrue(sleeping());
    }

    private void specifiedPages(Integer... pages) {
        Map<Long, List<Integer>> specified = field("makeDrugIndexListMap");
        specified.put(BOT_ID, new ArrayList<>(List.of(pages)));
    }

    private String row(String code, int price) {
        return "价格:" + price + "万 [乌灵参](mqqapi://test?command="
                + "%E5%9D%8A%E5%B8%82%E8%B4%AD%E4%B9%B0" + code + "&test=1)";
    }

    private void market(String rows) { market(rows, ++messageId); }

    private void market(String rows, int id) {
        service.自动购买药材(bot, group, HEADER + rows, id);
    }

    private void result(String text) throws Exception { result(text, ++messageId); }

    private void result(String text, int id) throws Exception {
        service.成功购买药材(bot, group, null, new MessageChain().text(text), text, id);
    }

    private void command(String text) {
        service.enableScheduled(bot, group, null, new MessageChain().text(text), text, ++messageId);
    }

    private void refresh() { ReflectionTestUtils.invokeMethod(service, "refreshHerbsIndex", bot); }

    private void checkAt(long now) { ReflectionTestUtils.invokeMethod(service, "checkPurchaseTimeout", bot, now); }

    private boolean sleeping() { return deadlines().containsKey(BOT_ID); }

    private Map<Long, Long> deadlines() { return field("purchaseSleepUntilMap"); }

    private List<String> texts() {
        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, atLeastOnce()).sendMessage(sent.capture());
        return sent.getAllValues().stream().map(Utils::getMessageText).collect(Collectors.toList());
    }

    private List<String> queries() {
        return texts().stream().filter(text -> text.startsWith("查看坊市药材")).collect(Collectors.toList());
    }

    private List<String> purchases() {
        return texts().stream().filter(text -> text.startsWith("坊市购买 ")).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) { return (T) ReflectionTestUtils.getField(service, name); }
}
