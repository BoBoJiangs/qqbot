package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.Config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void frequencyLimitStopsOnlyTheAffectedBotAndDoesNotContinueAutomaticPurchases() throws Exception {
        Bot bot = registerBot(90001L, 516625967L);
        Bot otherBot = registerBot(90002L, 516625968L);
        enqueueRecipe(90001L, "配方主药冰灵果1");
        enqueueRecipe(90002L, "配方主药地心火芝1");
        alchemyConfig.setFinishAutoBuyHerb(true);
        Map<Long, AtomicBoolean> flags = field("alchemyDelayScheduledFlagMap");
        flags.put(90001L, new AtomicBoolean(true));
        String warning = "道友今天已经很努力了，明天再来吧";
        Group group = bot.getGroup(516625967L);

        task.自动炼丹(bot, group, null, new MessageChain().text(warning), warning, 1, null);
        task.自动炼丹(bot, group, null, new MessageChain().text(warning), warning, 2, null);

        assertFalse(bot.getBotConfig().isStartAuto());
        assertTrue(((CopyOnWriteArrayList<?>) field("alchemyListMap").get(90001L)).isEmpty());
        assertFalse(flags.get(90001L).get());
        assertTrue(otherBot.getBotConfig().isStartAuto());
        assertEquals(1, ((CopyOnWriteArrayList<?>) field("alchemyListMap").get(90002L)).size());
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(messages.capture());
        assertEquals("检测到“今天已经很努力了”，已停止自动炼丹。",
                ((TextMessage) messages.getValue().get(0)).getText());
        verify(otherBot.getGroup(516625968L), never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void frequencyLimitFromAnotherGroupDoesNotStopAlchemy() throws Exception {
        Bot bot = registerBot(90001L, 516625967L);
        enqueueRecipe(90001L, "配方主药冰灵果1");
        Group otherGroup = mock(Group.class);
        when(otherGroup.getGroupId()).thenReturn(60001L);
        String warning = "道友今天已经很努力了";

        task.自动炼丹(bot, otherGroup, null, new MessageChain().text(warning), warning, 1, null);

        assertTrue(bot.getBotConfig().isStartAuto());
        assertEquals(1, ((CopyOnWriteArrayList<?>) field("alchemyListMap").get(90001L)).size());
        verify(bot.getGroup(516625967L), never()).sendMessage(any(MessageChain.class));
        verify(otherGroup, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void frequencyLimitWhileAlchemyIsInactiveDoesNotSendAStopNotice() throws Exception {
        Bot bot = registerBot(90001L, 516625967L);
        bot.getBotConfig().setStartAuto(false);
        Group group = bot.getGroup(516625967L);
        String warning = "道友今天已经很努力了";

        task.自动炼丹(bot, group, null, new MessageChain().text(warning), warning, 1, null);

        assertFalse(bot.getBotConfig().isStartAuto());
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void alreadyScheduledAlchemyCommandDoesNotSendAfterFrequencyLimitStopsTheBot() throws Exception {
        Bot bot = registerBot(90001L, 516625967L);
        BotConfig config = bot.getBotConfig();
        Group group = bot.getGroup(516625967L);
        enqueueRecipe(90001L, "配方主药冰灵果1");
        CountDownLatch pendingSend = new CountDownLatch(1);
        CountDownLatch resumeSend = new CountDownLatch(1);
        AtomicBoolean interceptWorker = new AtomicBoolean(true);
        Thread testThread = Thread.currentThread();
        when(bot.getBotConfig()).thenAnswer(invocation -> {
            if (Thread.currentThread() != testThread && interceptWorker.compareAndSet(true, false)) {
                pendingSend.countDown();
                if (!resumeSend.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待恢复炼丹发送任务超时");
                }
            }
            return config;
        });
        try {
            ReflectionTestUtils.invokeMethod(task, "autoAlchemy", 90001L);
            assertTrue(pendingSend.await(5, TimeUnit.SECONDS), "炼丹任务未进入发送前检查");
            String warning = "道友今天已经很努力了";

            task.自动炼丹(bot, group, null, new MessageChain().text(warning), warning, 1, null);
        } finally {
            resumeSend.countDown();
        }
        ForkJoinPool pool = (ForkJoinPool) ReflectionTestUtils.getField(AutoAlchemyTask.class, "customPool");
        assertTrue(pool.awaitQuiescence(5, TimeUnit.SECONDS));

        assertFalse(config.isStartAuto());
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(messages.capture());
        assertEquals("检测到“今天已经很努力了”，已停止自动炼丹。",
                ((TextMessage) messages.getValue().get(0)).getText());
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
