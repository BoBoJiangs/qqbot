package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FieldTaskPersistenceTest {
    @TempDir Path directory;
    private static final long BOT_ID = 9988776621L;
    private static final long GROUP_ID = 66778899L;
    private Path path;
    private Bot bot;
    private Group group;
    private Bot previousBot;

    @BeforeEach
    void setUp() {
        path = directory.resolve("field.ser");
        previousBot = BotFactory.getBots().remove(BOT_ID);
        bot = mock(Bot.class);
        BotConfig config = new BotConfig();
        config.setGroupId(GROUP_ID);
        config.setEnableAutoField(true);
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotConfig()).thenReturn(config);
        group = mock(Group.class);
        when(group.getGroupId()).thenReturn(GROUP_ID);
    }

    @AfterEach
    void restoreBots() {
        BotFactory.getBots().remove(BOT_ID);
        if (previousBot != null) BotFactory.getBots().put(BOT_ID, previousBot);
    }

    private FamilyTask fresh() {
        return new FamilyTask(path);
    }

    private FamilyTask restarted() {
        FamilyTask task = fresh();
        task.init();
        return task;
    }

    @Test
    void receivedFieldTimeIsSavedImmediatelyAndRestoredWithoutShutdown() throws Exception {
        FamilyTask task = fresh();
        ReflectionTestUtils.setField(task, "xxGroupId", 0L);
        task.灵田领取结果(bot, group, null, new MessageChain(),
                "@" + BOT_ID + " 灵田还不能收取，下次收取时间为：1.5小时", 1);
        assertNotNull(task.remindMap.get(BOT_ID));
        assertEquals(task.remindMap.get(BOT_ID), restarted().remindMap.get(BOT_ID));
        assertTrue(restarted().remindMap.get(BOT_ID) > System.currentTimeMillis());
    }

    @Test
    void noFieldMarkerIsPersistedWithoutBeingRemovedDuringSave() throws Exception {
        FamilyTask task = fresh();
        ReflectionTestUtils.setField(task, "xxGroupId", 0L);
        task.灵田领取结果(bot, group, null, new MessageChain(), "@" + BOT_ID + " 还没有洞天福地", 1);
        assertEquals(9223372036854175807L, restarted().remindMap.get(BOT_ID));
    }

    @Test
    void overdueTimerIsNotDiscardedWhenBotOrGroupIsUnavailable() throws Exception {
        FamilyTask task = fresh();
        long due = System.currentTimeMillis() - 3600000L;
        ReflectionTestUtils.invokeMethod(task, "setFieldTime", BOT_ID, due);
        task.灵田领取();
        assertEquals(due, restarted().remindMap.get(BOT_ID));
        BotFactory.getBots().put(BOT_ID, bot);
        task.灵田领取();
        assertEquals(due, restarted().remindMap.get(BOT_ID));
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void successfulSubmissionKeepsOriginalDueTimeUntilGameReturnsNextTime() throws Exception {
        FamilyTask task = fresh();
        long due = System.currentTimeMillis() - 3600000L;
        ReflectionTestUtils.invokeMethod(task, "setFieldTime", BOT_ID, due);
        BotFactory.getBots().put(BOT_ID, bot);
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        when(group.sendMessage(any(MessageChain.class))).thenReturn(-10);
        task.灵田领取();
        assertEquals(due, restarted().remindMap.get(BOT_ID));
        ReflectionTestUtils.setField(task, "xxGroupId", 0L);
        task.灵田领取结果(bot, group, null, new MessageChain(),
                "@" + BOT_ID + " 灵田还不能收取，下次收取时间为：2小时", 1);
        assertTrue(restarted().remindMap.get(BOT_ID) > System.currentTimeMillis());
    }
}
