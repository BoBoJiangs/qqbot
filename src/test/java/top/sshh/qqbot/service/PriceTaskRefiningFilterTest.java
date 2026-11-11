package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.MarkdownMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.ProductLowPrice;
import top.sshh.qqbot.data.ProductPrice;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceTaskRefiningFilterTest {
    private static final long BOT_ID = 1001L;
    private final PriceTask task = new PriceTask();
    private final GroupManager groupManager = mock(GroupManager.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(task, "groupManager", groupManager);
    }

    @ParameterizedTest
    @CsvSource({
            "天尘丹, true", "天尘丹, false", "易筋丹, true", "易筋丹, false"
    })
    void refiningQuerySkipsCustomExcludedItemsAndTheirValueInBothInventoryFormats(String name, boolean inline) {
        when(groupManager.isAlchemyExcluded(BOT_ID, name)).thenReturn(true);
        Group group = mock(Group.class);
        String excludedRow = inline
                ? "[" + name + "](mqqapi://test) - 数量：3 炼金 | 坊市数据 | 查看效果"
                : "名字：[" + name + "](mqqapi://test)\n拥有数量：3 炼金 | 查看效果";
        MessageChain reply = quoted("丹药背包\n" + excludedRow
                + "\n红绫草 - 数量：2 炼金 | 坊市数据 | 查看效果", inline);

        task.查上架价格(enabledBot(), group, null, reply, "查炼金", 1);

        String output = sentText(group);
        assertTrue(output.contains("炼金 红绫草 2 总价:220万"), output);
        assertTrue(output.contains("总价值：220 万"), output);
        assertFalse(output.contains("炼金 " + name + " "), output);
        assertTrue(output.contains("已过滤炼金排除物品（不计入总价值）"), output);
    }

    @ParameterizedTest
    @CsvSource({
            "天尘丹, true", "天尘丹, false", "易筋丹, true", "易筋丹, false"
    })
    void otherPillsAreIncludedUnlessCustomExcluded(String name, boolean inline) {
        Group group = mock(Group.class);
        String row = inline ? "[" + name + "](mqqapi://test) - 数量：390 炼金 | 查看效果"
                : "名字：" + name + "\n物品功效 拥有数量:390---炼金 |坊市数据";

        task.查上架价格(enabledBot(), group, null, quoted(row, inline), "查炼金", 4);

        String output = sentText(group);
        assertTrue(output.contains("炼金 " + name + " 390 总价:" + 390L * ProductLowPrice.getLowPrice(name) + "万"), output);
        assertFalse(output.contains("已过滤"), output);
    }

    @ParameterizedTest
    @CsvSource({
            "渡厄丹, true", "寒铁铸心炉, true", "陨铁炉, true", "雕花紫铜炉, true",
            "渡厄丹, false", "寒铁铸心炉, false", "陨铁炉, false", "雕花紫铜炉, false"
    })
    void protectedItemsAreExcludedEvenWhenCustomExclusionsAreEmpty(String name, boolean inline) {
        Group group = mock(Group.class);
        String row = inline ? "[" + name + "](mqqapi://test) - 数量：3 炼金 | 查看效果"
                : "名字：" + name + "\n拥有数量：3 炼金 | 查看效果";

        task.查上架价格(enabledBot(), group, null, quoted(row
                + "\n易筋丹 - 数量：2 炼金 | 查看效果", inline), "查炼金", 5);

        String output = sentText(group);
        assertFalse(output.contains("炼金 " + name + " "), output);
        assertTrue(output.contains("炼金 易筋丹 2 总价:420万"), output);
        assertTrue(output.contains("总价值：420 万"), output);
        assertTrue(output.contains("已过滤炼金排除物品（不计入总价值）"), output);
    }

    @Test
    void allCustomExcludedItemsReturnAnExplicitNoticeInsteadOfRefiningCommands() {
        for (String name : List.of("天尘丹", "易筋丹")) {
            when(groupManager.isAlchemyExcluded(BOT_ID, name)).thenReturn(true);
        }
        Group group = mock(Group.class);
        MessageChain reply = quoted("丹药背包\n天尘丹 - 数量：390 炼金 | 查看效果\n"
                + "名字：[易筋丹](mqqapi://test)\n拥有数量：4 炼金 | 查看效果", true);

        task.查上架价格(enabledBot(), group, null, reply, "查炼金", 2);

        String output = sentText(group);
        assertTrue(output.contains("已过滤炼金排除物品，本次没有可生成的炼金命令。"), output);
        assertFalse(output.contains("炼金 "), output);
        assertFalse(output.contains("总价值"), output);
    }

    @Test
    void customAlchemyExclusionDoesNotAffectMarketPriceQueriesForTheSameItems() {
        when(groupManager.isAlchemyExcluded(BOT_ID, "寒铁铸心炉")).thenReturn(true);
        when(groupManager.isAlchemyExcluded(BOT_ID, "渡厄丹")).thenReturn(true);
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
        when(bot.getBotId()).thenReturn(BOT_ID);
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
