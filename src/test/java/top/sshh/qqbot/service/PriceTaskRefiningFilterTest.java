package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.MarkdownMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.ProductPrice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceTaskRefiningFilterTest {
    @ParameterizedTest
    @CsvSource({
            "渡厄丹, true", "寒铁铸心炉, true", "陨铁炉, true", "雕花紫铜炉, true",
            "渡厄丹, false", "寒铁铸心炉, false", "陨铁炉, false", "雕花紫铜炉, false"
    })
    void refiningQuerySkipsProtectedItemsAndTheirValueInBothInventoryFormats(String name, boolean inline) {
        PriceTask task = new PriceTask();
        Group group = mock(Group.class);
        String protectedRow = inline
                ? "[" + name + "](mqqapi://test) - 数量：3 炼金 | 坊市数据 | 查看效果"
                : "名字：[" + name + "](mqqapi://test)\n拥有数量：3 炼金 | 查看效果";
        MessageChain reply = quoted("丹药背包\n" + protectedRow
                + "\n易筋丹 - 数量：2 炼金 | 坊市数据 | 查看效果", inline);

        task.查上架价格(enabledBot(), group, null, reply, "查炼金", 1);

        String output = sentText(group);
        assertTrue(output.contains("炼金 易筋丹 2 总价:420万"), output);
        assertTrue(output.contains("总价值：420 万"), output);
        assertFalse(output.contains("炼金 " + name + " "), output);
        assertTrue(output.contains("已过滤炼丹炉和渡厄丹（不计入总价值）"), output);
    }

    @Test
    void allProtectedItemsReturnAnExplicitNoticeInsteadOfRefiningCommands() {
        PriceTask task = new PriceTask();
        Group group = mock(Group.class);
        MessageChain reply = quoted("丹药背包\n寒铁铸心炉 - 数量：1 炼金 | 查看效果\n"
                + "陨铁炉 - 数量：2 炼金 | 查看效果\n雕花紫铜炉 - 数量：3 炼金 | 查看效果\n"
                + "名字：[渡厄丹](mqqapi://test)\n拥有数量：4 炼金 | 查看效果", true);

        task.查上架价格(enabledBot(), group, null, reply, "查炼金", 2);

        String output = sentText(group);
        assertTrue(output.contains("已过滤炼丹炉和渡厄丹，本次没有可生成的炼金命令。"), output);
        assertFalse(output.contains("炼金 "), output);
        assertFalse(output.contains("总价值"), output);
    }

    @Test
    void protectionDoesNotAffectMarketPriceQueriesForTheSameItems() {
        PriceTask task = new PriceTask();
        Group group = mock(Group.class);
        ProductPriceResponse prices = mock(ProductPriceResponse.class);
        ReflectionTestUtils.setField(task, "productPriceResponse", prices);
        ProductPrice furnace = new ProductPrice();
        furnace.setName("寒铁铸心炉");
        furnace.setPrice(500);
        ProductPrice pill = new ProductPrice();
        pill.setName("渡厄丹");
        pill.setPrice(100);
        when(prices.getFirstByNameOrderByTimeDesc("寒铁铸心炉")).thenReturn(furnace);
        when(prices.getFirstByNameOrderByTimeDesc("渡厄丹")).thenReturn(pill);

        task.查上架价格(enabledBot(), group, null, quoted("丹药背包\n"
                + "寒铁铸心炉 - 数量：1 查看效果\n渡厄丹 - 数量：2 查看效果", true), "查上架价格", 3);

        String output = sentText(group);
        assertTrue(output.contains("确认坊市上架 寒铁铸心炉 "), output);
        assertTrue(output.contains("确认坊市上架 渡厄丹 "), output);
        assertFalse(output.contains("已过滤"), output);
    }

    private Bot enabledBot() {
        Bot bot = mock(Bot.class);
        BotConfig config = mock(BotConfig.class);
        when(bot.getBotConfig()).thenReturn(config);
        when(config.isEnableCheckPrice()).thenReturn(true);
        return bot;
    }

    private MessageChain quoted(String text, boolean markdown) {
        ReplyMessage reply = new ReplyMessage();
        if (markdown) {
            MessageChain chain = new MessageChain();
            chain.add(new MarkdownMessage(text));
            reply.setChain(chain);
        } else {
            reply.setText(text);
        }
        MessageChain message = new MessageChain();
        message.add(reply);
        return message;
    }

    private String sentText(Group group) {
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(messages.capture());
        return messages.getValue().getMessageByType(TextMessage.class).stream()
                .map(TextMessage::getText).reduce("", String::concat);
    }
}
