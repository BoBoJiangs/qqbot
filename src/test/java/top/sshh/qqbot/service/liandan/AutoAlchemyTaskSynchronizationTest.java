package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.Config;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAlchemyTaskSynchronizationTest {
    private static final long BOT_ID = 987650001002L;
    private final AutoAlchemyTask task = new AutoAlchemyTask();
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);

    @BeforeEach
    void setUp() {
        task.danCalculator = mock(DanCalculator.class);
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotConfig()).thenReturn(new BotConfig());
        when(group.getGroupId()).thenReturn(123456L);
        when(task.danCalculator.getConfig(BOT_ID)).thenReturn(new Config());
    }

    @Test
    void loadFailureStopsCalculationAndReportsFailure() throws Exception {
        doThrow(new IllegalStateException("模拟加载失败")).when(task.danCalculator).loadData(BOT_ID);

        synchronizeRecipes();

        verify(task.danCalculator, never()).calculateAllDans(BOT_ID);
        ArgumentCaptor<MessageChain> response = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(response.capture());
        assertEquals("同步失败：模拟加载失败", textOf(response.getValue()));
        assertFalse(AutoAlchemyTask.matchingLock.isLocked());
    }

    @Test
    void calculationFailureReportsFailureAndAllowsNextSynchronization() throws Exception {
        doThrow(new IllegalStateException("模拟计算失败")).when(task.danCalculator).calculateAllDans(BOT_ID);

        synchronizeRecipes();

        ArgumentCaptor<MessageChain> response = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(response.capture());
        assertEquals("同步失败：模拟计算失败", textOf(response.getValue()));
        assertFalse(AutoAlchemyTask.matchingLock.isLocked());

        doNothing().when(task.danCalculator).calculateAllDans(BOT_ID);
        synchronizeRecipes();

        verify(group, times(2)).sendMessage(response.capture());
        assertEquals("已同步炼丹配方！", textOf(response.getValue()));
    }

    private void synchronizeRecipes() throws Exception {
        String message = "添加成功,开始同步炼丹配方";
        task.enableScheduled(bot, group, null, new MessageChain().text(message), message, 1);
        ForkJoinPool pool = (ForkJoinPool) ReflectionTestUtils.getField(AutoAlchemyTask.class, "customPool");
        assertTrue(pool.awaitQuiescence(5, TimeUnit.SECONDS), "同步任务未在预期时间内结束");
    }

    private String textOf(MessageChain chain) {
        return chain.getMessageByType(TextMessage.class).stream()
                .map(TextMessage::getText)
                .collect(java.util.stream.Collectors.joining());
    }
}
