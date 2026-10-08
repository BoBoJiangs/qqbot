package top.sshh.qqbot.service;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.UserRemindConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuyGoodsReminderTest {
    private static final long BOT_ID = 1001L;
    private static final long GROUP_ID = 2001L;
    private static final long USER_ID = 3001L;
    private static final String MARKET_HEADER = "不鼓励不保障任何第三方交易行为，风险极大，容易给骗子可趁之机！\n";

    private BuyGoodsReminder reminder;
    private Bot bot;

    @BeforeEach
    void setUp() {
        reminder = new BuyGoodsReminder();
        reminder.groupManager = mock(GroupManager.class);
        bot = mock(Bot.class);
        when(bot.getBotId()).thenReturn(BOT_ID);

        UserRemindConfig config = new UserRemindConfig();
        config.setUserQq(USER_ID);
        config.setGroupId(GROUP_ID);
        config.setItems(new HashSet<>(Arrays.asList("玄光甲", "苦难行衣", "三阳道袍")));
        when(reminder.groupManager.getUserRemindConfigs(BOT_ID))
                .thenReturn(Collections.singletonMap(String.valueOf(USER_ID), config));
        when(reminder.groupManager.isBuyRemindGroupEnabled(GROUP_ID)).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "⭐", "⭐\uFE0F", "  ⭐\uFE0F "})
    void marketRowsRemindSubscribedUserWithOrWithoutFavoriteMarker(String prefix) {
        process(MARKET_HEADER + prefix + "价格:950万 [玄\u200C光\u200C甲](mqqapi://test)\n物品功效");

        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(bot).sendGroupMessage(eq(GROUP_ID), sent.capture());
        assertTrue(textOf(sent.getValue()).contains("物品：玄光甲\n价格：950万\n页数：装备1"));
        assertTrue(sent.getValue().getMessageByType(AtMessage.class).stream()
                .anyMatch(at -> String.valueOf(USER_ID).equals(at.getQq())));
    }

    @Test
    void mixedMarketRowsRemindOncePerItemAtLowestPrice() {
        String message = MARKET_HEADER
                + "⭐价格:950万 [玄\u200C光\u200C甲](mqqapi://test)\n物品功效\n"
                + "⭐价格:920万 [苦难\u200C行衣](mqqapi://test)\n物品功效\n"
                + "价格:300万 [三阳道袍](mqqapi://test)\n物品功效\n"
                + "翻页 | 全部装备\n"
                + "⭐价格:940万 [玄光甲](mqqapi://test)\n物品功效\n"
                + "⭐价格:920万 [苦难行衣](mqqapi://test)\n物品功效";
        process(message);

        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(bot, times(3)).sendGroupMessage(eq(GROUP_ID), sent.capture());
        List<String> texts = sent.getAllValues().stream().map(this::textOf).collect(Collectors.toList());
        assertEquals(1, texts.stream().filter(text -> text.contains("物品：玄光甲\n价格：940万")).count());
        assertEquals(1, texts.stream().filter(text -> text.contains("物品：苦难行衣\n价格：920万")).count());
        assertEquals(1, texts.stream().filter(text -> text.contains("物品：三阳道袍\n价格：300万")).count());

        process(message);
        verify(bot, times(3)).sendGroupMessage(eq(GROUP_ID), any(MessageChain.class));
    }

    private void process(String message) {
        ReflectionTestUtils.invokeMethod(reminder, "processMarketMessage", bot, message, "装备1");
    }

    private String textOf(MessageChain chain) {
        return chain.getMessageByType(TextMessage.class).stream()
                .map(TextMessage::getText).reduce("", String::concat);
    }
}
