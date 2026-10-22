package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.RemindTime;
import top.sshh.qqbot.service.storage.LocalDataFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameRemindersPersistenceTest {
    @TempDir Path directory;
    private static final long BOT_ID = 9988776611L;
    private static final long GROUP_ID = 66778899L;
    private static final long USER_ID = 1234567L;
    private Path path;
    private Bot bot;
    private Bot previousBot;
    private Group group;

    @BeforeEach
    void setUp() {
        path = directory.resolve("group-tasks.json");
        previousBot = BotFactory.getBots().remove(BOT_ID);
        bot = mock(Bot.class);
        when(bot.getBotId()).thenReturn(BOT_ID);
        group = mock(Group.class);
        when(group.getGroupId()).thenReturn(GROUP_ID);
    }

    @AfterEach
    void restoreBots() {
        BotFactory.getBots().remove(BOT_ID);
        if (previousBot != null) BotFactory.getBots().put(BOT_ID, previousBot);
    }

    private GroupManager fresh() {
        GroupManager manager = new GroupManager(path);
        manager.groupSettlementReminderMap.put(Long.toString(GROUP_ID), true);
        return manager;
    }

    private GroupManager restarted() {
        GroupManager manager = new GroupManager(path);
        manager.loadTasksFromFile();
        return manager;
    }

    @Test
    void eachExplorationReminderIsSavedAtRegistrationWithoutWaitingForShutdown() {
        for (String type : new String[]{"悬赏", "秘境", "秘域"}) {
            GroupManager first = fresh();
            first.extractInfo("@" + USER_ID + " 预计5分钟", type, group, bot, USER_ID);
            GroupManager next = restarted();
            RemindTime reminder = next.mjXslmap.get(Long.toString(USER_ID));
            assertNotNull(reminder);
            assertEquals(type, reminder.getText());
            assertEquals(BOT_ID, reminder.getRemindQq());
            assertEquals(GROUP_ID, reminder.getGroupId());
            assertEquals(first.mjXslmap.get(Long.toString(USER_ID)).getExpireTime(), reminder.getExpireTime());
        }
    }

    @Test
    void fieldReminderIsSavedImmediatelyAndKeepsAbsoluteTime() {
        GroupManager first = fresh();
        ReflectionTestUtils.invokeMethod(first, "updateLingTianTimer", Long.toString(USER_ID), "1.5", group, BOT_ID);
        GroupManager next = restarted();
        assertEquals(first.ltmap.get(Long.toString(USER_ID)).getExpireTime(),
                next.ltmap.get(Long.toString(USER_ID)).getExpireTime());
    }

    private GroupManager expired(String type) {
        GroupManager first = fresh();
        RemindTime reminder = new RemindTime();
        reminder.setQq(USER_ID);
        reminder.setGroupId(GROUP_ID);
        reminder.setRemindQq(BOT_ID);
        reminder.setText(type);
        reminder.setExpireTime(System.currentTimeMillis() - 3600000L);
        if ("灵田".equals(type)) first.ltmap.put(Long.toString(USER_ID), reminder);
        else first.mjXslmap.put(Long.toString(USER_ID), reminder);
        first.saveTasksToFile();
        return restarted();
    }

    @Test
    void overdueOfflineReminderIsKeptAndSentOnceWhenBotReturnsEvenAfterThirtyMinutes() {
        GroupManager manager = expired("秘域");
        manager.结算();
        assertFalse(restarted().mjXslmap.isEmpty());

        BotFactory.getBots().put(BOT_ID, bot);
        when(bot.sendGroupMessage(eq(GROUP_ID), any(MessageChain.class))).thenReturn(-123);
        manager.结算();
        assertTrue(restarted().mjXslmap.isEmpty());
        restarted().结算();
        verify(bot, times(1)).sendGroupMessage(eq(GROUP_ID), any(MessageChain.class));
    }

    @Test
    void failedDeliveryIsNotRemovedAndCanBeRetriedAfterRestart() {
        GroupManager manager = expired("秘境");
        BotFactory.getBots().put(BOT_ID, bot);
        when(bot.sendGroupMessage(anyLong(), any(MessageChain.class)))
                .thenReturn(0).thenThrow(new IllegalStateException("连接断开")).thenReturn(456);
        manager.结算();
        assertFalse(restarted().mjXslmap.isEmpty());
        restarted().结算();
        assertFalse(restarted().mjXslmap.isEmpty());
        restarted().结算();
        assertTrue(restarted().mjXslmap.isEmpty());
    }

    @Test
    void offlineFieldReminderAlsoSurvivesUntilSuccessfulDelivery() {
        GroupManager manager = expired("灵田");
        manager.结算();
        assertFalse(restarted().ltmap.isEmpty());
        BotFactory.getBots().put(BOT_ID, bot);
        when(bot.sendGroupMessage(anyLong(), any(MessageChain.class))).thenReturn(-1);
        manager.结算();
        assertTrue(restarted().ltmap.isEmpty());
    }

    @Test
    void oldDocumentsWithoutExplorationRemindersStillLoadExistingFieldTimers() throws Exception {
        expired("灵田");
        JSONObject legacy = JSON.parseObject(LocalDataFiles.readString(path));
        legacy.remove("悬赏秘境秘域提醒");
        LocalDataFiles.writeString(path, JSON.toJSONString(legacy), StandardCharsets.UTF_8);
        GroupManager restored = restarted();
        assertEquals(1, restored.ltmap.size());
        assertTrue(restored.mjXslmap.isEmpty());
    }
}
