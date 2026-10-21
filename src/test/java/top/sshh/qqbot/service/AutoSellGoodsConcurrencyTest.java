package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.MarkdownMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.Utils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoSellGoodsConcurrencyTest {
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final AutoSellGoods service = new AutoSellGoods(scheduler);
    private final ProductPriceResponse prices = mock(ProductPriceResponse.class);
    private final GroupManager groups = mock(GroupManager.class);
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final BotConfig config = new BotConfig();
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final AtomicInteger outgoingIds = new AtomicInteger(100);

    @BeforeEach
    void setUp() {
        config.setGroupId(2001L);
        config.setCommand("批量上架药材");
        when(bot.getBotId()).thenReturn(1001L);
        when(bot.getBotConfig()).thenReturn(config);
        when(bot.getGroup(2001L)).thenReturn(group);
        when(group.getGroupId()).thenReturn(2001L);
        when(group.getBot()).thenReturn(bot);
        when(group.sendMessage(any(MessageChain.class))).thenAnswer(call -> outgoingIds.getAndIncrement());
        when(scheduler.schedule(any(Runnable.class), eq(2L), eq(TimeUnit.SECONDS))).thenAnswer(call -> {
            tasks.add(call.getArgument(0));
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            futures.add(future);
            return future;
        });
        ReflectionTestUtils.setField(service, "productPriceResponse", prices);
        ReflectionTestUtils.setField(service, "groupManager", groups);
        ReflectionTestUtils.setField(service, "xxGroupId", 690933736L);
        price("地龙干", 390);
        price("龙须藤", 400);
        price("鬼面花", 370);
        price("七彩月兰", 1);
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    @Test
    void alchemyWaitsForItsOwnSuccessAndBusyReplyCannotSkipNextListing() throws Exception {
        begin(item("七彩月兰"), item("地龙干"), item("龙须藤"));
        assertEquals(List.of("炼金 七彩月兰 2"), commands());
        assertEquals(3, queue().size());

        reply("炼金成功，凝聚6400000枚灵石！", 1);
        reply("道友的上一条指令还没执行完，稍等一会！", 2);
        assertEquals(2, queue().size());
        assertEquals(1, tasks.size());
        assertEquals(1, commands().size());
        run(0);
        assertEquals(List.of("炼金 七彩月兰 2", "确认坊市上架 地龙干 3800000 2"), commands());
        reply("炼金成功", 3); // 迟到的炼金回执不能确认当前上架。
        assertEquals(2, queue().size());
        assertEquals(1, tasks.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"道友的上一条指令还没执行完，稍等一会！", "操作失败，请稍后再试！", "坊市现在太繁忙了"})
    void temporaryFailuresRetryCurrentItemInsteadOfRemovingIt(String failure) throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        reply(failure, 1);
        reply(failure, 2);
        assertEquals(2, queue().size());
        assertEquals(1, tasks.size());
        run(0);
        assertEquals(List.of("确认坊市上架 地龙干 3800000 2", "确认坊市上架 地龙干 3800000 2"), commands());
    }

    @Test
    void concurrentDuplicateSuccessConsumesExactlyOneItem() throws Exception {
        begin(item("地龙干"), item("龙须藤"), item("鬼面花"));
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i=0; i<16; i++) results.add(workers.submit(() -> {
                start.await();
                reply("物品成功上架坊市", 11);
                return null;
            }));
            start.countDown();
            for (Future<?> result : results) result.get(5, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        assertEquals(2, queue().size());
        assertEquals("龙须藤", queue().get(0).getName());
        assertEquals(1, tasks.size());
        run(0);
        assertEquals(2, commands().size());
        reply("物品成功上架坊市", 11); // 新指令已发出后重放旧事件也不能再次出队。
        assertEquals(2, queue().size());
        assertEquals(1, tasks.size());
    }

    @Test
    void concurrentBusyAndFailureScheduleOnlyOneRetry() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = workers.submit(() -> { start.await(); reply("操作失败", 1); return null; });
            Future<?> b = workers.submit(() -> { start.await(); reply("道友的上一条指令还没执行完", 2); return null; });
            start.countDown();
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        assertEquals(1, tasks.size());
        run(0);
        assertEquals(2, queue().size());
        assertEquals(2, commands().size());
        assertEquals(commands().get(0), commands().get(1));
    }

    @Test
    void repeatedCaptchaAndTwoVerificationMessagesResumeCurrentItemOnce() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        reply("道友的上一条指令还没执行完", 1);
        reply("请点击图中第4个表情对应的按钮", 2);
        reply("请点击图中第4个表情对应的按钮", 3);
        config.setStop(false); // 验证等待不依赖其他模块对通用 stop 的修改。
        run(0);
        assertEquals(1, commands().size());
        reply("不需要验证", 4);
        reply("操作失败，请稍后再试！", 5);
        reply("道友的上一条指令还没执行完", 6);
        reply("奖励8574灵石", 7);
        assertEquals(2, tasks.size());
        run(1);
        assertEquals(2, queue().size());
        assertEquals(List.of("确认坊市上架 地龙干 3800000 2", "确认坊市上架 地龙干 3800000 2"), commands());
    }

    @Test
    void captchaAfterConfirmedSuccessDoesNotResendCompletedItem() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        reply("物品成功上架坊市", 1);
        reply("请点击图中第4个表情对应的按钮", 2);
        run(0);
        reply("奖励8574灵石", 3);
        run(1);
        assertEquals(List.of("确认坊市上架 地龙干 3800000 2", "确认坊市上架 龙须藤 3900000 2"), commands());
    }

    @Test
    void successBeforeVerificationResultConfirmsItemWithoutSendingNextEarly() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        reply("请点击图中第4个表情对应的按钮", 1);
        reply("物品成功上架坊市", 2);
        assertEquals(1, queue().size());
        assertTrue(tasks.isEmpty());
        assertEquals(1, commands().size());
        reply("奖励8574灵石", 3);
        run(0);
        assertEquals(List.of("确认坊市上架 地龙干 3800000 2", "确认坊市上架 龙须藤 3900000 2"), commands());
    }

    @Test
    void verificationBeforeDelayedSuccessCancelsCurrentItemRetry() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        reply("请点击图中第4个表情对应的按钮", 1);
        reply("不需要验证", 2);
        reply("物品成功上架坊市", 3);
        run(0);
        assertEquals(1, commands().size());
        run(1);
        assertEquals(List.of("确认坊市上架 地龙干 3800000 2", "确认坊市上架 龙须藤 3900000 2"), commands());
    }

    @Test
    void captchaDuringInventoryScanRetriesOnlyTheBackpackQuery() throws Exception {
        command("批量上架药材");
        reply("请点击图中第4个表情对应的按钮", 1);
        reply("奖励8574灵石", 2);
        run(0);
        assertEquals(List.of("药材背包", "药材背包"), commands());
        assertTrue(queue().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"上架价格过高", "上架价格过低", "物品数量不足"})
    void definitiveFailureOnlySkipsTheCurrentItem(String failure) throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        reply(failure, 1);
        reply(failure, 1);
        assertEquals(1, queue().size());
        assertEquals(1, tasks.size());
        run(0);
        assertEquals("确认坊市上架 龙须藤 3900000 2", commands().get(1));
    }

    @Test
    void failedVerificationDoesNotPopOrResumeCurrentItem() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        reply("请点击图中第4个表情对应的按钮", 1);
        reply("验证码不正确", 2);
        reply("验证码已过期", 3);
        reply("未验证通过", 4);
        assertTrue(tasks.isEmpty());
        assertEquals(2, queue().size());
        assertEquals(1, commands().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void commonVerificationHandlerDoesNotClearBatchCommandInEitherHandlerOrder(boolean commonFirst) {
        begin(item("地龙干"), item("龙须藤"));
        TestService common = new TestService();
        ReflectionTestUtils.setField(common, "xxGroupId", 0L);
        List<String> messages = List.of("请点击图中第4个表情对应的按钮", "验证码不正确", "不需要验证",
                "操作失败，请稍后再试！", "奖励8574灵石");
        for (int i = 0; i < messages.size(); i++) {
            String text = "@1001 " + messages.get(i);
            MessageChain chain = new MessageChain().at("1001").text(text);
            if (commonFirst) common.验证码判断(bot, group, null, chain, text, i + 1, null);
            service.成功上架药材(bot, group, null, chain, text, i + 1);
            if (!commonFirst) common.验证码判断(bot, group, null, chain, text, i + 1, null);
            assertEquals("批量上架药材", config.getCommand());
        }
        assertEquals(1, tasks.size());
        run(0);
        assertEquals(List.of("确认坊市上架 地龙干 3800000 2", "确认坊市上架 地龙干 3800000 2"), commands());
        assertEquals(2, queue().size());
    }

    @Test
    void successWhileWaitingToRetryCancelsRetryAndAdvancesOnce() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        reply("操作失败", 1);
        reply("物品成功上架坊市", 2);
        run(0); // 已取消的重试回调。
        assertEquals(1, commands().size());
        run(1);
        assertEquals("确认坊市上架 龙须藤 3900000 2", commands().get(1));
    }

    @Test
    void stopsAfterThreeFailedAttemptsAndKeepsUnprocessedQueue() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        reply("操作失败", 1);
        run(0);
        reply("操作失败", 2);
        run(1);
        reply("操作失败", 3);
        assertEquals(3, commands().size());
        assertEquals(2, queue().size());
        assertEquals("", config.getCommand());
        verify(bot, never()).sendGroupMessage(anyLong(), any());
    }

    @Test
    void otherBotsGroupsAndUnaddressedMessagesNeverAdvanceQueue() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        service.成功上架药材(bot, group, null, new MessageChain().at("1002"), "物品成功上架坊市", 1);
        service.成功上架药材(bot, group, null, new MessageChain(), "物品成功上架坊市", 2);
        service.成功上架药材(bot, group, null, new MessageChain(), "@10010 物品成功上架坊市", 3);
        Group other = mock(Group.class);
        when(other.getGroupId()).thenReturn(2002L);
        service.成功上架药材(bot, other, null, new MessageChain().at("1001"), "物品成功上架坊市", 4);
        assertEquals(2, queue().size());
        assertTrue(tasks.isEmpty());
    }

    @Test
    void quotedReplyCanOnlyConfirmTheCurrentOperation() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        replyTo("物品成功上架坊市", 1, 999);
        assertEquals(2, queue().size());
        replyTo("操作失败", 2, 100);
        run(0); // 同一个操作现在发送过100和101两条消息。
        replyTo("物品成功上架坊市", 3, 100);
        run(1); // 下一项发送为102。
        replyTo("物品成功上架坊市", 4, 101);
        assertEquals(1, queue().size());
        assertEquals("龙须藤", queue().get(0).getName());
    }

    @Test
    void stopAndRestartInvalidateOldScheduledCallbacks() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        reply("物品成功上架坊市", 1);
        command("停止执行");
        command("批量上架药材");
        run(0);
        assertEquals(List.of("确认坊市上架 地龙干 3800000 2", "药材背包"), commands());
        assertTrue(queue().isEmpty());
        verify(futures.get(0)).cancel(false);
    }

    @Test
    void duplicateStartDoesNotResetOrSendAnotherBackpackQuery() throws Exception {
        command("批量上架药材");
        command("批量上架药材");
        assertEquals(List.of("药材背包"), commands());
    }

    @Test
    void backpackHandlersShareEventsWithoutConsumingEachOthersMessages() throws Exception {
        command("批量上架药材");
        inventory("药材背包\n地龙干 - 数量：12 炼金 | 坊市数据\n第1页/共1页", 1);
        inventory("药材背包\n地龙干 - 数量：12 炼金 | 坊市数据\n第1页/共1页", 1);
        assertEquals(2, queue().size());
        assertEquals(10, queue().get(0).getHerbCount());
        assertEquals(2, queue().get(1).getHerbCount());
        assertEquals(List.of("药材背包"), commands());
        run(0);
        assertEquals("确认坊市上架 地龙干 3800000 10", commands().get(1));
    }

    @Test
    void duplicateTextAndMarkdownBackpackRowsAreParsedOnce() throws Exception {
        command("批量上架药材");
        String text="药材背包\n地龙干 - 数量：2 炼金 | 坊市数据\n第1页/共1页";
        MessageChain chain=new MessageChain().at("1001");
        chain.add(new MarkdownMessage(text));
        chain.add(new TextMessage(text));
        service.药材背包(bot, group, null, chain, text, 1);
        assertEquals(1, queue().size());
    }

    @Test
    void paginationRejectsOldPageAndWaitsBeforeSendingNextPage() throws Exception {
        command("批量上架药材");
        String page1="药材背包\n地龙干 - 数量：2 炼金 | 坊市数据\n第1页/共2页 下一页";
        inventory(page1, 1);
        assertEquals(1, commands().size());
        run(0);
        assertEquals("药材背包2", commands().get(1));
        inventory(page1, 2);
        assertEquals(1, queue().size());
        inventory("药材背包\n龙须藤 - 数量：2 炼金 | 坊市数据\n第2页/共2页 上一页", 3);
        assertEquals(2, queue().size());
        run(1);
        assertEquals("确认坊市上架 地龙干 3800000 2", commands().get(2));
    }

    @Test
    void startingAndStoppingAnotherBotDoesNotClearThisBotsQueue() throws Exception {
        begin(item("地龙干"), item("龙须藤"));
        Bot second=mock(Bot.class);
        BotConfig secondConfig=new BotConfig();
        secondConfig.setGroupId(2001L);
        Group secondGroup=mock(Group.class);
        when(second.getBotId()).thenReturn(1002L);
        when(second.getBotConfig()).thenReturn(secondConfig);
        when(second.getGroup(2001L)).thenReturn(secondGroup);
        when(secondGroup.getGroupId()).thenReturn(2001L);
        when(secondGroup.getBot()).thenReturn(second);
        service.enableScheduled(second, secondGroup, null, new MessageChain(), "批量上架药材", 1);
        service.enableScheduled(second, secondGroup, null, new MessageChain(), "停止执行", 2);
        assertEquals(2, queue().size());
        reply("物品成功上架坊市", 1);
        run(0);
        assertEquals("确认坊市上架 龙须藤 3900000 2", commands().get(1));
    }

    @Test
    void completionIsOnlySentAfterFinalMatchingSuccess() throws Exception {
        begin(item("地龙干"));
        verify(bot, never()).sendGroupMessage(anyLong(), any());
        when(bot.sendGroupMessage(anyLong(),any())).thenAnswer(call -> {
            assertEquals("", config.getCommand());
            return 1;
        });
        reply("物品成功上架坊市", 1);
        reply("物品成功上架坊市", 2);
        assertTrue(queue().isEmpty());
        verify(bot, times(1)).sendGroupMessage(eq(2001L), any());
    }

    private void price(String name,int value) {
        ProductPrice price=new ProductPrice();
        price.setName(name);
        price.setPrice(value);
        when(prices.getFirstByNameOrderByTimeDesc(name)).thenReturn(price);
    }

    private ProductPrice item(String name) {
        ProductPrice item=new ProductPrice();
        item.setName(name);
        item.setHerbCount(2);
        return item;
    }

    private void begin(ProductPrice... items) {
        ReflectionTestUtils.invokeMethod(service,"buyHerbs",new ArrayList<>(List.of(items)),group,config);
    }

    private void command(String command) throws Exception {
        service.enableScheduled(bot,group,null,new MessageChain().text(command),command,99);
    }

    private void inventory(String text,int id) throws Exception {
        MessageChain chain=new MessageChain().at("1001").text(text);
        service.成功上架药材(bot,group,null,chain,text,id);
        service.药材背包(bot,group,null,chain,text,id);
    }

    private void reply(String text,int id) {
        service.成功上架药材(bot,group,null,new MessageChain().at("1001").text(text),text,id);
    }

    private void replyTo(String text,int id,int outgoingId) {
        MessageChain chain=new MessageChain().at("1001").text(text);
        ReplyMessage reply=new ReplyMessage();
        reply.setId(String.valueOf(outgoingId));
        reply.setQq("1001");
        chain.add(reply);
        service.成功上架药材(bot,group,null,chain,text,id);
    }

    private void run(int index) { tasks.get(index).run(); }

    @SuppressWarnings("unchecked")
    private List<ProductPrice> queue() {
        return ((Map<Long,List<ProductPrice>>)ReflectionTestUtils.getField(service,"herbPackMap")).get(1001L);
    }

    private List<String> commands() {
        ArgumentCaptor<MessageChain> sent=ArgumentCaptor.forClass(MessageChain.class);
        verify(group,atLeastOnce()).sendMessage(sent.capture());
        return sent.getAllValues().stream().filter(chain -> !chain.getMessageByType(AtMessage.class).isEmpty())
                .map(Utils::getMessageText).collect(Collectors.toList());
    }
}
