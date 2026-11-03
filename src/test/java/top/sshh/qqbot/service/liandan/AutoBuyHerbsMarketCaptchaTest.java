package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoBuyHerbsMarketCaptchaTest {
    private static final long BOT_ID = 1001L;
    private static final long GROUP_ID = 2001L;
    private final AutoBuyHerbs service = new AutoBuyHerbs();
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final BotConfig botConfig = new BotConfig();
    private final Config config = new Config();
    private ExecutorService originalPool;
    private Map<String, ProductPrice> previousRules;

    @BeforeEach
    void setUp() {
        botConfig.setGroupId(GROUP_ID);
        botConfig.setAutoBuyHerbsMode(1);
        botConfig.setAutoVerifyModel(2);
        botConfig.setTaskStatusHerbs(5);
        config.setRandomDelay(0);
        config.setPurchaseSleepMinutes(10);
        service.danCalculator = mock(DanCalculator.class);
        when(service.danCalculator.getConfig(BOT_ID)).thenReturn(config);
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotConfig()).thenReturn(botConfig);
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        when(group.getGroupId()).thenReturn(GROUP_ID);
        ProductPrice rule = new ProductPrice();
        rule.setName("乌灵参");
        rule.setPrice(80);
        previousRules = AutoBuyHerbs.AUTO_BUY_HERBS.put(BOT_ID, new ConcurrentHashMap<>(Map.of("乌灵参", rule)));
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
        if (previousRules == null) AutoBuyHerbs.AUTO_BUY_HERBS.remove(BOT_ID);
        else AutoBuyHerbs.AUTO_BUY_HERBS.put(BOT_ID, previousRules);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verificationRetriesBlockedPageAndQueuesItsHerbsBeforeNextPage(boolean sleepEnabled) throws Exception {
        specifiedPages(sleepEnabled);
        refresh();
        receipt("请点击图中第4个表情对应的按钮");
        receipt("奖励8675灵石");
        receipt("奖励8675灵石"); // 重复成功回执不能再发送一次查询。
        assertEquals(List.of("查看坊市药材5"), texts());
        queryTimeout(); // 游戏没有补发页面时才重试。
        assertEquals(List.of("查看坊市药材5", "查看坊市药材5"), texts());

        market(10);
        Map<Long, CopyOnWriteArrayList<ProductPrice>> queues = field("autoBuyListMap");
        assertEquals("page-five", queues.get(BOT_ID).get(0).getCode());
        assertEquals("坊市购买 page-five", texts().get(2));
        service.成功购买药材(bot, group, null, new MessageChain(), "道友成功购买乌灵参", 11);
        assertEquals("查看坊市药材7", texts().get(3));
        market(10); // 旧页重复投递不能当作下一页的回执。
        Map<Long, Integer> pending = field("pendingMarketPageMap");
        assertEquals(7, pending.get(BOT_ID));
    }

    @Test
    void sequentialPagesRetryFiveThenContinueWithSix() throws Exception {
        refresh();
        receipt("请点击图中第4个表情对应的按钮");
        receipt("验证成功");
        queryTimeout();
        market(10);
        service.成功购买药材(bot, group, null, new MessageChain(), "道友成功购买乌灵参", 11);
        assertEquals(List.of("查看坊市药材5", "查看坊市药材5", "坊市购买 page-five", "查看坊市药材6"), texts());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void captchaTimeoutRetriesSamePage(boolean sleepEnabled) {
        specifiedPages(sleepEnabled);
        refresh();
        receipt("请点击图中第4个表情对应的按钮");
        Map<Long, Long> deadlines = field("captchaWaitUntilMap");
        ReflectionTestUtils.invokeMethod(service, "checkPurchaseTimeout", bot, deadlines.get(BOT_ID));
        assertEquals(List.of("查看坊市药材5", "查看坊市药材5"), texts());
        market(10);
        assertEquals("坊市购买 page-five", texts().get(2));
    }

    @Test
    void rejectedPrimaryKeepsPagePausedWhileKnnIsPending() {
        refresh();
        receipt("请点击图中第4个表情对应的按钮");
        receipt("验证码不正确");
        botConfig.setStop(false); // 通用处理器可能清掉 stop，采购仍须等待 KNN。
        ReflectionTestUtils.invokeMethod(service, "checkPurchaseTimeout", bot, System.currentTimeMillis() + 20000L);
        assertEquals(List.of("查看坊市药材5"), texts());
        receipt("验证通过");
        queryTimeout();
        assertEquals(List.of("查看坊市药材5", "查看坊市药材5"), texts());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void automaticMarketReplayIsQueuedInEitherReceiptOrder(boolean sleepEnabled, boolean pageArrivesFirst) throws Exception {
        specifiedPages(sleepEnabled);
        refresh();
        receipt("请点击图中第4个表情对应的按钮");
        if (pageArrivesFirst) {
            market(10);
            assertEquals(List.of("查看坊市药材5"), texts(), "验证完成前不能购买");
        }
        receipt("奖励7799灵石");
        if (!pageArrivesFirst) market(10);
        assertEquals(List.of("查看坊市药材5", "坊市购买 page-five"), texts(),
                "游戏已补发的第5页应直接入队，无需重复查询");
        service.成功购买药材(bot, group, null, new MessageChain(), "道友成功购买乌灵参", 11);
        assertEquals("查看坊市药材7", texts().get(2));
        Map<Long, ?> buffered = field("captchaMarketMessageMap");
        assertFalse(buffered.containsKey(BOT_ID));
    }

    @Test
    void bufferedPageCannotResumeAfterPurchasesAreStopped() {
        refresh();
        receipt("请点击图中第4个表情对应的按钮");
        market(10);
        ReflectionTestUtils.invokeMethod(service, "resetPram", bot, botConfig);
        botConfig.setAutoBuyHerbsMode(0);
        receipt("奖励7799灵石");
        assertEquals(List.of("查看坊市药材5"), texts());
        Map<Long, ?> buffered = field("captchaMarketMessageMap");
        assertFalse(buffered.containsKey(BOT_ID));
    }

    @Test
    void malformedAndOtherAccountsMarketRepliesCannotConsumePendingPage() {
        refresh();
        ReflectionTestUtils.invokeMethod(service, "processMarketMessage", bot, group, "无法读取的坊市消息");
        ReflectionTestUtils.invokeMethod(service, "processMarketMessage", bot, group, "[CQ:at,qq=1002]\n" + row());
        ReflectionTestUtils.invokeMethod(service, "checkPurchaseTimeout", bot, botConfig.getAutoTaskRefreshTime() + 20000L);
        assertEquals(List.of("查看坊市药材5", "查看坊市药材5"), texts());
    }

    @Test
    void failedQuerySendDoesNotSkipItsPage() {
        when(bot.getGroup(GROUP_ID)).thenReturn(null);
        refresh();
        assertEquals(5, botConfig.getTaskStatusHerbs());
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        refresh();
        market(10);
        assertEquals(List.of("查看坊市药材5", "坊市购买 page-five"), texts());
    }

    private void specifiedPages(boolean sleepEnabled) {
        config.setPurchaseSleepThreshold(sleepEnabled ? 1 : 0);
        Map<Long, List<Integer>> pages = field("makeDrugIndexListMap");
        pages.put(BOT_ID, new ArrayList<>(List.of(5, 7)));
    }

    private void receipt(String text) {
        String message = "at_tinyid=" + BOT_ID + ") " + text;
        service.验证码判断(bot, group, null, new MessageChain().text(message), message, 1);
    }

    private String row() {
        return "价格:50万 [乌灵参](mqqapi://test?command=%E5%9D%8A%E5%B8%82%E8%B4%AD%E4%B9%B0page-five&test=1)";
    }

    private void market(int messageId) {
        service.自动购买药材(bot, group, "不鼓励不保障任何第三方交易行为\n" + row(), messageId);
    }

    private void refresh() { ReflectionTestUtils.invokeMethod(service, "refreshHerbsIndex", bot); }

    private void queryTimeout() {
        ReflectionTestUtils.invokeMethod(service, "checkPurchaseTimeout", bot, botConfig.getAutoTaskRefreshTime() + 20000L);
    }

    private List<String> texts() {
        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, atLeastOnce()).sendMessage(sent.capture());
        return sent.getAllValues().stream().map(Utils::getMessageText).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) { return (T) ReflectionTestUtils.getField(service, name); }
}
