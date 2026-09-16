package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.message.support.MarkdownMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PriceTaskCommandConflictTest {

    @Test
    void backpackMatchCommands_doNotTriggerPriceQueryHandler() {
        PriceTask task = new PriceTask();
        Bot bot = Mockito.mock(Bot.class);
        Group group = Mockito.mock(Group.class);
        MessageChain reply = herbReply();

        task.查上架价格(bot, group, null, reply, "@123 匹配炼金丹 6", 1);
        task.查上架价格(bot, group, null, reply, "@123 匹配坊市丹 6", 2);

        verifyNoInteractions(group);
    }

    @Test
    void originalRefiningPriceCommand_stillWorks() {
        PriceTask task = new PriceTask();
        Bot bot = Mockito.mock(Bot.class);
        BotConfig config = Mockito.mock(BotConfig.class);
        Group group = Mockito.mock(Group.class);
        when(bot.getBotConfig()).thenReturn(config);
        when(config.isEnableCheckPrice()).thenReturn(true);

        task.查上架价格(bot, group, null, herbReply(), "炼金", 3);

        ArgumentCaptor<MessageChain> messageCaptor = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(messageCaptor.capture());
        String output = messageCaptor.getValue().getMessageByType(TextMessage.class).stream()
                .map(TextMessage::getText)
                .reduce("", String::concat);
        assertTrue(output.contains("炼金 红绫草 1 总价:110万"));
    }

    @Test
    void quotedInlineHerbRows_areUsedByRefiningPriceQuery() {
        PriceTask task = new PriceTask();
        Bot bot = Mockito.mock(Bot.class);
        BotConfig config = Mockito.mock(BotConfig.class);
        Group group = Mockito.mock(Group.class);
        when(bot.getBotConfig()).thenReturn(config);
        when(config.isEnableCheckPrice()).thenReturn(true);

        ReplyMessage reply = new ReplyMessage();
        MessageChain quoted = new MessageChain();
        quoted.add(new MarkdownMessage("@咕咕咕丫\n冰灵果 - 数量：2 炼金 | 坊市数据\n☆------五品药材------☆"));
        reply.setChain(quoted);
        MessageChain chain = new MessageChain();
        chain.add(reply);
        task.查上架价格(bot, group, null, chain, "炼金", 4);

        ArgumentCaptor<MessageChain> messageCaptor = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(messageCaptor.capture());
        String output = messageCaptor.getValue().getMessageByType(TextMessage.class).stream()
                .map(TextMessage::getText)
                .reduce("", String::concat);
        assertTrue(output.contains("炼金 冰灵果 2 总价:"));
    }

    private MessageChain herbReply() {
        ReplyMessage reply = new ReplyMessage();
        reply.setText("药材背包\n名字：红绫草\n拥有数量:1炼金|坊市数据");
        MessageChain chain = new MessageChain();
        chain.add(reply);
        return chain;
    }
}
