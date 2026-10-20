package top.sshh.qqbot.service;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        assertTrue(textOf(sent.getValue()).contains("物品：玄光甲（950万）\n时间："));
        assertFalse(textOf(sent.getValue()).contains("页数："));
        assertTrue(textOf(sent.getValue()).contains("发送『查看提醒帮助』"));
        assertTrue(sent.getValue().getMessageByType(AtMessage.class).stream()
                .anyMatch(at -> String.valueOf(USER_ID).equals(at.getQq())));
    }

    @Test
    void mixedMarketRowsMergeIntoOneReminderAtLowestPrice() {
        String message = MARKET_HEADER
                + "⭐价格:950万 [玄\u200C光\u200C甲](mqqapi://test)\n物品功效\n"
                + "⭐价格:920万 [苦难\u200C行衣](mqqapi://test)\n物品功效\n"
                + "价格:300万 [三阳道袍](mqqapi://test)\n物品功效\n"
                + "翻页 | 全部装备\n"
                + "⭐价格:940万 [玄光甲](mqqapi://test)\n物品功效\n"
                + "⭐价格:920万 [苦难行衣](mqqapi://test)\n物品功效";
        process(message);

        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(bot).sendGroupMessage(eq(GROUP_ID), sent.capture());
        String text = textOf(sent.getValue());
        assertTrue(text.contains("『玄光甲』、『苦难行衣』、『三阳道袍』"));
        assertTrue(text.contains("物品：玄光甲（940万）\n物品：苦难行衣（920万）\n物品：三阳道袍（300万）\n时间："));
        assertEquals(3, text.lines().filter(line -> line.startsWith("物品：")).count());
        assertEquals(1, text.lines().filter(line -> line.startsWith("时间：")).count());
        assertEquals(1, atCount(sent.getValue(), USER_ID));

        process(message);
        verify(bot).sendGroupMessage(eq(GROUP_ID), any(MessageChain.class));
    }

    @Test
    void formatsMultipleItemsWithYiPricesAsRequested() {
        UserRemindConfig config = config(USER_ID, GROUP_ID, "真龙九变", "五指拳心剑");
        when(reminder.groupManager.getUserRemindConfigs(BOT_ID))
                .thenReturn(Collections.singletonMap(String.valueOf(USER_ID), config));
        process(MARKET_HEADER + "价格:33.7亿 [真龙九变](mqqapi://test)\n"
                + "价格:33.7亿 [五指拳心剑](mqqapi://test)");

        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(bot).sendGroupMessage(eq(GROUP_ID), sent.capture());
        String text = textOf(sent.getValue());
        assertTrue(text.contains("『真龙九变』、『五指拳心剑』"));
        assertTrue(text.contains("物品：真龙九变（33.70亿）\n物品：五指拳心剑（33.70亿）\n时间："));
        assertFalse(text.contains("页数："));
        assertFalse(text.contains("\n价格："));
    }

    @Test
    void batchesUsersWithinEachGroupWithoutMixingOtherGroupsItems() {
        long secondUser = USER_ID + 1;
        long otherGroupUser = USER_ID + 2;
        long otherGroup = GROUP_ID + 1;
        Map<String, UserRemindConfig> configs = new LinkedHashMap<>();
        configs.put(String.valueOf(USER_ID), config(USER_ID, GROUP_ID, "玄光甲"));
        configs.put(String.valueOf(secondUser), config(secondUser, GROUP_ID, "玄光甲", "苦难行衣"));
        configs.put(String.valueOf(otherGroupUser), config(otherGroupUser, otherGroup, "苦难行衣"));
        when(reminder.groupManager.getUserRemindConfigs(BOT_ID)).thenReturn(configs);
        when(reminder.groupManager.isBuyRemindGroupEnabled(otherGroup)).thenReturn(true);
        process(MARKET_HEADER + "价格:950万 [玄光甲](mqqapi://test)\n"
                + "价格:920万 [苦难行衣](mqqapi://test)\n价格:300万 [三阳道袍](mqqapi://test)");

        ArgumentCaptor<MessageChain> sameGroup = ArgumentCaptor.forClass(MessageChain.class);
        verify(bot).sendGroupMessage(eq(GROUP_ID), sameGroup.capture());
        assertEquals(1, atCount(sameGroup.getValue(), USER_ID));
        assertEquals(1, atCount(sameGroup.getValue(), secondUser));
        assertEquals(0, atCount(sameGroup.getValue(), otherGroupUser));
        assertTrue(textOf(sameGroup.getValue()).contains("『玄光甲』、『苦难行衣』"));
        assertFalse(textOf(sameGroup.getValue()).contains("三阳道袍"));

        ArgumentCaptor<MessageChain> differentGroup = ArgumentCaptor.forClass(MessageChain.class);
        verify(bot).sendGroupMessage(eq(otherGroup), differentGroup.capture());
        assertEquals(1, atCount(differentGroup.getValue(), otherGroupUser));
        assertFalse(textOf(differentGroup.getValue()).contains("玄光甲"));
        assertTrue(textOf(differentGroup.getValue()).contains("物品：苦难行衣（920万）"));
    }

    @Test
    void itemCooldownStillAllowsNewItemInNextBatch() {
        process(MARKET_HEADER + "价格:950万 [玄光甲](mqqapi://test)");
        process(MARKET_HEADER + "价格:950万 [玄光甲](mqqapi://test)\n"
                + "价格:920万 [苦难行衣](mqqapi://test)");

        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(bot, times(2)).sendGroupMessage(eq(GROUP_ID), sent.capture());
        String second = textOf(sent.getAllValues().get(1));
        assertFalse(second.contains("玄光甲"));
        assertTrue(second.contains("物品：苦难行衣（920万）"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"发送【查看提醒帮助】", "发送『查看提醒帮助』"})
    void generatedRemindersDoNotTriggerHelpCommands(String helpPrompt) {
        Group group = mock(Group.class);
        Member member = mock(Member.class);
        MessageChain chain = new MessageChain().at(String.valueOf(BOT_ID));
        String text = "物品：玄光甲（950万）\n" + helpPrompt + "，也可以订制你的专属提醒";

        reminder.专属提醒命令(bot, group, member, chain, text, 1);

        verifyNoInteractions(group, reminder.groupManager);
    }

    private void process(String message) {
        ReflectionTestUtils.invokeMethod(reminder, "processMarketMessage", bot, message);
    }

    private UserRemindConfig config(long user, long group, String... items) {
        UserRemindConfig config = new UserRemindConfig();
        config.setUserQq(user);
        config.setGroupId(group);
        config.setItems(new HashSet<>(Arrays.asList(items)));
        return config;
    }

    private long atCount(MessageChain chain, long user) {
        return chain.getMessageByType(AtMessage.class).stream()
                .filter(at -> String.valueOf(user).equals(at.getQq())).count();
    }

    private String textOf(MessageChain chain) {
        return chain.getMessageByType(TextMessage.class).stream()
                .map(TextMessage::getText).reduce("", String::concat);
    }
}
