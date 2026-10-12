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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.constant.Constant;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.ProductPrice;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoBuyHerbsGroupRoutingTest {
    private static final long BOT_ID = 90011L;
    private static final long EXECUTION_GROUP_ID = 516625967L;
    private static final long TASK_GROUP_ID = 60001L;

    @TempDir
    Path tempDir;

    private final AutoBuyHerbs service = new AutoBuyHerbs();
    private final Bot bot = mock(Bot.class);
    private final BotConfig botConfig = new BotConfig();
    private final Config alchemyConfig = new Config();
    private Group executionGroup;
    private Group taskGroup;
    private Group commandGroup;
    private String previousTargetDir;
    private Map<String, ProductPrice> previousPurchaseRules;
    private ExecutorService originalPool;

    @BeforeEach
    void setUp() {
        previousTargetDir = Constant.targetDir;
        Constant.targetDir = tempDir.toString();
        previousPurchaseRules = AutoBuyHerbs.AUTO_BUY_HERBS.remove(BOT_ID);
        originalPool = (ExecutorService) ReflectionTestUtils.getField(service, "customPool");
        botConfig.setEnableAlchemy(true);
        botConfig.setGroupId(EXECUTION_GROUP_ID);
        botConfig.setTaskId(TASK_GROUP_ID);
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotConfig()).thenReturn(botConfig);
        executionGroup = group(EXECUTION_GROUP_ID);
        taskGroup = group(TASK_GROUP_ID);
        commandGroup = group(60002L);
        service.danCalculator = mock(DanCalculator.class);
        when(service.danCalculator.getConfig(BOT_ID)).thenReturn(alchemyConfig);
    }

    @AfterEach
    void tearDown() {
        originalPool.shutdownNow();
        ((ExecutorService) ReflectionTestUtils.getField(service, "customPool")).shutdownNow();
        Constant.targetDir = previousTargetDir;
        if (previousPurchaseRules == null) {
            AutoBuyHerbs.AUTO_BUY_HERBS.remove(BOT_ID);
        } else {
            AutoBuyHerbs.AUTO_BUY_HERBS.put(BOT_ID, previousPurchaseRules);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"开始采购药材", "分析背包药材"})
    void commandsFromOtherGroupsReadBackpackInConfiguredGroup(String command) {
        start(command);

        assertSentTexts(executionGroup, "药材背包");
        verify(commandGroup, never()).sendMessage(any(MessageChain.class));
        verify(taskGroup, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void alchemyCompletionInReminderGroupResumesPurchasesInConfiguredGroup() {
        botConfig.setStartAuto(true);
        service.enableScheduled(bot, taskGroup, null, new MessageChain().text("丹药炼金完成"),
                "丹药炼金完成", 1);

        assertSentTexts(executionGroup, "药材背包");
        assertEquals(1, botConfig.getAutoBuyHerbsMode());
        assertEquals(false, botConfig.isStartAuto());
        verify(taskGroup, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void purchaseBackpackPaginationAndMarketRefreshStayInConfiguredGroup() throws Exception {
        start("开始采购药材");
        receiveBackpack(taskGroup, "冰灵果 - 数量：2 炼金 | 坊市数据\n下一页");
        assertTrue(field("medicinalListMap").get(BOT_ID).equals(List.of()));
        assertEquals(1, field("pageMap").get(BOT_ID));

        receiveBackpack(executionGroup, "冰灵果 - 数量：2 炼金 | 坊市数据\n下一页");
        receiveBackpack(executionGroup, "乌灵参 - 数量：1 炼金 | 坊市数据\n上一页");

        assertSentTexts(executionGroup, "药材背包", "药材背包2", "查看坊市药材1");
        verify(taskGroup, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void analysisOnlyReadsConfiguredGroupAndReturnsResultThere() throws Exception {
        botConfig.setAutoBuyHerbsMode(1);
        start("分析背包药材");
        receiveBackpack(taskGroup, "冰灵果 - 数量：100 炼金 | 坊市数据\n药材背包");
        assertEquals(0, botConfig.getAutoBuyHerbsMode());
        assertEquals(true, field("smartAdjustModeMap").get(BOT_ID));

        receiveBackpack(executionGroup, "冰灵果 - 数量：2 炼金 | 坊市数据\n下一页");
        receiveBackpack(executionGroup, "乌灵参 - 数量：1 炼金 | 坊市数据\n上一页");

        assertSentTexts(executionGroup, "药材背包", "药材背包2",
                "当前背包药材数量均在限制范围内，无需调整价格");
        assertEquals(1, botConfig.getAutoBuyHerbsMode());
        assertEquals(false, field("smartAdjustModeMap").get(BOT_ID));
        verify(commandGroup, never()).sendMessage(any(MessageChain.class));
        verify(taskGroup, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void purchaseResultsFromTaskGroupCannotAdvanceOrStopQueue() throws Exception {
        botConfig.setAutoBuyHerbsMode(1);
        CopyOnWriteArrayList<ProductPrice> queue = new CopyOnWriteArrayList<>(List.of(
                product("乌灵参", "code-1"), product("血灵芝", "code-2")));
        Map<Long, CopyOnWriteArrayList<ProductPrice>> queues = field("autoBuyListMap");
        queues.put(BOT_ID, queue);
        String success = "道友成功购买乌灵参，消耗60万灵石";

        service.成功购买药材(bot, taskGroup, null, new MessageChain(), success, 1);
        service.成功购买药材(bot, taskGroup, null, new MessageChain(), "今天已经很努力了", 2);

        assertEquals(2, queue.size());
        assertEquals(1, botConfig.getAutoBuyHerbsMode());
        service.成功购买药材(bot, executionGroup, null, new MessageChain(), success, 3);

        assertEquals(1, queue.size());
        assertSentTexts(executionGroup, "坊市购买 code-2");
        verify(taskGroup, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void marketMessagesOnlySubmitPurchasesFromConfiguredGroup() {
        botConfig.setAutoBuyHerbsMode(1);
        ProductPrice rule = product("乌灵参", "unused");
        AutoBuyHerbs.AUTO_BUY_HERBS.put(BOT_ID, Map.of("乌灵参", rule));
        ExecutorService pool = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "customPool", pool);
        String market = "不鼓励不保障任何第三方交易行为\n价格:60万 [乌灵参]"
                + "(mqqapi://command?text=%E5%9D%8A%E5%B8%82%E8%B4%AD%E4%B9%B0code-1&x=1)";

        service.自动购买药材(bot, taskGroup, market, 1);
        verify(pool, never()).submit(any(Runnable.class));
        service.自动购买药材(bot, executionGroup, market, 2);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(pool).submit(callback.capture());
        callback.getValue().run();

        assertSentTexts(executionGroup, "坊市购买 code-1");
        verify(taskGroup, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void delayedPurchaseUsesConfigurationAtExecutionTime() {
        botConfig.setAutoBuyHerbsMode(1);
        alchemyConfig.setRandomDelay(1);
        Map<Long, CopyOnWriteArrayList<ProductPrice>> queues = field("autoBuyListMap");
        queues.put(BOT_ID, new CopyOnWriteArrayList<>(List.of(product("乌灵参", "code-1"))));
        ExecutorService pool = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "customPool", pool);
        ReflectionTestUtils.invokeMethod(service, "buyNextHerbAfterDelay", bot);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(pool).submit(callback.capture());

        botConfig.setGroupId(TASK_GROUP_ID);
        callback.getValue().run();

        assertSentTexts(taskGroup, "坊市购买 code-1");
        verify(executionGroup, never()).sendMessage(any(MessageChain.class));
    }

    private void start(String command) {
        service.enableScheduled(bot, commandGroup, null, new MessageChain().text(command), command, 1);
    }

    private void receiveBackpack(Group group, String content) throws Exception {
        MessageChain chain = new MessageChain().text(content);
        service.药材背包(bot, group, null, chain, content, 1);
    }

    private Group group(long groupId) {
        Group group = mock(Group.class);
        when(group.getGroupId()).thenReturn(groupId);
        when(bot.getGroup(groupId)).thenReturn(group);
        return group;
    }

    private ProductPrice product(String name, String code) {
        ProductPrice product = new ProductPrice();
        product.setName(name);
        product.setCode(code);
        product.setPrice(100);
        return product;
    }

    private void assertSentTexts(Group group, String... expected) {
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, times(expected.length)).sendMessage(messages.capture());
        List<String> actual = messages.getAllValues().stream()
                .map(chain -> chain.getMessageByType(TextMessage.class).stream()
                        .map(TextMessage::getText).collect(Collectors.joining()))
                .collect(Collectors.toList());
        assertEquals(List.of(expected), actual);
    }

    @SuppressWarnings("unchecked")
    private <V> Map<Long, V> field(String name) {
        return (Map<Long, V>) ReflectionTestUtils.getField(service, name);
    }
}
