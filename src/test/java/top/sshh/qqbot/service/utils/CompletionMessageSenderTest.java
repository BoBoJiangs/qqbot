package top.sshh.qqbot.service.utils;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.api.ApiResult;
import com.zhuangxv.bot.api.support.GetGroups;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompletionMessageSenderTest {
    private static final long REMINDER_GROUP_ID = 690933736L;
    private static final long OWN_GROUP_ID = 949396446L;
    private final Bot bot = mock(Bot.class);
    private final BotConfig config = new BotConfig();

    @BeforeEach
    void setUp() {
        config.setGroupId(OWN_GROUP_ID);
        config.setTaskId(60001L);
        when(bot.getBotConfig()).thenReturn(config);
        when(bot.getBotId()).thenReturn(3821805584L);
        when(bot.sendGroupMessage(anyLong(), any(MessageChain.class))).thenReturn(-100);
    }

    @Test
    void refreshesActualMembershipAndIgnoresCachedGroupInfo() {
        when(bot.getGroup(REMINDER_GROUP_ID)).thenReturn(mock(Group.class));
        when(bot.invoke(any(GetGroups.class))).thenReturn(groups("[{\"group_id\":690933736}]"), groups("[]"));

        assertTrue(CompletionMessageSender.send(bot, REMINDER_GROUP_ID, "丹药炼金完成"));
        assertTrue(CompletionMessageSender.send(bot, REMINDER_GROUP_ID, "丹药炼金完成"));

        verify(bot).sendGroupMessage(eq(REMINDER_GROUP_ID), any(MessageChain.class));
        verify(bot).sendGroupMessage(eq(OWN_GROUP_ID), any(MessageChain.class));
        verify(bot, never()).getGroup(anyLong());
    }

    @Test
    void fallbackFollowsCurrentBotConfigurationRatherThanTaskGroupOrFixedGroup() {
        when(bot.invoke(any(GetGroups.class))).thenReturn(groups("[]"));
        config.setGroupId(516625967L);

        assertTrue(CompletionMessageSender.send(bot, REMINDER_GROUP_ID, "装备炼金完成"));

        verify(bot).sendGroupMessage(eq(516625967L), any(MessageChain.class));
        verify(bot, never()).sendGroupMessage(eq(OWN_GROUP_ID), any(MessageChain.class));
        verify(bot, never()).sendGroupMessage(eq(config.getTaskId()), any(MessageChain.class));
    }

    @Test
    void disabledReminderGroupUsesOwnGroupWithoutQuery() {
        assertTrue(CompletionMessageSender.send(bot, 0L, "药材上架完成"));

        verify(bot).sendGroupMessage(eq(OWN_GROUP_ID), any(MessageChain.class));
        verify(bot, never()).invoke(any(GetGroups.class));
    }

    @Test
    void unavailableOrFailedMembershipQueryFallsBackToOwnGroup() {
        ApiResult failed = groups("[{\"group_id\":690933736}]");
        failed.setStatus("failed");
        when(bot.invoke(any(GetGroups.class))).thenReturn(null, failed)
                .thenThrow(new IllegalStateException("连接断开"));

        for (int i = 0; i < 3; i++) {
            assertTrue(CompletionMessageSender.send(bot, REMINDER_GROUP_ID, "丹药炼金完成"));
        }

        verify(bot, times(3)).sendGroupMessage(eq(OWN_GROUP_ID), any(MessageChain.class));
        verify(bot, never()).sendGroupMessage(eq(REMINDER_GROUP_ID), any(MessageChain.class));
    }

    @Test
    void stringGroupListIsSupported() {
        ApiResult result = groups("[]");
        result.setData("[{\"group_id\":\"690933736\"}]");
        when(bot.invoke(any(GetGroups.class))).thenReturn(result);

        assertTrue(CompletionMessageSender.send(bot, REMINDER_GROUP_ID, "已完成本周妖塔挑战"));

        verify(bot).sendGroupMessage(eq(REMINDER_GROUP_ID), any(MessageChain.class));
    }

    @Test
    void failedSendIsContainedAndDoesNotDuplicateTheCompletionSignal() {
        when(bot.invoke(any(GetGroups.class))).thenReturn(groups("[{\"group_id\":690933736}]"));
        when(bot.sendGroupMessage(eq(REMINDER_GROUP_ID), any(MessageChain.class)))
                .thenThrow(new IllegalStateException("发送失败"));

        assertFalse(CompletionMessageSender.send(bot, REMINDER_GROUP_ID, "丹药炼金完成"));

        verify(bot, times(1)).sendGroupMessage(anyLong(), any(MessageChain.class));
    }

    @Test
    void zeroMessageIdIsReportedAsFailed() {
        when(bot.sendGroupMessage(anyLong(), any(MessageChain.class))).thenReturn(0);

        assertFalse(CompletionMessageSender.send(bot, 0L, "丹药炼金完成"));
    }

    private ApiResult groups(String json) {
        ApiResult result = new ApiResult();
        result.setStatus("ok");
        result.setRetCode(0);
        result.setData(JSON.parseArray(json));
        return result;
    }
}
