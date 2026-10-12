package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.Config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAlchemyTaskGroupRoutingTest {
    private final AutoAlchemyTask task = new AutoAlchemyTask();
    private final Map<Long, Bot> previousBots = new HashMap<>();
    private final Config alchemyConfig = new Config();

    @BeforeEach
    void setUp() {
        alchemyConfig.setRandomDelay(0);
        task.danCalculator = mock(DanCalculator.class);
        when(task.danCalculator.getConfig(any(Long.class))).thenReturn(alchemyConfig);
    }

    @AfterEach
    void restoreBots() {
        previousBots.forEach((botId, previousBot) -> {
            if (previousBot == null) {
                BotFactory.getBots().remove(botId);
            } else {
                BotFactory.getBots().put(botId, previousBot);
            }
        });
    }

    @Test
    void eachBotSendsAlchemyCommandsToItsOwnConfiguredGroup() {
        Bot firstBot = registerBot(90001L, 516625967L);
        Bot secondBot = registerBot(90002L, 516625968L);
        Group commandGroup = mock(Group.class);
        when(firstBot.getGroup(60001L)).thenReturn(commandGroup);
        when(secondBot.getGroup(60001L)).thenReturn(commandGroup);
        enqueueRecipe(90001L, "炼丹主药冰灵果1");
        enqueueRecipe(90002L, "炼丹主药地心火芝1");

        ReflectionTestUtils.invokeMethod(task, "autoAlchemy", 90001L);
        ReflectionTestUtils.invokeMethod(task, "autoAlchemy", 90002L);

        verify(firstBot.getGroup(516625967L), timeout(2000)).sendMessage(any(MessageChain.class));
        verify(secondBot.getGroup(516625968L), timeout(2000)).sendMessage(any(MessageChain.class));
        verify(commandGroup, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void alchemyCommandsUseCurrentGroupConfiguration() {
        Bot bot = registerBot(90001L, 516625967L);
        Group previousGroup = bot.getGroup(516625967L);
        Group currentGroup = mock(Group.class);
        when(currentGroup.getBot()).thenReturn(bot);
        when(currentGroup.getGroupId()).thenReturn(516625968L);
        when(bot.getGroup(516625968L)).thenReturn(currentGroup);
        bot.getBotConfig().setGroupId(516625968L);
        enqueueRecipe(90001L, "炼丹主药冰灵果1");

        ReflectionTestUtils.invokeMethod(task, "autoAlchemy", 90001L);

        verify(currentGroup, timeout(2000)).sendMessage(any(MessageChain.class));
        verify(previousGroup, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void stoppedAlchemyDoesNotSendQueuedCommands() {
        Bot bot = registerBot(90001L, 516625967L);
        bot.getBotConfig().setStartAuto(false);
        enqueueRecipe(90001L, "炼丹主药冰灵果1");

        ReflectionTestUtils.invokeMethod(task, "autoAlchemy", 90001L);

        Map<Long, AtomicBoolean> scheduledFlags = field("alchemyDelayScheduledFlagMap");
        assertTimeout(Duration.ofSeconds(2), () -> {
            while (scheduledFlags.get(90001L).get()) {
                Thread.sleep(5);
            }
        });
        verify(bot.getGroup(516625967L), never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void backpackPaginationOnlyProcessesConfiguredExecutionGroup() throws Exception {
        Bot bot = registerBot(90001L, 516625967L);
        bot.getBotConfig().setTaskId(60001L);
        Group taskGroup = mock(Group.class);
        when(taskGroup.getGroupId()).thenReturn(60001L);
        when(bot.getGroup(60001L)).thenReturn(taskGroup);
        MessageChain backpack = new MessageChain().text("冰灵果 - 数量：2 炼金 | 坊市数据\n下一页");

        task.药材背包(bot, taskGroup, null, backpack, backpack.toString(), 1);

        assertTrue(field("medicinalListMap").isEmpty());
        assertTrue(field("pageMap").isEmpty());
        verify(taskGroup, never()).sendMessage(any(MessageChain.class));

        Group executionGroup = bot.getGroup(516625967L);
        task.药材背包(bot, executionGroup, null, backpack, backpack.toString(), 2);

        assertEquals(2, field("pageMap").get(90001L));
        verify(executionGroup).sendMessage(any(MessageChain.class));
        verify(taskGroup, never()).sendMessage(any(MessageChain.class));
    }

    private Bot registerBot(long botId, long groupId) {
        BotConfig config = new BotConfig();
        config.setGroupId(groupId);
        config.setStartAuto(true);
        Bot bot = mock(Bot.class);
        when(bot.getBotId()).thenReturn(botId);
        when(bot.getBotConfig()).thenReturn(config);
        Group group = mock(Group.class);
        when(group.getBot()).thenReturn(bot);
        when(group.getGroupId()).thenReturn(groupId);
        when(bot.getGroup(groupId)).thenReturn(group);
        previousBots.put(botId, BotFactory.getBots().put(botId, bot));
        return bot;
    }

    private void enqueueRecipe(long botId, String recipe) {
        Map<Long, CopyOnWriteArrayList<String>> recipes = field("alchemyListMap");
        recipes.computeIfAbsent(botId, id -> new CopyOnWriteArrayList<>()).add(recipe);
    }

    @SuppressWarnings("unchecked")
    private <V> Map<Long, V> field(String name) {
        return (Map<Long, V>) ReflectionTestUtils.getField(task, name);
    }
}
