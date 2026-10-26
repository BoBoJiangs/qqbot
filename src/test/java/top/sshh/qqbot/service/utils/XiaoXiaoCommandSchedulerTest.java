package top.sshh.qqbot.service.utils;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class XiaoXiaoCommandSchedulerTest {
    @Test
    void queuedCommandChecksCancellationBeforeSending() throws Exception {
        Bot bot = bot(77101L);
        Group group = bot.getGroup(77101L);
        CountDownLatch checked = new CountDownLatch(1);
        XiaoXiaoCommandScheduler.sendTracked(bot, 77101L, exit(), () -> {
            checked.countDown();
            return false;
        }, sent -> fail("已取消的排队操作不应回报发送结果"));

        assertTrue(checked.await(3, TimeUnit.SECONDS));
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void sendFailureIsReportedToTheRetryManager() throws Exception {
        Bot bot = bot(77102L);
        Group group = bot.getGroup(77102L);
        when(group.sendMessage(any(MessageChain.class))).thenThrow(new IllegalStateException("offline"));
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch completed = new CountDownLatch(1);
        XiaoXiaoCommandScheduler.sendTracked(bot, 77102L, exit(), () -> true, sent -> {
            result.set(sent);
            completed.countDown();
        });

        assertTrue(completed.await(3, TimeUnit.SECONDS));
        assertFalse(result.get());
    }

    @Test
    void missingGroupIsReportedWithoutStartingAReplyTimeout() {
        Bot bot = bot(77103L);
        when(bot.getGroup(77103L)).thenReturn(null);
        AtomicBoolean checked = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        XiaoXiaoCommandScheduler.sendTracked(bot, 77103L, exit(), () -> {
            checked.set(true);
            return true;
        }, sent -> failed.set(!sent));

        assertFalse(checked.get());
        assertTrue(failed.get());
    }

    private Bot bot(long groupId) {
        Bot bot = mock(Bot.class);
        BotConfig config = new BotConfig();
        Group group = mock(Group.class);
        config.setGroupId(groupId);
        when(bot.getBotId()).thenReturn(groupId);
        when(bot.getBotConfig()).thenReturn(config);
        when(bot.getGroup(groupId)).thenReturn(group);
        return bot;
    }

    private MessageChain exit() {
        return new MessageChain().at("3889001741").text("宗门出关");
    }
}
