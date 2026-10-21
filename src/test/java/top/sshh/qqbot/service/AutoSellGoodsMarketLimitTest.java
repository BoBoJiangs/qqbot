package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.ReplyMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.Utils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AutoSellGoodsMarketLimitTest {
    @ParameterizedTest
    @CsvSource({"700,7602500", "980,9700000", "1200,11207500", "300000,11207500"})
    void batchListingClampsFinalCommand(int currentPrice, long expected) {
        AutoSellGoods service = new AutoSellGoods();
        ProductPrice product = product(currentPrice);
        ProductPriceResponse prices = prices(product);
        ReflectionTestUtils.setField(service, "productPriceResponse", prices);
        BotConfig config = new BotConfig();
        config.setCommand("批量上架药材");
        Bot bot = bot(config, 87650L + currentPrice);
        Group group = bot.getGroup(config.getGroupId());
        ProductPrice pack = new ProductPrice();
        pack.setName(product.getName());
        pack.setHerbCount(10);

        ReflectionTestUtils.invokeMethod(service, "buyHerbs", new ArrayList<>(List.of(pack)), group, config);

        assertEquals("确认坊市上架 离火梧桐芝 " + expected + " 10", output(group));
    }

    @ParameterizedTest
    @CsvSource({"700,7602500", "1200,11207500", "1050,10402500"})
    void oneClickListingClampsAfterTaxBracketAdjustment(int currentPrice, long expected) {
        TestService service = new TestService();
        ProductPrice product = product(currentPrice);
        if (currentPrice == 1050) product.setMarketMinPrice(10402500L);
        ReflectionTestUtils.setField(service, "productPriceResponse", prices(product));
        ReflectionTestUtils.setField(service, "groupManager", mock(GroupManager.class));
        BotConfig config = new BotConfig();
        Bot bot = bot(config, 98760L + currentPrice);
        Group group = bot.getGroup(config.getGroupId());
        ReplyMessage reply = new ReplyMessage();
        reply.setText("名字：离火梧桐芝\n拥有数量：1 炼金 | 坊市数据");
        MessageChain chain = new MessageChain();
        chain.add(reply);

        service.alchemyAndListed(chain, bot, "一键上架", group);

        assertEquals("确认坊市上架 离火梧桐芝 " + expected + " 1", output(group));
    }

    private ProductPrice product(int price) {
        ProductPrice product = new ProductPrice();
        product.setName("离火梧桐芝");
        product.setPrice(price);
        product.setMarketMinPrice(7602500L);
        product.setMarketMaxPrice(11207500L);
        return product;
    }

    private ProductPriceResponse prices(ProductPrice product) {
        ProductPriceResponse prices = mock(ProductPriceResponse.class);
        when(prices.getFirstByNameOrderByTimeDesc(product.getName())).thenReturn(product);
        return prices;
    }

    private Bot bot(BotConfig config, long groupId) {
        Bot bot = mock(Bot.class);
        Group group = mock(Group.class);
        config.setGroupId(groupId);
        when(bot.getBotId()).thenReturn(groupId + 1000000L);
        when(bot.getBotConfig()).thenReturn(config);
        when(bot.getGroup(groupId)).thenReturn(group);
        when(group.getGroupId()).thenReturn(groupId);
        when(group.getBot()).thenReturn(bot);
        return bot;
    }

    private String output(Group group) {
        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(sent.capture());
        return Utils.getMessageText(sent.getValue());
    }
}
