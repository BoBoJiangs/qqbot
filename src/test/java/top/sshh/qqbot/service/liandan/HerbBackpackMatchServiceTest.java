package top.sshh.qqbot.service.liandan;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zhuangxv.bot.api.ApiResult;
import com.zhuangxv.bot.api.BaseApi;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.ForwardMessage;
import com.zhuangxv.bot.message.support.ForwardNodeMessage;
import com.zhuangxv.bot.message.support.MarkdownMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.ProductPriceResponse;
import top.sshh.qqbot.service.utils.GetForwardMsgApi;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HerbBackpackMatchServiceTest {
    private HerbBackpackMatchService service;
    private ProductPriceResponse prices;

    @BeforeEach
    void setUp() {
        service = new HerbBackpackMatchService();
        prices = Mockito.mock(ProductPriceResponse.class);
        ReflectionTestUtils.setField(service, "productPriceResponse", prices);
    }

    @Test
    void parseBackpack_supportsPlainMarkdownDuplicateHerbsAndTextWithoutBackpackTitle() {
        String text = "药材背包 第1页\n"
                + "名字：恒心草\n拥有数量:2\n"
                + "名字：[剑魄竹笋](mqqapi://aio/inlinecmd?command=1)\n拥有数量：3---[炼金](mqqapi://x)\n"
                + "名字：恒心草\n拥有数量：5";

        Map<String, Long> inventory = service.parseBackpack(text);

        assertEquals(7L, inventory.get("恒心草"));
        assertEquals(3L, inventory.get("剑魄竹笋"));
        assertEquals(2L, service.parseBackpack("普通消息\n名字：恒心草\n拥有数量:2").get("恒心草"));
    }

    @Test
    void parseBackpack_ignoresOrphanCountAndParsesContinuationPage() {
        String continuationPage = "拥有数量:1炼金|坊市数据\n"
                + "名字：幻心草\n拥有数量:30炼金|坊市数据\n"
                + "名字：鬼臼草\n拥有数量:5炼金|坊市数据\n"
                + "第2页/共5页 上一页 下一页";

        Map<String, Long> inventory = service.parseBackpack(continuationPage);

        assertEquals(2, inventory.size());
        assertEquals(30L, inventory.get("幻心草"));
        assertEquals(5L, inventory.get("鬼臼草"));
    }

    @Test
    void parseBackpack_supportsNameDashQuantityFormatAndIgnoresHeadingsAndPagination() {
        String page = "@咕咕咕丫\n"
                + "冰灵果 - 数量：2 炼金 | 坊市数据\n"
                + "☆------五品药材------☆\n"
                + "地心火芝 - 数量：16 炼金 | 坊市数据\n"
                + "天蝉灵叶 - 数量：14 炼金 | 坊市数据\n"
                + "☆------六品药材------☆\n"
                + "白沉脂 - 数量：12 炼金 | 坊市数据\n"
                + "冰灵果 - 数量：3 炼金 | 坊市数据\n"
                + "第2页/共3页 上一页 下一页";

        Map<String, Long> inventory = service.parseBackpack(page);

        assertEquals(4, inventory.size());
        assertEquals(5L, inventory.get("冰灵果"));
        assertEquals(16L, inventory.get("地心火芝"));
        assertEquals(14L, inventory.get("天蝉灵叶"));
        assertEquals(12L, inventory.get("白沉脂"));
        assertFalse(inventory.containsKey("咕咕咕丫"));
    }

    @Test
    void extractReplyText_usesLastNonBlankTextSegmentAndSupportsMarkdown() {
        MessageChain quoted = new MessageChain();
        quoted.add(new TextMessage("药材背包\n名字：旧药\n拥有数量:1"));
        quoted.add(new TextMessage("  "));
        quoted.add(new MarkdownMessage("药材背包\n名字：[新药](mqqapi://x)\n拥有数量:2"));
        ReplyMessage reply = new ReplyMessage();
        reply.setText("不应使用的兜底文本");
        reply.setChain(quoted);
        MessageChain incoming = new MessageChain();
        incoming.add(reply);

        String result = service.extractReplyText(incoming);

        assertTrue(result.contains("新药"));
        assertFalse(result.contains("旧药"));
    }

    @Test
    void extractReplyText_fallsBackToReplyText() {
        ReplyMessage reply = new ReplyMessage();
        reply.setText("药材背包\n名字：恒心草\n拥有数量:2");
        MessageChain incoming = new MessageChain();
        incoming.add(reply);

        assertTrue(service.extractReplyText(incoming).contains("恒心草"));
    }

    @Test
    void extractReplyText_readsAllForwardNodesAndJoinsContinuationPages() {
        MessageChain incoming = replyTo(forwardChain("forward-1"), null, null);
        Bot bot = Mockito.mock(Bot.class);
        JSONArray messages = new JSONArray();
        messages.add(forwardNode(textSegment("名字：甲药\n拥有数量:2\n名字：跨页药")));
        messages.add(forwardNode(markdownSegment("拥有数量:3\n名字：[乙药](mqqapi://x)\n拥有数量:4")));
        when(bot.invoke(Mockito.any(GetForwardMsgApi.class))).thenReturn(forwardResult(messages));

        String result = service.extractReplyText(incoming, bot);
        Map<String, Long> inventory = service.parseBackpack(result);

        assertEquals(2L, inventory.get("甲药"));
        assertEquals(3L, inventory.get("跨页药"));
        assertEquals(4L, inventory.get("乙药"));
        ArgumentCaptor<BaseApi> apiCaptor = ArgumentCaptor.forClass(BaseApi.class);
        verify(bot).invoke(apiCaptor.capture());
        assertEquals("get_forward_msg", apiCaptor.getValue().getAction());
        assertEquals("forward-1", ((Map<?, ?>) apiCaptor.getValue().getParams()).get("message_id"));
    }

    @Test
    void extractReplyText_fetchesQuotedMessageBeforeReadingForward() {
        MessageChain incoming = replyTo(null, "321", "[聊天记录]");
        Bot bot = Mockito.mock(Bot.class);
        when(bot.getMessage(321)).thenReturn(forwardChain("forward-from-quoted-message"));
        JSONArray messages = new JSONArray();
        messages.add(forwardNode(textSegment("名字：甲药\n拥有数量:5")));
        when(bot.invoke(Mockito.any(GetForwardMsgApi.class))).thenReturn(forwardResult(messages));

        Map<String, Long> inventory = service.parseBackpack(service.extractReplyText(incoming, bot));

        assertEquals(5L, inventory.get("甲药"));
        verify(bot).getMessage(321);
    }

    @Test
    void extractReplyText_retriesWithSnowLumaIdParameter() {
        MessageChain incoming = replyTo(forwardChain("snow-forward"), null, null);
        Bot bot = Mockito.mock(Bot.class);
        ApiResult empty = forwardResult(new JSONArray());
        JSONArray messages = new JSONArray();
        messages.add(forwardNode(textSegment("名字：雪药\n拥有数量:6")));
        when(bot.invoke(Mockito.any(GetForwardMsgApi.class))).thenReturn(empty, forwardResult(messages));

        Map<String, Long> inventory = service.parseBackpack(service.extractReplyText(incoming, bot));

        assertEquals(6L, inventory.get("雪药"));
        ArgumentCaptor<BaseApi> apiCaptor = ArgumentCaptor.forClass(BaseApi.class);
        verify(bot, times(2)).invoke(apiCaptor.capture());
        assertEquals("snow-forward", ((Map<?, ?>) apiCaptor.getAllValues().get(0).getParams()).get("message_id"));
        assertEquals("snow-forward", ((Map<?, ?>) apiCaptor.getAllValues().get(1).getParams()).get("id"));
    }

    @Test
    void extractReplyText_readsNestedForwardAndStringMessage() {
        MessageChain incoming = replyTo(forwardChain("outer-forward"), null, null);
        Bot bot = Mockito.mock(Bot.class);

        JSONObject nestedData = new JSONObject();
        nestedData.put("id", "inner-forward");
        JSONObject nestedSegment = new JSONObject();
        nestedSegment.put("type", "forward");
        nestedSegment.put("data", nestedData);
        JSONArray outerMessages = new JSONArray();
        outerMessages.add(forwardNode(nestedSegment));

        JSONObject innerNode = new JSONObject();
        innerNode.put("message", "名字：嵌套药\n拥有数量:7");
        JSONArray innerMessages = new JSONArray();
        innerMessages.add(innerNode);
        when(bot.invoke(Mockito.any(GetForwardMsgApi.class)))
                .thenReturn(forwardResult(outerMessages), forwardResult(innerMessages));

        Map<String, Long> inventory = service.parseBackpack(service.extractReplyText(incoming, bot));

        assertEquals(7L, inventory.get("嵌套药"));
        verify(bot, times(2)).invoke(Mockito.any(GetForwardMsgApi.class));
    }

    @Test
    void parseCommand_supportsBothModesExplicitCountConfigDefaultAndValidation() {
        HerbBackpackMatchService.MatchCommand alchemy = service.parseCommand("匹配炼金丹 8", 6);
        assertTrue(alchemy.isValid());
        assertEquals(HerbBackpackMatchService.MatchMode.ALCHEMY, alchemy.getMode());
        assertEquals(8, alchemy.getDanCount());

        HerbBackpackMatchService.MatchCommand market = service.parseCommand("匹配坊市丹", 7);
        assertTrue(market.isValid());
        assertEquals(HerbBackpackMatchService.MatchMode.MARKET, market.getMode());
        assertEquals(7, market.getDanCount());

        assertEquals(6, service.parseCommand("匹配炼金丹", 0).getDanCount());
        assertFalse(service.parseCommand("匹配坊市丹 0", 6).isValid());
        assertFalse(service.parseCommand("匹配炼金丹 -1", 6).isValid());
        assertFalse(service.parseCommand("匹配炼金丹 abc", 6).isValid());
    }

    @Test
    void alchemyMatch_prioritizesUnitProfitAndConsumesSharedInventory() {
        mockPrices(Map.of("甲药", 10, "乙药", 10, "丙药", 10));
        DanRecipeBlueprint high = blueprint("高利润丹", 2, 1, 1);
        DanRecipeBlueprint low = blueprint("低利润丹", 1, 1, 1);
        Map<String, Long> inventory = inventory(4, 2, 2);

        HerbBackpackMatchResult result = service.match(inventory, List.of(low, high),
                HerbBackpackMatchService.MatchMode.ALCHEMY, 1,
                Map.of("高利润丹", 200, "低利润丹", 150));

        assertEquals(1, result.getAllocations().size());
        HerbBackpackMatchResult.Allocation allocation = result.getAllocations().get(0);
        assertEquals("高利润丹", allocation.getBlueprint().getDanName());
        assertEquals(2L, allocation.getFurnaceCount());
        assertEquals(320L, allocation.getTotalProfit());
        assertEquals(2L, result.getTotalFurnaces());
        assertEquals(320L, result.getTotalProfit());
        verify(prices, times(1)).findFirstByNameOrderByTimeDescIdDesc("甲药");
        verify(prices, times(1)).findFirstByNameOrderByTimeDescIdDesc("乙药");
        verify(prices, times(1)).findFirstByNameOrderByTimeDescIdDesc("丙药");
    }

    @Test
    void marketMatch_usesLatestDanPriceAndDeductsFee() {
        mockPrices(Map.of("甲药", 10, "乙药", 10, "丙药", 10, "坊市测试丹", 100));

        HerbBackpackMatchResult result = service.match(inventory(1, 1, 1),
                List.of(blueprint("坊市测试丹", 1, 1, 1)),
                HerbBackpackMatchService.MatchMode.MARKET, 2, Map.of());

        HerbBackpackMatchResult.Allocation allocation = result.getAllocations().get(0);
        assertEquals(30L, allocation.getUnitCost());
        assertEquals(160L, allocation.getUnitProfit());
        assertEquals(160L, result.getTotalProfit());
        verify(prices, times(1)).findFirstByNameOrderByTimeDescIdDesc("坊市测试丹");
    }

    @Test
    void match_filtersNonPositiveProfitAndCollectsMissingPricesByCategory() {
        mockPrices(Map.of("甲药", 10, "乙药", 10));
        DanRecipeBlueprint missing = blueprint("缺坊市价丹", 1, 1, 1);

        HerbBackpackMatchResult marketResult = service.match(inventory(1, 1, 1), List.of(missing),
                HerbBackpackMatchService.MatchMode.MARKET, 1, Map.of());
        assertTrue(marketResult.getAllocations().isEmpty());
        assertTrue(marketResult.getMissingHerbPrices().contains("丙药"));
        assertTrue(marketResult.getMissingMarketDanPrices().contains("缺坊市价丹"));

        mockPrices(Map.of("甲药", 10, "乙药", 10, "丙药", 10));
        HerbBackpackMatchResult noAlchemyValue = service.match(inventory(1, 1, 1), List.of(missing),
                HerbBackpackMatchService.MatchMode.ALCHEMY, 1, Map.of());
        assertTrue(noAlchemyValue.getMissingAlchemyValues().contains("缺坊市价丹"));

        HerbBackpackMatchResult zeroProfit = service.match(inventory(1, 1, 1), List.of(missing),
                HerbBackpackMatchService.MatchMode.ALCHEMY, 1, Map.of("缺坊市价丹", 30));
        assertTrue(zeroProfit.getAllocations().isEmpty());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void handleMatch_sendsHeaderAndAllRecipeDetailsAsTwoForwardNodes() throws Exception {
        mockPrices(Map.of(
                "甲药", 10, "乙药", 10, "丙药", 10,
                "丁药", 10, "戊药", 10, "己药", 10));
        DanRecipeQueryService queryService = Mockito.mock(DanRecipeQueryService.class);
        DanCalculator calculator = Mockito.mock(DanCalculator.class);
        Config config = new Config();
        config.setDanNumber(1);
        when(calculator.getConfig(123L)).thenReturn(config);
        when(queryService.getAllRecipeBlueprints()).thenReturn(List.of(
                blueprint("输出测试丹", 1, 1, 1),
                new DanRecipeBlueprint("第二个测试丹", "丁药", 1, "戊药", 1, "己药", 1)));
        when(queryService.getDanAlchemyValues()).thenReturn(Map.of("输出测试丹", 100, "第二个测试丹", 100));
        ReflectionTestUtils.setField(service, "danRecipeQueryService", queryService);
        ReflectionTestUtils.setField(service, "danCalculator", calculator);

        ReplyMessage reply = new ReplyMessage();
        reply.setText("药材背包\n名字：甲药\n拥有数量:1\n名字：乙药\n拥有数量:1\n名字：丙药\n拥有数量:1"
                + "\n名字：丁药\n拥有数量:1\n名字：戊药\n拥有数量:1\n名字：己药\n拥有数量:1");
        MessageChain incoming = new MessageChain();
        incoming.add(reply);
        Group group = Mockito.mock(Group.class);
        Bot bot = Mockito.mock(Bot.class);
        when(bot.getBotId()).thenReturn(123L);

        service.handleMatch(
                "匹配炼金丹",
                incoming,
                group,
                bot,
                HerbBackpackMatchService.MatchRequester.of("测试用户", 456L));

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(group).sendGroupForwardMessage(captor.capture());
        List<?> nodes = captor.getValue();
        assertEquals(2, nodes.size());
        String header = ((ForwardNodeMessage) nodes.get(0)).getContent().toJSONString();
        String details = ((ForwardNodeMessage) nodes.get(1)).getContent().toJSONString();

        assertTrue(header.contains("药材背包匹配：炼金丹｜每炉1丹｜按单炉净利润优先"));
        assertTrue(header.contains("请求者：测试用户"));
        assertFalse(header.contains("QQ：456"));
        assertTrue(header.contains("总炉数：2炉  总利润：140万"));
        assertTrue(header.contains("注：匹配结果会随药材和坊市丹药价格实时变动"));
        assertTrue(details.contains("配方主药甲药1药引乙药1辅药丙药1丹炉寒铁铸心炉"));
        assertTrue(details.contains("配方主药丁药1药引戊药1辅药己药1丹炉寒铁铸心炉"));
        assertTrue(details.contains("可炼制：1炉  利润：70万"));
        assertTrue(details.contains("丹药：输出测试丹  炼金价100万"));
        assertTrue(details.contains("丹药：第二个测试丹  炼金价100万"));
        assertTrue(details.contains("主药甲药*1（10万）"));
        assertTrue(details.contains("药引乙药*1（10万）"));
        assertTrue(details.contains("辅药丙药*1（10万）"));
        int firstRecipe = details.indexOf("配方主药甲药1");
        int firstFurnaceSummary = details.indexOf("可炼制：", firstRecipe);
        int firstDanDetails = details.indexOf("丹药：输出测试丹", firstRecipe);
        assertTrue(firstRecipe >= 0 && firstRecipe < firstFurnaceSummary);
        assertTrue(firstFurnaceSummary < firstDanDetails);
        assertFalse(details.contains("当前10万"));
        verify(group, never()).sendMessage(Mockito.any(MessageChain.class));
    }

    @Test
    void formatWanAmount_usesYiAndKeepsAtMostTwoDecimalPlaces() {
        assertEquals("9999万", HerbBackpackMatchService.formatWanAmount(9999L));
        assertEquals("1亿", HerbBackpackMatchService.formatWanAmount(10000L));
        assertEquals("1.2亿", HerbBackpackMatchService.formatWanAmount(12000L));
        assertEquals("1.23亿", HerbBackpackMatchService.formatWanAmount(12345L));
        assertEquals("1.24亿", HerbBackpackMatchService.formatWanAmount(12350L));
        assertEquals("10亿", HerbBackpackMatchService.formatWanAmount(100000L));
    }

    private DanRecipeBlueprint blueprint(String danName, int mainCount, int leadCount, int assistCount) {
        return new DanRecipeBlueprint(danName, "甲药", mainCount, "乙药", leadCount, "丙药", assistCount);
    }

    private Map<String, Long> inventory(long main, long lead, long assist) {
        Map<String, Long> inventory = new HashMap<>();
        inventory.put("甲药", main);
        inventory.put("乙药", lead);
        inventory.put("丙药", assist);
        return inventory;
    }

    private void mockPrices(Map<String, Integer> values) {
        when(prices.findFirstByNameOrderByTimeDescIdDesc(Mockito.anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            Integer value = values.get(name);
            if (value == null) return null;
            ProductPrice product = new ProductPrice();
            product.setId(1L);
            product.setName(name);
            product.setPrice(value);
            product.setTime(LocalDateTime.now());
            return product;
        });
    }

    private MessageChain replyTo(MessageChain quotedChain, String messageId, String fallbackText) {
        ReplyMessage reply = new ReplyMessage();
        reply.setChain(quotedChain);
        reply.setId(messageId);
        reply.setText(fallbackText);
        MessageChain incoming = new MessageChain();
        incoming.add(reply);
        return incoming;
    }

    private MessageChain forwardChain(String id) {
        ForwardMessage forward = new ForwardMessage();
        forward.setId(id);
        MessageChain chain = new MessageChain();
        chain.add(forward);
        return chain;
    }

    private ApiResult forwardResult(JSONArray messages) {
        JSONObject data = new JSONObject();
        data.put("messages", messages);
        ApiResult result = new ApiResult();
        result.setStatus("ok");
        result.setRetCode(0);
        result.setData(data);
        return result;
    }

    private JSONObject forwardNode(JSONObject... segments) {
        JSONObject node = new JSONObject();
        JSONArray message = new JSONArray();
        Collections.addAll(message, segments);
        node.put("message", message);
        return node;
    }

    private JSONObject textSegment(String text) {
        JSONObject data = new JSONObject();
        data.put("text", text);
        JSONObject segment = new JSONObject();
        segment.put("type", "text");
        segment.put("data", data);
        return segment;
    }

    private JSONObject markdownSegment(String text) {
        JSONObject data = new JSONObject();
        data.put("content", text);
        JSONObject segment = new JSONObject();
        segment.put("type", "markdown");
        segment.put("data", data);
        return segment;
    }
}
