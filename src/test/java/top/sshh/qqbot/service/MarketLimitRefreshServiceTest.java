package top.sshh.qqbot.service;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.MarkdownMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.Utils;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketLimitRefreshServiceTest {
    private final ProductPriceResponse prices = mock(ProductPriceResponse.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);
    private final List<Runnable> callbacks = new ArrayList<>();
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private MarketLimitRefreshService service;
    private PriceTask priceTask;

    @BeforeEach
    void setUp() {
        when(bot.getBotId()).thenReturn(1001L);
        when(bot.getGroup(2001L)).thenReturn(group);
        when(group.getGroupId()).thenReturn(2001L);
        when(group.getBot()).thenReturn(bot);
        when(prices.findAll()).thenReturn(List.of(
                product("红绫草", null), product("离火梧桐芝", null), product("离火梧桐芝", null),
                product("寒铁铸心炉", null), product("渡厄丹", null)));
        when(scheduler.schedule(any(Runnable.class), eq(2L), eq(TimeUnit.SECONDS))).thenAnswer(call -> {
            callbacks.add(call.getArgument(0));
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            futures.add(future);
            return future;
        });
        service = new MarketLimitRefreshService(prices, scheduler);
        priceTask = new PriceTask();
        ReflectionTestUtils.setField(priceTask, "productPriceResponse", prices);
        ReflectionTestUtils.setField(priceTask, "marketLimitRefreshService", service);
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    @Test
    void startsOneQueryForEachDistinctDatabaseHerbAndWaitsForItsReply() {
        start();
        assertEquals(List.of("坊市数据离火梧桐芝"), queries(group));
        assertTrue(texts(group).contains("开始更新坊市限价，共 2 种药材。"));
        verifyNoInteractions(scheduler);
        for (MessageChain chain : sent(group)) {
            if (!Utils.getMessageText(chain).startsWith("坊市数据")) continue;
            assertEquals("3889001741", chain.getMessageByType(AtMessage.class).get(0).getQq());
        }
    }

    @Test
    void savesMatchingMarkdownQuoteBeforeSchedulingNextQueryAndFinishesOneRound() {
        start();
        receive(bot, group, "离火梧桐芝");
        InOrder order = inOrder(prices, scheduler);
        order.verify(prices).save(any(ProductPrice.class));
        order.verify(scheduler).schedule(any(Runnable.class), eq(2L), eq(TimeUnit.SECONDS));
        assertEquals(List.of("坊市数据离火梧桐芝"), queries(group));

        callbacks.get(0).run();
        assertEquals(List.of("坊市数据离火梧桐芝", "坊市数据红绫草"), queries(group));
        receive(bot, group, "红绫草");
        assertTrue(texts(group).contains("坊市限价更新完成，共更新 2 种药材。"));
        assertEquals(1, callbacks.size());

        receive(bot, group, "红绫草");
        assertEquals(1, texts(group).stream().filter(t -> t.contains("更新完成")).count());
        start();
        assertEquals(3, queries(group).size(), "完成后可以开始新的一轮");
    }

    @Test
    void snowLumaImageCardSavesTheRequestedHerbAndAdvancesAfterTwoSeconds() throws Exception {
        when(prices.findAll()).thenReturn(List.of(product("七彩月兰", "药材"), product("红绫草", "药材")));
        ProductPrice existing = product("七彩月兰", "药材");
        existing.setId(9L);
        when(prices.getFirstByNameOrderByTimeDesc("七彩月兰")).thenReturn(existing);
        String card = new String(getClass().getResourceAsStream("/market/seven-color-orchid.md").readAllBytes(),
                StandardCharsets.UTF_8);
        MessageChain response = new MessageChain().at("1001");
        response.add(new MarkdownMessage(card));
        response.add(new TextMessage(card));

        start();
        priceTask.保存市场限价(bot, group, response, "", 3);

        verify(prices).save(existing);
        assertEquals("七彩月兰", existing.getName());
        assertEquals(3800000L, existing.getMarketMinPrice());
        assertEquals(4600000L, existing.getMarketMaxPrice());
        assertEquals(List.of("坊市数据七彩月兰"), queries(group));
        verify(scheduler).schedule(any(Runnable.class), eq(2L), eq(TimeUnit.SECONDS));
        callbacks.get(0).run();
        assertEquals(List.of("坊市数据七彩月兰", "坊市数据红绫草"), queries(group));
        receive(bot, group, "红绫草");
        assertTrue(texts(group).contains("坊市限价更新完成，共更新 2 种药材。"));
    }

    @Test
    void ignoresOtherHerbsOtherGroupsOtherBotsAndRepliesAddressedToAnotherBot() {
        start();
        receive(bot, group, "红绫草");
        Group otherGroup = mock(Group.class);
        when(otherGroup.getGroupId()).thenReturn(2002L);
        receive(bot, otherGroup, "离火梧桐芝");
        Bot otherBot = mock(Bot.class);
        when(otherBot.getBotId()).thenReturn(1002L);
        receive(otherBot, group, "离火梧桐芝");
        MessageChain addressed = new MessageChain().at("1002");
        addressed.add(new MarkdownMessage(card("离火梧桐芝")));
        priceTask.保存市场限价(bot, group, addressed, "", 6);
        verifyNoInteractions(scheduler);

        MessageChain ownReply = new MessageChain().at("1001");
        ownReply.add(new MarkdownMessage(card("离火梧桐芝")));
        priceTask.保存市场限价(bot, group, ownReply, "", 7);
        assertEquals(1, callbacks.size());
    }

    @Test
    void duplicateRepliesDuringDelayOnlyScheduleOnce() {
        start();
        receive(bot, group, "离火梧桐芝");
        receive(bot, group, "离火梧桐芝");
        assertEquals(1, callbacks.size());
        callbacks.get(0).run();
        receive(bot, group, "离火梧桐芝");
        assertEquals(1, callbacks.size());
        assertEquals(2, queries(group).size());
    }

    @Test
    void stoppingWhileWaitingIgnoresLateReply() {
        start();
        stop();
        receive(bot, group, "离火梧桐芝");
        verifyNoInteractions(scheduler);
        assertEquals(1, queries(group).size());
        assertTrue(texts(group).stream().anyMatch(t -> t.contains("已停止更新坊市限价，已更新 0/2")));
    }

    @Test
    void stopCancelsDelayedQueryAndOldCallbackCannotAffectRestart() {
        start();
        receive(bot, group, "离火梧桐芝");
        stop();
        verify(futures.get(0)).cancel(false);
        start();
        callbacks.get(0).run();
        assertEquals(List.of("坊市数据离火梧桐芝", "坊市数据离火梧桐芝"), queries(group));
        receive(bot, group, "离火梧桐芝");
        callbacks.get(1).run();
        assertEquals("坊市数据红绫草", queries(group).get(2));
    }

    @Test
    void duplicateStartDoesNotReplaceOrSendAnotherQuery() {
        start();
        start();
        verify(prices).findAll();
        assertEquals(1, queries(group).size());
        assertTrue(texts(group).stream().anyMatch(t -> t.contains("正在更新")));
    }

    @Test
    void categorySupportsNewHerbsWhileExplicitNonHerbCategoryIsExcluded() {
        when(prices.findAll()).thenReturn(Arrays.asList(
                product("新药材", "药材"), product("红绫草", "丹药"), product(" ", "药材"), null));
        start();
        assertEquals(List.of("坊市数据新药材"), queries(group));
        receive(bot, group, "新药材");
        assertTrue(texts(group).contains("坊市限价更新完成，共更新 1 种药材。"));
        verifyNoInteractions(scheduler);
    }

    @Test
    void emptyHerbListDoesNotCreateSession() {
        when(prices.findAll()).thenReturn(List.of(product("寒铁铸心炉", null)));
        start();
        assertTrue(queries(group).isEmpty());
        assertTrue(texts(group).contains("数据库中没有可更新限价的药材。"));
        stop();
        assertTrue(texts(group).contains("当前没有正在更新的坊市限价任务。"));
    }

    @Test
    void malformedQuoteDoesNotAdvanceAndSuccessfulSaveStoresCategory() {
        start();
        priceTask.保存市场限价(bot, group, null,
                card("离火梧桐芝").replace("760万 ~ 1120万", "1120万 ~ 760万"), 8);
        verify(prices, never()).save(any());
        verifyNoInteractions(scheduler);
        receive(bot, group, "离火梧桐芝");
        ArgumentCaptor<ProductPrice> saved = ArgumentCaptor.forClass(ProductPrice.class);
        verify(prices).save(saved.capture());
        assertEquals("药材", saved.getValue().getCategory());
    }

    @Test
    void databaseFailureDoesNotAdvanceToNextHerb() {
        start();
        doThrow(new IllegalStateException("保存失败")).when(prices).save(any(ProductPrice.class));
        assertThrows(IllegalStateException.class, () -> receive(bot, group, "离火梧桐芝"));
        verifyNoInteractions(scheduler);
        assertEquals(1, queries(group).size());
    }

    @Test
    void botsMaintainIndependentSessions() {
        Bot second = mock(Bot.class);
        Group secondGroup = mock(Group.class);
        when(second.getBotId()).thenReturn(1002L);
        when(second.getGroup(2002L)).thenReturn(secondGroup);
        when(secondGroup.getGroupId()).thenReturn(2002L);
        start();
        service.handleCommand(second, secondGroup, "开始更新坊市限价", 9);
        receive(bot, group, "离火梧桐芝");
        receive(second, secondGroup, "离火梧桐芝");
        stop();
        callbacks.get(0).run();
        callbacks.get(1).run();
        assertEquals(1, queries(group).size());
        assertEquals(2, queries(secondGroup).size());
    }

    @Test
    void unavailableExecutionGroupStopsSessionAndAllowsRetry() {
        when(bot.getGroup(2001L)).thenReturn(null);
        start();
        assertTrue(texts(group).stream().anyMatch(t -> t.contains("查询发送失败")));
        when(bot.getGroup(2001L)).thenReturn(group);
        start();
        assertEquals(1, queries(group).size());
    }

    @Test
    void unrelatedCommandsDoNothing() {
        service.handleCommand(bot, group, "开始更新坊市限价说明", 10);
        service.handleCommand(bot, group, null, 11);
        verifyNoInteractions(prices, group, scheduler);
    }

    private void start() { service.handleCommand(bot, group, "开始更新坊市限价", 1); }
    private void stop() { service.handleCommand(bot, group, "停止更新坊市限价", 2); }

    private void receive(Bot source, Group target, String name) {
        MessageChain chain = new MessageChain();
        chain.add(new MarkdownMessage(card(name)));
        priceTask.保存市场限价(source, target, chain, "", 3);
    }

    private ProductPrice product(String name, String category) {
        ProductPrice product = new ProductPrice();
        product.setName(name);
        product.setCategory(category);
        return product;
    }

    private String card(String name) {
        return name + " 所在位置：坊市第6页\n分类：药材\n当前价格: 980万\n"
                + "最高价格：1080万\n最低价格：720万\n市场限价：760万 ~ 1120万";
    }

    private List<MessageChain> sent(Group target) {
        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(target, atLeastOnce()).sendMessage(sent.capture());
        return sent.getAllValues();
    }

    private List<String> texts(Group target) {
        return sent(target).stream().map(Utils::getMessageText).collect(Collectors.toList());
    }

    private List<String> queries(Group target) {
        return sent(target).stream().filter(chain -> !chain.getMessageByType(AtMessage.class).isEmpty())
                .map(Utils::getMessageText).filter(t -> t.startsWith("坊市数据")).collect(Collectors.toList());
    }
}
