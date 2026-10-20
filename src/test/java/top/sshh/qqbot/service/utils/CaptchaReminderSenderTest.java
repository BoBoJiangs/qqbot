package top.sshh.qqbot.service.utils;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.api.ApiResult;
import com.zhuangxv.bot.api.support.GetGroups;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CaptchaReminderSenderTest {
    private static final long GROUP_ID = 690933736L;
    private final Bot bot = mock(Bot.class);
    private final MessageChain message = new MessageChain().text("验证码提醒");

    @BeforeEach
    void setUp() {
        when(bot.getBotId()).thenReturn(1001L);
        when(bot.sendGroupMessage(eq(GROUP_ID), any(MessageChain.class))).thenReturn(-100);
    }

    @Test
    void skipsUnjoinedGroupEvenWhenGroupInfoIsCached() {
        when(bot.getGroup(GROUP_ID)).thenReturn(mock(Group.class));
        when(bot.invoke(any(GetGroups.class))).thenReturn(groupList("[]"));

        assertFalse(CaptchaReminderSender.send(bot, GROUP_ID, message));

        verify(bot, never()).sendGroupMessage(anyLong(), any(MessageChain.class));
        verify(bot, never()).getGroup(anyLong());
    }

    @Test
    void sendsToJoinedGroupAndAcceptsNegativeMessageId() {
        when(bot.invoke(any(GetGroups.class))).thenReturn(groupList("[{\"group_id\":690933736}]"));

        assertTrue(CaptchaReminderSender.send(bot, GROUP_ID, message));

        verify(bot).sendGroupMessage(GROUP_ID, message);
    }

    @Test
    void checksMembershipAgainAfterAccountLeavesGroup() {
        ApiResult joined = groupList("[]");
        joined.setData("[{\"group_id\":\"690933736\"}]");
        when(bot.invoke(any(GetGroups.class))).thenReturn(joined, groupList("[]"));

        assertTrue(CaptchaReminderSender.send(bot, GROUP_ID, message));
        assertFalse(CaptchaReminderSender.send(bot, GROUP_ID, message));

        verify(bot, times(1)).sendGroupMessage(GROUP_ID, message);
    }

    @Test
    void membershipQueryFailureDoesNotEscapeOrSend() {
        when(bot.invoke(any(GetGroups.class))).thenThrow(new IllegalStateException("连接断开"));

        assertFalse(CaptchaReminderSender.send(bot, GROUP_ID, message));

        verify(bot, never()).sendGroupMessage(anyLong(), any(MessageChain.class));
    }

    @Test
    void failedOrMissingGroupListDoesNotSend() {
        ApiResult failed = groupList("[{\"group_id\":690933736}]");
        failed.setStatus("failed");
        when(bot.invoke(any(GetGroups.class))).thenReturn(failed, null);

        assertFalse(CaptchaReminderSender.send(bot, GROUP_ID, message));
        assertFalse(CaptchaReminderSender.send(bot, GROUP_ID, message));

        verify(bot, never()).sendGroupMessage(anyLong(), any(MessageChain.class));
    }

    @Test
    void sendFailureDoesNotEscapeToVerificationHandler() {
        when(bot.invoke(any(GetGroups.class))).thenReturn(groupList("[{\"group_id\":690933736}]"));
        when(bot.sendGroupMessage(GROUP_ID, message)).thenThrow(new IllegalStateException("发送失败"));

        assertFalse(CaptchaReminderSender.send(bot, GROUP_ID, message));
    }

    @Test
    void zeroMessageIdIsNotReportedAsSent() {
        when(bot.invoke(any(GetGroups.class))).thenReturn(groupList("[{\"group_id\":690933736}]"));
        when(bot.sendGroupMessage(GROUP_ID, message)).thenReturn(0);

        assertFalse(CaptchaReminderSender.send(bot, GROUP_ID, message));
    }

    private ApiResult groupList(String data) {
        ApiResult result = new ApiResult();
        result.setStatus("ok");
        result.setRetCode(0);
        result.setData(JSON.parseArray(data));
        return result;
    }
}
