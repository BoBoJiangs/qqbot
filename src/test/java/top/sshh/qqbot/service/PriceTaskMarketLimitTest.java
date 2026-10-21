package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.MarkdownMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.Utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PriceTaskMarketLimitTest {
    private final PriceTask task = new PriceTask();
    private final ProductPriceResponse prices = mock(ProductPriceResponse.class);
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(task, "productPriceResponse", prices);
        BotConfig config = new BotConfig();
        config.setEnableCheckPrice(true);
        // 即使启用捡漏，也应采集限价。
        config.setEnableAutoBuyLowPrice(true);
        when(bot.getBotConfig()).thenReturn(config);
    }

    @Test
    void createsProductFromMarketCardEvenWithAutomaticBuyingEnabled() {
        MessageChain card = new MessageChain();
        card.add(new MarkdownMessage(cardText()));
        task.保存市场限价(bot, group, card, "@道友", 1);

        ArgumentCaptor<ProductPrice> saved = ArgumentCaptor.forClass(ProductPrice.class);
        verify(prices).save(saved.capture());
        ProductPrice product = saved.getValue();
        assertEquals("离火梧桐芝", product.getName());
        assertEquals(980, product.getPrice());
        assertEquals(7600000L, product.getMarketMinPrice());
        assertEquals(11200000L, product.getMarketMaxPrice());
        assertNotNull(product.getTime());
    }

    @Test
    void updatesLimitsWhenCurrentPriceHasNotChangedAndPreservesIdentity() {
        ProductPrice product = product(980);
        product.setId(9L);
        product.setCode("existing-code");
        product.setMarketMinPrice(7200000L);
        product.setMarketMaxPrice(10800000L);
        when(prices.getFirstByNameOrderByTimeDesc(product.getName())).thenReturn(product);

        task.保存市场限价(bot, group, null, cardText(), 2);

        verify(prices).save(product);
        assertEquals(9L, product.getId());
        assertEquals("existing-code", product.getCode());
        assertEquals(7600000L, product.getMarketMinPrice());
        assertEquals(11200000L, product.getMarketMaxPrice());
    }

    @Test
    void malformedCardDoesNotOverwriteKnownLimits() {
        task.保存市场限价(bot, group, null,
                cardText().replace("760.00万 ~ 1120.00万", "1120万 ~ 760万"), 3);
        verifyNoInteractions(prices);
    }

    @Test
    void ordinaryMarketPriceUpdatesKeepPreviouslyCollectedLimits() throws Exception {
        ProductPrice product = product(980);
        when(prices.getFirstByNameOrderByTimeDesc(product.getName())).thenReturn(product);
        bot.getBotConfig().setEnableAutoBuyLowPrice(false);

        task.保存商品价格(bot, group, "不鼓励不保障任何第三方交易行为\n"
                + "价格:1000万 [离火梧桐芝](mqqapi://test?command=%E5%9D%8A%E5%B8%82%E8%B4%AD%E4%B9%B0code-2&x=1)", 4);

        verify(prices, timeout(3000)).save(product);
        assertEquals(1000, product.getPrice());
        assertEquals(7600000L, product.getMarketMinPrice());
        assertEquals(11200000L, product.getMarketMaxPrice());
    }

    @ParameterizedTest
    @CsvSource({"700,7600000", "980,9700000", "1200,11200000", "1050,10400000"})
    void directQueryUsesMarketBoundsAfterOtherPriceAdjustments(int currentPrice, long expected) {
        ProductPrice product = product(currentPrice);
        // 1050 万会先因税档调整至 1000 万，再按新的最低限价回升。
        if (currentPrice == 1050) product.setMarketMinPrice(10400000L);
        when(prices.getFirstByNameOrderByTimeDesc(product.getName())).thenReturn(product);

        task.查上架价格(bot, group, null, new MessageChain(), "查上架价格\n离火梧桐芝", 5);

        assertTrue(output().contains("确认坊市上架 离火梧桐芝 " + expected));
    }

    @Test
    void quotedBackpackUsesFractionalLimitsForCommandsValueAndFee() {
        ProductPrice product = product(700);
        product.setMarketMinPrice(7602500L);
        when(prices.getFirstByNameOrderByTimeDesc(product.getName())).thenReturn(product);
        ReplyMessage reply = new ReplyMessage();
        reply.setText("离火梧桐芝 - 数量：12 炼金 | 坊市数据");
        MessageChain chain = new MessageChain();
        chain.add(reply);

        task.查上架价格(bot, group, null, chain, "查上架价格", 6);

        String output = output();
        assertTrue(output.contains("确认坊市上架 离火梧桐芝 7602500 10"), output);
        assertTrue(output.contains("确认坊市上架 离火梧桐芝 7602500 2"), output);
        assertTrue(output.contains("总价值：9123 万"), output);
        assertTrue(output.contains("手续费：912.3 万"), output);
        assertTrue(output.contains("实际到手：8210.7 万"), output);
    }

    @Test
    @SuppressWarnings("unchecked")
    void existingPriceSnapshotRestoresLimitsOnRestart(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve("properties"));
        ProductPrice product = product(980);
        product.setTime(LocalDateTime.now());
        when(prices.findAll()).thenReturn(List.of(product));
        String oldTargetDir = PriceTask.targetDir;
        PriceTask.targetDir = directory.toString() + "/";
        try {
            task.savePrice();
            task.readPrice();
            ArgumentCaptor<Iterable<ProductPrice>> saved = ArgumentCaptor.forClass(Iterable.class);
            verify(prices).saveAll(saved.capture());
            ProductPrice restored = saved.getValue().iterator().next();
            assertEquals(7600000L, restored.getMarketMinPrice());
            assertEquals(11200000L, restored.getMarketMaxPrice());
        } finally {
            PriceTask.targetDir = oldTargetDir;
        }
    }

    private ProductPrice product(int price) {
        ProductPrice product = new ProductPrice();
        product.setName("离火梧桐芝");
        product.setPrice(price);
        product.setMarketMinPrice(7600000L);
        product.setMarketMaxPrice(11200000L);
        return product;
    }

    private String cardText() {
        return "离火梧桐芝 所在位置：坊市第6页\n当前价格: 980万 点击上架 | 点击购买\n"
                + "最高价格: 1080万\n最低价格: 720万\n市场限价：760.00万 ~ 1120.00万 | 🔺";
    }

    private String output() {
        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(sent.capture());
        return Utils.getMessageText(sent.getValue());
    }
}
