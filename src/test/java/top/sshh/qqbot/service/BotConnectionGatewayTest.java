package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zhuangxv.bot.api.ApiResult;
import com.zhuangxv.bot.api.support.GetFriends;
import com.zhuangxv.bot.api.support.GetGroups;
import com.zhuangxv.bot.api.support.SendGroupMsg;
import com.zhuangxv.bot.api.support.SendPrivateMsg;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.network.ws.WsBotClient;
import io.netty.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.service.BotConnectionGateway.GetStatus;
import top.sshh.qqbot.service.BotConnectionGateway.Status;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotConnectionGatewayTest {
    private static final long MASTER = 819463350L;
    private static final long GROUP = 690933736L;
    private final BotConnectionGateway gateway = new BotConnectionGateway();

    @BeforeEach void configureReminderGroup() { ReflectionTestUtils.setField(gateway, "xxGroupId", GROUP); }
    @AfterEach void close() { gateway.close(); }

    @Test
    void distinguishesRealQqStatusFromSocketPresenceAndUnknownResults() {
        Bot bot = bot(11L, MASTER);
        when(bot.invoke(any(GetStatus.class))).thenReturn(
                result("{\"online\":true,\"good\":true}"),
                result("{\"online\":false,\"good\":false}"),
                result("{\"online\":null,\"good\":true}"),
                result("{\"online\":true,\"good\":false}"), null);
        assertEquals(Status.ONLINE, gateway.probe(bot).status);
        assertEquals(Status.OFFLINE, gateway.probe(bot).status);
        assertEquals(Status.UNKNOWN, gateway.probe(bot).status);
        assertEquals(Status.UNKNOWN, gateway.probe(bot).status);
        assertEquals(Status.UNKNOWN, gateway.probe(bot).status);
    }

    @Test
    void disconnectedSocketIsNotMistakenForAnOnlineAccount() {
        Bot bot = bot(11L, MASTER);
        WsBotClient client = mock(WsBotClient.class);
        Channel channel = mock(Channel.class);
        when(bot.getBotClient()).thenReturn(client);
        when(client.getChannel()).thenReturn(channel);
        when(channel.isActive()).thenReturn(false);

        assertEquals(Status.DISCONNECTED, gateway.probe(bot).status);
        verify(bot, never()).invoke(any());
    }

    @Test
    void checksActualFriendsAndFallsBackAfterNonFriendOrFailedSend() throws Exception {
        Bot stranger = bot(11L, MASTER);
        Bot failed = bot(22L, MASTER);
        Bot success = bot(33L, MASTER);
        when(stranger.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        when(failed.invoke(any(SendPrivateMsg.class))).thenReturn(result("{\"message_id\":0}"));

        assertTrue(gateway.sendToMaster(MASTER, "告警", Arrays.asList(success, stranger, failed), null));
        verify(stranger).invoke(any(GetFriends.class));
        verify(stranger, never()).isFriend(anyLong());
        verify(stranger, never()).invoke(any(SendPrivateMsg.class));
        verify(failed).invoke(any(SendPrivateMsg.class));
        verify(success).invoke(any(SendPrivateMsg.class));
        verify(success, never()).invoke(any(SendGroupMsg.class));
        verify(stranger, never()).invoke(any(SendGroupMsg.class));
    }

    @Test
    void offlineOrExcludedBotsCannotSendAndSuccessfulSenderStopsFurtherAttempts() {
        Bot excluded = bot(11L, MASTER);
        Bot offline = bot(22L, MASTER);
        Bot success = bot(33L, MASTER);
        Bot unused = bot(44L, MASTER);
        when(offline.invoke(any(GetStatus.class))).thenReturn(result("{\"online\":false}"));

        assertTrue(gateway.sendToMaster(MASTER, "告警", Arrays.asList(excluded, offline, success, unused), 11L));
        verify(excluded, never()).invoke(any());
        verify(offline, never()).invoke(any(GetFriends.class));
        verify(success).invoke(any(SendPrivateMsg.class));
        verify(unused, never()).invoke(any());
    }

    @Test
    void failedApiStatusIsRejectedEvenWhenResponseContainsMessageId() {
        Bot bot = bot(11L, MASTER);
        ApiResult failed = result("{\"message_id\":123}");
        failed.setStatus("failed");
        when(bot.invoke(any(SendPrivateMsg.class))).thenReturn(failed);

        assertFalse(gateway.sendToMaster(MASTER, "告警", Collections.singleton(bot), null));
        verify(bot, never()).invoke(any(GetGroups.class));
    }

    @Test
    void failedFriendLookupNeverFallsBackToCachedFriendship() {
        Bot bot = bot(11L, MASTER);
        ApiResult failed = result("[{\"user_id\":819463350}]");
        failed.setRetCode(1);
        when(bot.invoke(any(GetFriends.class))).thenReturn(failed);

        assertFalse(gateway.sendToMaster(MASTER, "告警", Collections.singleton(bot), null));
        verify(bot, never()).invoke(any(SendPrivateMsg.class));
        verify(bot, never()).invoke(any(GetGroups.class));
    }

    @Test
    void allNonFriendsFallBackToConfiguredGroupWithActualAtMessageAndStopAfterSuccess() {
        long configuredGroup = GROUP + 1L;
        long configuredMaster = MASTER + 1L;
        ReflectionTestUtils.setField(gateway, "xxGroupId", configuredGroup);
        Bot first = bot(11L, configuredMaster);
        Bot second = bot(22L, configuredMaster);
        when(first.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        when(second.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        ApiResult groups = result("[]");
        groups.setData("[{\"group_id\":\"" + configuredGroup + "\"}]");
        when(first.invoke(any(GetGroups.class))).thenReturn(groups);
        AtomicReference<SendGroupMsg> sent = new AtomicReference<>();
        when(first.invoke(any(SendGroupMsg.class))).thenAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return result("{\"message_id\":-567}");
        });

        assertTrue(gateway.sendToMaster(configuredMaster, "重启后甲账号未连接", Arrays.asList(first, second), null));
        verify(first).invoke(any(SendGroupMsg.class));
        JSONObject params = JSON.parseObject(JSON.toJSONString(sent.get().getParams()));
        assertEquals(configuredGroup, params.getLongValue("group_id"));
        JSONObject mention = params.getJSONArray("message").getJSONObject(0);
        assertEquals("at", mention.getString("type"));
        assertEquals(Long.toString(configuredMaster), mention.getJSONObject("data").getString("qq"));
        assertEquals("\n重启后甲账号未连接", params.getJSONArray("message").getJSONObject(1)
                .getJSONObject("data").getString("text"));
        verify(first, never()).invoke(any(SendPrivateMsg.class));
        verify(second, never()).invoke(any(SendPrivateMsg.class));
        verify(second, never()).invoke(any(GetGroups.class));
        verify(second, never()).invoke(any(SendGroupMsg.class));
    }

    @Test
    void skipsBotsNotActuallyInReminderGroupAndUsesAnotherOnlineBot() {
        Bot notJoined = bot(11L, MASTER);
        Bot joined = bot(22L, MASTER);
        when(notJoined.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        when(joined.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        when(notJoined.invoke(any(GetGroups.class))).thenReturn(result("[]"));

        assertTrue(gateway.sendToMaster(MASTER, "告警", Arrays.asList(notJoined, joined), null));
        verify(notJoined, never()).getGroup(anyLong());
        verify(notJoined, never()).invoke(any(SendGroupMsg.class));
        verify(joined).invoke(any(SendGroupMsg.class));
    }

    @Test
    void groupFailuresSwitchSendersAndRequireSuccessfulMessageId() {
        Bot failed = bot(11L, MASTER);
        Bot retry = bot(22L, MASTER);
        when(failed.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        when(retry.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        ApiResult rejected = result("{\"message_id\":123}");
        rejected.setStatus("failed");
        when(failed.invoke(any(SendGroupMsg.class))).thenReturn(rejected);
        when(retry.invoke(any(SendGroupMsg.class))).thenReturn(
                result("{\"message_id\":0}"), result("{\"message_id\":-456}"));

        assertFalse(gateway.sendToMaster(MASTER, "告警", Arrays.asList(failed, retry), null));
        assertTrue(gateway.sendToMaster(MASTER, "告警", Arrays.asList(failed, retry), null));
        verify(failed, times(2)).invoke(any(SendGroupMsg.class));
        verify(retry, times(2)).invoke(any(SendGroupMsg.class));
    }

    @Test
    void unknownFriendshipDoesNotMeanAllBotsAreNonFriends() {
        Bot unknown = bot(11L, MASTER);
        Bot nonFriend = bot(22L, MASTER);
        when(unknown.invoke(any(GetFriends.class))).thenReturn(null);
        when(nonFriend.invoke(any(GetFriends.class))).thenReturn(result("[]"));

        assertFalse(gateway.sendToMaster(MASTER, "告警", Arrays.asList(unknown, nonFriend), null));
        verify(unknown, never()).invoke(any(GetGroups.class));
        verify(nonFriend, never()).invoke(any(GetGroups.class));
    }

    @Test
    void missingReminderGroupConfigurationKeepsNoticePending() {
        Bot nonFriend = bot(11L, MASTER);
        when(nonFriend.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        ReflectionTestUtils.setField(gateway, "xxGroupId", 0L);

        assertFalse(gateway.sendToMaster(MASTER, "告警", Collections.singleton(nonFriend), null));
        verify(nonFriend, never()).invoke(any(GetGroups.class));
        verify(nonFriend, never()).invoke(any(SendGroupMsg.class));
    }

    @Test
    void failedMembershipQueryOrNoJoinedBotKeepsNoticePending() {
        Bot absent = bot(11L, MASTER);
        Bot unknown = bot(22L, MASTER);
        when(absent.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        when(unknown.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        when(absent.invoke(any(GetGroups.class))).thenReturn(result("[]"));
        ApiResult failed = result("[{\"group_id\":690933736}]");
        failed.setRetCode(1);
        when(unknown.invoke(any(GetGroups.class))).thenReturn(failed);

        assertFalse(gateway.sendToMaster(MASTER, "告警", Arrays.asList(absent, unknown), null));
        verify(absent, never()).invoke(any(SendGroupMsg.class));
        verify(unknown, never()).invoke(any(SendGroupMsg.class));
    }

    @Test
    void groupFallbackRechecksSenderOnlineState() {
        Bot lost = bot(11L, MASTER);
        Bot available = bot(22L, MASTER);
        when(lost.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        when(available.invoke(any(GetFriends.class))).thenReturn(result("[]"));
        when(lost.invoke(any(GetStatus.class))).thenReturn(
                result("{\"online\":true}"), result("{\"online\":false}"));

        assertTrue(gateway.sendToMaster(MASTER, "告警", Arrays.asList(lost, available), null));
        verify(lost, never()).invoke(any(GetGroups.class));
        verify(lost, never()).invoke(any(SendGroupMsg.class));
        verify(available).invoke(any(SendGroupMsg.class));
    }

    @Test
    void cancelledMembershipQueryCannotSendLate() {
        BotConnectionGateway bounded = new BotConnectionGateway(200L);
        ReflectionTestUtils.setField(bounded, "xxGroupId", GROUP);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Bot slow = bot(11L, MASTER);
            Bot fallback = bot(22L, MASTER);
            when(slow.invoke(any(GetFriends.class))).thenReturn(result("[]"));
            when(fallback.invoke(any(GetFriends.class))).thenReturn(result("[]"));
            when(slow.invoke(any(GetGroups.class))).thenAnswer(invocation -> {
                boolean waiting = true;
                while (waiting) {
                    try {
                        release.await();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                        // 模拟第三方 API 在取消后仍返回查询结果。
                    }
                }
                return result("[{\"group_id\":690933736}]");
            });

            assertTrue(bounded.sendToMaster(MASTER, "告警", Arrays.asList(slow, fallback), null));
            release.countDown();
            verify(slow, after(200).never()).invoke(any(SendGroupMsg.class));
            verify(fallback).invoke(any(SendGroupMsg.class));
        } finally {
            release.countDown();
            bounded.close();
        }
    }

    @Test
    void acceptsStringPayloadsAndPrefersAccountConfiguredForTheRecipient() {
        Bot otherMaster = bot(1L, 12345L);
        Bot preferred = bot(99L, MASTER);
        ApiResult status = result("{}");
        status.setData("{\"online\":true,\"good\":true}");
        ApiResult friends = result("[]");
        friends.setData("[{\"user_id\":\"819463350\"}]");
        when(preferred.invoke(any(GetStatus.class))).thenReturn(status);
        when(preferred.invoke(any(GetFriends.class))).thenReturn(friends);

        assertTrue(gateway.sendToMaster(MASTER, "告警", Arrays.asList(otherMaster, preferred), null));
        verify(otherMaster, never()).invoke(any());
        verify(preferred).invoke(any(SendPrivateMsg.class));
    }

    @Test
    void slowStatusQueryHasABoundedWait() {
        BotConnectionGateway bounded = new BotConnectionGateway(100L);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Bot slow = bot(11L, MASTER);
            when(slow.invoke(any(GetStatus.class))).thenAnswer(invocation -> {
                release.await();
                return result("{\"online\":true}");
            });
            Map<Long, BotConnectionGateway.Probe> states = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> bounded.probeAll(Collections.singletonMap(11L, slow)));
            assertEquals(Status.UNKNOWN, states.get(11L).status);
        } finally {
            release.countDown();
            bounded.close();
        }
    }

    @Test
    void cancelledFriendLookupCannotSendLateEvenIfProtocolSwallowsInterruption() {
        BotConnectionGateway bounded = new BotConnectionGateway(200L);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Bot slow = bot(11L, MASTER);
            Bot fallback = bot(22L, MASTER);
            when(slow.invoke(any(GetFriends.class))).thenAnswer(invocation -> {
                boolean waiting = true;
                while (waiting) {
                    try {
                        release.await();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                        // 模拟第三方网络层吞掉中断，稍后仍返回 API 结果。
                    }
                }
                return result("[{\"user_id\":819463350}]");
            });

            assertTrue(bounded.sendToMaster(MASTER, "告警", Arrays.asList(slow, fallback), null));
            release.countDown();
            verify(slow, after(200).never()).invoke(any(SendPrivateMsg.class));
            verify(fallback).invoke(any(SendPrivateMsg.class));
        } finally {
            release.countDown();
            bounded.close();
        }
    }

    @Test
    void detectsMultipleBotsIncludingFailedStatusQueries() {
        Bot online = bot(11L, MASTER);
        Bot unknown = bot(22L, MASTER);
        when(unknown.invoke(any(GetStatus.class))).thenThrow(new IllegalStateException("连接异常"));
        Map<Long, BotConnectionGateway.Probe> states = gateway.probeAll(Map.of(11L, online, 22L, unknown));
        assertEquals(Status.ONLINE, states.get(11L).status);
        assertEquals(Status.UNKNOWN, states.get(22L).status);
    }

    private Bot bot(long id, long master) {
        Bot bot = mock(Bot.class);
        BotConfig config = new BotConfig();
        config.setMasterQQ(master);
        when(bot.getBotId()).thenReturn(id);
        when(bot.getBotConfig()).thenReturn(config);
        when(bot.invoke(any(GetStatus.class))).thenReturn(result("{\"online\":true,\"good\":true}"));
        when(bot.invoke(any(GetFriends.class))).thenReturn(result("[{\"user_id\":819463350}]"));
        when(bot.invoke(any(SendPrivateMsg.class))).thenReturn(result("{\"message_id\":-123}"));
        when(bot.invoke(any(GetGroups.class))).thenReturn(result("[{\"group_id\":690933736}]"));
        when(bot.invoke(any(SendGroupMsg.class))).thenReturn(result("{\"message_id\":-567}"));
        return bot;
    }

    private ApiResult result(String json) {
        ApiResult result = new ApiResult();
        result.setStatus("ok");
        result.setRetCode(0);
        result.setData(JSON.parse(json));
        return result;
    }
}
