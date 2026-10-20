package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.api.ApiResult;
import com.zhuangxv.bot.api.support.GetGroups;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.constant.Constant;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.liandan.AutoBuyHerbs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompletionNoticeRoutingTest {
    private static final long BOT_ID = 3821805584L;
    private static final long REMINDER_GROUP_ID = 690933736L;
    private static final long OWN_GROUP_ID = 949396446L;
    private final AutoSellGoods sell = new AutoSellGoods();
    private final FamilyTask family = new FamilyTask();
    private final Bot bot = mock(Bot.class);
    private final Group ownGroup = mock(Group.class);
    private final BotConfig config = new BotConfig();
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        config.setGroupId(OWN_GROUP_ID);
        config.setTaskId(60001L);
        config.setCommand("");
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getBotConfig()).thenReturn(config);
        when(ownGroup.getGroupId()).thenReturn(OWN_GROUP_ID);
        when(ownGroup.getBot()).thenReturn(bot);
        when(bot.getGroup(OWN_GROUP_ID)).thenReturn(ownGroup);
        when(bot.sendGroupMessage(anyLong(), any(MessageChain.class))).thenAnswer(invocation -> {
            assertEquals("", config.getCommand(), "应在发送完成通知前清理旧任务");
            return -1;
        });
        ReflectionTestUtils.setField(sell, "xxGroupId", REMINDER_GROUP_ID);
        ReflectionTestUtils.setField(family, "xxGroupId", REMINDER_GROUP_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"装备炼金完成", "丹药炼金完成", "药材上架完成", "已完成本周妖塔挑战"})
    void unjoinedReminderGroupRoutesCompletionToOwnGroup(String notice) throws Exception {
        membership(false);

        complete(notice);

        assertNotice(OWN_GROUP_ID, notice);
    }

    @ParameterizedTest
    @ValueSource(strings = {"装备炼金完成", "丹药炼金完成", "药材上架完成", "已完成本周妖塔挑战"})
    void joinedReminderGroupKeepsCompletionInReminderGroup(String notice) throws Exception {
        membership(true);

        complete(notice);

        assertNotice(REMINDER_GROUP_ID, notice);
    }

    @ParameterizedTest
    @ValueSource(strings = {"装备炼金完成", "丹药炼金完成", "药材上架完成", "已完成本周妖塔挑战"})
    void sendFailureDoesNotLeaveOldCommandOrEscapeHandler(String notice) {
        membership(false);
        when(bot.sendGroupMessage(anyLong(), any(MessageChain.class))).thenAnswer(invocation -> {
            assertEquals("", config.getCommand());
            throw new IllegalStateException("专属群发送失败");
        });

        assertDoesNotThrow(() -> complete(notice));

        assertEquals("", config.getCommand());
    }

    @Test
    void pillCompletionSentToOwnGroupStillStartsNextPurchase() throws Exception {
        membership(false);
        AutoBuyHerbs purchase = new AutoBuyHerbs();
        String previousTargetDir = Constant.targetDir;
        Constant.targetDir = tempDir.toString();
        try {
            config.setEnableAlchemy(true);
            config.setStartAuto(true);
            when(bot.sendGroupMessage(eq(OWN_GROUP_ID), any(MessageChain.class))).thenAnswer(invocation -> {
                assertEquals("", config.getCommand());
                MessageChain completion = invocation.getArgument(1);
                purchase.enableScheduled(bot, ownGroup, null, completion, text(completion), -1);
                return -1;
            });

            complete("丹药炼金完成");

            ArgumentCaptor<MessageChain> query = ArgumentCaptor.forClass(MessageChain.class);
            verify(ownGroup).sendMessage(query.capture());
            assertEquals("药材背包", text(query.getValue()));
            assertEquals(1, config.getAutoBuyHerbsMode());
            assertFalse(config.isStartAuto());
            assertEquals("", config.getCommand());
        } finally {
            Constant.targetDir = previousTargetDir;
            ((ExecutorService) ReflectionTestUtils.getField(purchase, "customPool")).shutdownNow();
        }
    }

    private void complete(String notice) throws Exception {
        if ("已完成本周妖塔挑战".equals(notice)) {
            config.setCommand("");
            config.setChallengeMode(1);
            family.妖塔状态(bot, ownGroup, null, new MessageChain(), "登上第九层", 1);
            return;
        }
        String mapName;
        if ("装备炼金完成".equals(notice)) {
            config.setCommand("批量炼金装备");
            mapName = "equipPackMap";
        } else if ("丹药炼金完成".equals(notice)) {
            config.setCommand("批量炼金丹药");
            mapName = "pillPackMap";
        } else {
            config.setCommand("批量上架药材");
            mapName = "herbPackMap";
        }
        @SuppressWarnings("unchecked")
        Map<Long, List<ProductPrice>> queue = (Map<Long, List<ProductPrice>>) ReflectionTestUtils.getField(sell, mapName);
        queue.put(BOT_ID, new ArrayList<>(List.of(new ProductPrice())));
        switch (notice) {
            case "装备炼金完成":
                sell.成功炼金装备(bot, ownGroup, null, new MessageChain(), "炼金成功", 1);
                break;
            case "丹药炼金完成":
                sell.成功炼金丹药(bot, ownGroup, null, new MessageChain(), "炼金成功", 1);
                break;
            default:
                sell.成功上架药材(bot, ownGroup, null, new MessageChain(), "物品成功上架坊市", 1);
        }
        assertTrue(queue.get(BOT_ID).isEmpty());
    }

    private void membership(boolean joined) {
        ApiResult result = new ApiResult();
        result.setStatus("ok");
        result.setRetCode(0);
        result.setData(JSON.parseArray(joined ? "[{\"group_id\":690933736},{\"group_id\":949396446}]"
                : "[{\"group_id\":949396446}]"));
        when(bot.invoke(any(GetGroups.class))).thenReturn(result);
    }

    private void assertNotice(long targetGroupId, String expectedText) {
        ArgumentCaptor<MessageChain> sent = ArgumentCaptor.forClass(MessageChain.class);
        verify(bot).sendGroupMessage(eq(targetGroupId), sent.capture());
        assertEquals(expectedText, text(sent.getValue()));
        verify(bot, times(1)).sendGroupMessage(anyLong(), any(MessageChain.class));
    }

    private static String text(MessageChain message) {
        return message.getMessageByType(TextMessage.class).stream().map(TextMessage::getText)
                .collect(Collectors.joining());
    }
}
