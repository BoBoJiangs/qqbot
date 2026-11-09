package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.MarkdownMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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

class AutoSellGoodsPriceRefreshTest {
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final AutoSellGoods service = new AutoSellGoods(scheduler);
    private final PriceTask priceTask = new PriceTask();
    private final ProductPriceResponse prices = mock(ProductPriceResponse.class);
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final BotConfig config = new BotConfig();
    private final List<ScheduledCall> tasks = new ArrayList<>();
    private final AtomicInteger outgoingIds = new AtomicInteger(100);
    private ProductPrice originalPrice;

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
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS))).thenAnswer(call -> {
            ScheduledCall task = new ScheduledCall(call.getArgument(0), call.getArgument(1));
            tasks.add(task);
            return task.future;
        });
        ReflectionTestUtils.setField(service, "productPriceResponse", prices);
        ReflectionTestUtils.setField(priceTask, "productPriceResponse", prices);
        ReflectionTestUtils.setField(priceTask, "autoSellGoods", service);
        originalPrice = price("重元换血草", 360);
        price("龙须藤", 400);
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    @ParameterizedTest
    @CsvSource({"上架价格过低!,350,360.25,380.5,3602500", "上架价格过高!,900,320,350.5,3505000"})
    void savesNewLimitsBeforeRetryingSameQuantityAndThenContinuesQueue(
            String rejection, int currentPrice, String min, String max, long expected) {
        begin();
        reply(rejection, 1);
        reply(rejection, 1);
        assertEquals(List.of("确认坊市上架 重元换血草 3500000 2", "坊市数据重元换血草"), commands());
        assertEquals(2, queue().size());
        assertEquals(0, pending(2));

        quote(bot, group, card("重元换血草", currentPrice, min, max), 2, 101);
        InOrder order = inOrder(prices, scheduler);
        order.verify(prices).save(originalPrice);
        order.verify(scheduler).schedule(any(Runnable.class), eq(2L), eq(TimeUnit.SECONDS));
        assertEquals("重元换血草", originalPrice.getName());
        assertEquals(1, pending(2));
        assertEquals(2, commands().size(), "限价保存后仍需等待两秒");
        runNext(2);
        assertEquals("确认坊市上架 重元换血草 " + expected + " 2", commands().get(2));
        assertEquals(2, queue().size(), "重试发送不出队");

        reply("物品成功上架坊市", 3);
        runNext(2);
        assertEquals("确认坊市上架 龙须藤 3900000 2", commands().get(3));
        assertEquals(1, queue().size());
        reply("物品成功上架坊市", 4);
        assertTrue(queue().isEmpty());
        assertEquals("", config.getCommand());
    }

    @Test
    void refreshedListingDoesNotTurnIntoAlchemyEvenIfNewMarketPriceIsLow() {
        begin();
        reply("上架价格过低", 1);
        quote(bot, group, card("重元换血草", 1, "360", "380"), 2, 101);
        runNext(2);
        assertEquals("确认坊市上架 重元换血草 3600000 2", commands().get(2));
    }

    @Test
    void repeatedRejectionAfterRefreshSkipsOnlyCurrentItemAndContinues() {
        begin();
        reply("上架价格过低", 1);
        quote(bot, group, card("重元换血草", 400, "360", "420"), 2, 101);
        runNext(2);
        reply("上架价格过高", 3);
        assertEquals(1, queue().size());
        runNext(2);
        assertEquals("确认坊市上架 龙须藤 3900000 2", commands().get(3));
        assertEquals(1, commands().stream().filter(c -> c.startsWith("坊市数据")).count());
    }

    @Test
    void duplicateAndLateRepliesCannotSkipQueryOrUnsentRetry() {
        begin();
        reply("上架价格过低", 1);
        reply("上架价格过低", 2);
        reply("物品成功上架坊市", 3);
        assertEquals(2, commands().size());
        assertEquals(0, pending(2));
        quote(bot, group, card("重元换血草", 400, "360", "420"), 4, 101);
        quote(bot, group, card("重元换血草", 400, "360", "420"), 4, 101);
        reply("上架价格过低", 5);
        reply("物品成功上架坊市", 6);
        assertEquals(2, queue().size());
        assertEquals(1, pending(2));
        runNext(2);
        replyTo("上架价格过低", 7, 100);
        quote(bot, group, card("重元换血草", 400, "360", "420"), 8, 101);
        assertEquals(2, queue().size());
        assertEquals(3, commands().size());
    }

    @Test
    void otherHerbsGroupsBotsAndUnrelatedQuotesDoNotResumeListing() {
        begin();
        reply("上架价格过低", 1);
        quote(bot, group, card("龙须藤", 400, "360", "420"), 2, 101);
        Group otherGroup = mock(Group.class);
        when(otherGroup.getGroupId()).thenReturn(2002L);
        quote(bot, otherGroup, card("重元换血草", 400, "360", "420"), 3, 101);
        Bot otherBot = mock(Bot.class);
        when(otherBot.getBotId()).thenReturn(1002L);
        quote(otherBot, group, card("重元换血草", 400, "360", "420"), 4, 101);
        quote(bot, group, card("重元换血草", 400, "360", "420"), 5, 999);
        MessageChain otherMention = new MessageChain().at("1002");
        otherMention.add(new MarkdownMessage(card("重元换血草", 400, "360", "420")));
        priceTask.保存市场限价(bot, group, otherMention, "", 6);
        assertEquals(0, pending(2));
        assertEquals(2, queue().size());
        quote(bot, group, card("重元换血草", 400, "360", "420"), 7, 101);
        assertEquals(1, pending(2));
    }

    @Test
    void failedDatabaseSaveNeverRetriesWithUncommittedLimits() {
        begin();
        reply("上架价格过低", 1);
        doThrow(new IllegalStateException("数据库保存失败")).when(prices).save(any(ProductPrice.class));
        assertThrows(IllegalStateException.class,
                () -> quote(bot, group, card("重元换血草", 400, "360", "420"), 2, 101));
        assertEquals(0, pending(2));
        assertEquals(2, queue().size());
        runNext(30);
        runNext(2);
        assertEquals("确认坊市上架 龙须藤 3900000 2", commands().get(2));
    }

    @Test
    void missingOrMalformedQuoteTimesOutWithoutBlockingLaterItems() {
        begin();
        reply("上架价格过低", 1);
        quote(bot, group, card("重元换血草", 400, "420", "360"), 2, 101);
        verify(prices, never()).save(any());
        runNext(30);
        assertEquals(1, queue().size());
        runNext(2);
        assertEquals("确认坊市上架 龙须藤 3900000 2", commands().get(2));
    }

    @Test
    void quoteSendFailureDoesNotDiscardRemainingQueue() {
        begin();
        doThrow(new IllegalStateException("发送失败")).when(group).sendMessage(argThat(
                (MessageChain chain) -> Utils.getMessageText(chain).equals("坊市数据重元换血草")));
        reply("上架价格过低", 1);
        assertEquals(1, queue().size());
        assertEquals("批量上架药材", config.getCommand());
        runNext(2);
        assertEquals("确认坊市上架 龙须藤 3900000 2", commands().get(2));
    }

    @Test
    void repeatedBusyQuoteRepliesEventuallySkipOnlyFailedItem() {
        begin();
        reply("上架价格过低", 1);
        for (int i = 0; i < 3; i++) {
            reply("坊市现在太繁忙了", 2 + i);
            runNext(2);
        }
        assertEquals(3, commands().stream().filter(c -> c.equals("坊市数据重元换血草")).count());
        assertEquals("确认坊市上架 龙须藤 3900000 2", commands().get(4));
        assertEquals(1, queue().size());
    }

    @Test
    void captchaDuringQuoteCancelsTimeoutAndRetriesOnlyQuoteAfterVerification() {
        begin();
        reply("上架价格过低", 1);
        ScheduledCall oldTimeout = tasks.get(0);
        reply("请点击图中第4个表情对应的按钮", 2);
        oldTimeout.action.run();
        assertEquals(2, queue().size());
        assertEquals(2, commands().size());
        reply("不需要验证", 3);
        runNext(2);
        assertEquals("坊市数据重元换血草", commands().get(2));
        oldTimeout.action.run();
        assertEquals(2, queue().size());
        quote(bot, group, card("重元换血草", 400, "360", "420"), 4, 102);
        runNext(2);
        assertEquals("确认坊市上架 重元换血草 3900000 2", commands().get(3));
    }

    @Test
    void savedQuoteDuringCaptchaWaitsForVerificationBeforeRetryingListing() {
        begin();
        reply("上架价格过低", 1);
        reply("请点击图中第4个表情对应的按钮", 2);
        quote(bot, group, card("重元换血草", 400, "360", "420"), 3, 101);
        assertEquals(0, pending(2));
        reply("奖励8574灵石", 4);
        runNext(2);
        assertEquals("确认坊市上架 重元换血草 3900000 2", commands().get(2));
        assertEquals(2, queue().size());
    }

    @Test
    void captchaDuringAdjustedListingPreservesOneRefreshLimitAndTheQueue() {
        begin();
        reply("上架价格过低", 1);
        quote(bot, group, card("重元换血草", 400, "360", "420"), 2, 101);
        runNext(2);
        reply("请点击图中第4个表情对应的按钮", 3);
        reply("验证通过", 4);
        runNext(2);
        assertEquals(commands().get(2), commands().get(3));
        reply("上架价格过低", 5);
        runNext(2);
        assertEquals("确认坊市上架 龙须藤 3900000 2", commands().get(4));
    }

    @Test
    void stoppingAndRestartingInvalidateQuoteTimeoutAndLateQuoteCallbacks() throws Exception {
        begin();
        reply("上架价格过低", 1);
        service.enableScheduled(bot, group, null, new MessageChain(), "停止执行", 2);
        service.enableScheduled(bot, group, null, new MessageChain(), "批量上架药材", 3);
        tasks.get(0).action.run();
        quote(bot, group, card("重元换血草", 400, "360", "420"), 4, 101);
        assertTrue(queue().isEmpty());
        assertEquals(3, commands().size());
        assertEquals("药材背包", commands().get(2));
    }

    private void begin() {
        ReflectionTestUtils.invokeMethod(service, "buyHerbs",
                new ArrayList<>(List.of(item("重元换血草"), item("龙须藤"))), group, config);
    }

    private ProductPrice price(String name, int value) {
        ProductPrice price = new ProductPrice();
        price.setName(name);
        price.setPrice(value);
        when(prices.getFirstByNameOrderByTimeDesc(name)).thenReturn(price);
        return price;
    }

    private ProductPrice item(String name) {
        ProductPrice item = new ProductPrice();
        item.setName(name);
        item.setHerbCount(2);
        return item;
    }

    private String card(String name, int current, String min, String max) {
        return name + " 所在位置：坊市第6页\n分类：药材\n当前价格: " + current
                + "万\n市场限价：" + min + "万 ~ " + max + "万";
    }

    private void reply(String text, int id) {
        service.成功上架药材(bot, group, null, new MessageChain().at("1001").text(text), text, id);
    }

    private void replyTo(String text, int id, int sentId) {
        MessageChain chain = new MessageChain().at("1001").text(text);
        addReply(chain, sentId);
        service.成功上架药材(bot, group, null, chain, text, id);
    }

    private void quote(Bot source, Group target, String card, int id, int sentId) {
        MessageChain chain = new MessageChain().at(String.valueOf(source.getBotId()));
        chain.add(new MarkdownMessage(card));
        addReply(chain, sentId);
        priceTask.保存市场限价(source, target, chain, "", id);
    }

    private void addReply(MessageChain chain, int sentId) {
        ReplyMessage reply = new ReplyMessage();
        reply.setId(String.valueOf(sentId));
        reply.setQq("1001");
        chain.add(reply);
    }

    private long pending(long seconds) {
        return tasks.stream().filter(t -> t.seconds == seconds && !t.executed && !t.cancelled).count();
    }

    private void runNext(long seconds) {
        ScheduledCall next = tasks.stream().filter(t -> t.seconds == seconds && !t.executed && !t.cancelled)
                .findFirst().orElseThrow(() -> new AssertionError("没有待执行的 " + seconds + " 秒任务"));
        next.executed = true;
        next.action.run();
    }

    @SuppressWarnings("unchecked")
    private List<ProductPrice> queue() {
        return ((Map<Long, List<ProductPrice>>) ReflectionTestUtils.getField(service, "herbPackMap")).get(1001L);
    }

    private List<String> commands() {
        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, atLeastOnce()).sendMessage(sent.capture());
        return sent.getAllValues().stream().filter(c -> !c.getMessageByType(AtMessage.class).isEmpty())
                .map(Utils::getMessageText).collect(Collectors.toList());
    }

    private static class ScheduledCall {
        final Runnable action;
        final long seconds;
        final ScheduledFuture<?> future = mock(ScheduledFuture.class);
        boolean executed;
        boolean cancelled;

        ScheduledCall(Runnable action, long seconds) {
            this.action = action;
            this.seconds = seconds;
            when(future.cancel(false)).thenAnswer(call -> { cancelled = true; return true; });
        }
    }
}
