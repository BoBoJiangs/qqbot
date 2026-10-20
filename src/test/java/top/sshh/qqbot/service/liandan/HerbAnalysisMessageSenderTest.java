package top.sshh.qqbot.service.liandan;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.ForwardNodeMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class HerbAnalysisMessageSenderTest {
    private static final long BOT_ID = 1001L;
    private static final long GROUP_ID = 971297429L;
    private final Bot bot = mock(Bot.class);
    private final Group group = mock(Group.class);

    @BeforeEach
    void setUp() {
        when(bot.getBotId()).thenReturn(BOT_ID);
        when(bot.getGroup(GROUP_ID)).thenReturn(group);
        when(group.sendMessage(any(MessageChain.class))).thenReturn(-100);
        when(group.sendGroupForwardMessage(anyList())).thenReturn(-200);
    }

    @Test
    void sendsSummaryThenPlainTextForwardNodesThenPlainTextCommands() {
        HerbAnalysisMessageFormatter.Report report = report(1);
        HerbAnalysisMessageSender.send(bot, GROUP_ID, report);

        InOrder order = inOrder(group);
        ArgumentCaptor<MessageChain> plain = ArgumentCaptor.forClass(MessageChain.class);
        ArgumentCaptor<List<ForwardNodeMessage>> forward = nodeCaptor();
        order.verify(group).sendMessage(plain.capture());
        order.verify(group).sendGroupForwardMessage(forward.capture());
        order.verify(group).sendMessage(plain.capture());
        order.verifyNoMoreInteractions();
        assertEquals(report.getSummaries().get(0), text(plain.getAllValues().get(0)));
        assertEquals(report.getCommands().get(0), text(plain.getAllValues().get(1)));
        assertEquals(2, forward.getValue().size());
        assertTrue(nodeText(forward.getValue().get(0)).startsWith("药材积压分析详情"));
        assertEquals(report.getDetails().get(0), nodeText(forward.getValue().get(1)));
        for (ForwardNodeMessage node : forward.getValue()) {
            assertEquals(String.valueOf(BOT_ID), node.getUserId());
            assertEquals("药材分析助手", node.getNickname());
        }
    }

    @Test
    void shortNoticeDoesNotCreateForwardOrCommandMessages() {
        HerbAnalysisMessageSender.send(bot, GROUP_ID, HerbAnalysisMessageFormatter.format("无需调整价格"));
        ArgumentCaptor<MessageChain> plain = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(plain.capture());
        assertEquals("无需调整价格", text(plain.getValue()));
        verify(group, never()).sendGroupForwardMessage(anyList());
    }

    @Test
    void forwardExceptionFallsBackToPlainTextAndStillSendsCommands() {
        when(group.sendGroupForwardMessage(anyList())).thenThrow(new IllegalStateException("转发失败"));
        assertFallbackAndCommands();
    }

    @Test
    void zeroForwardMessageIdFallsBackButNegativeIdIsAccepted() {
        when(group.sendGroupForwardMessage(anyList())).thenReturn(0);
        assertFallbackAndCommands();
    }

    @Test
    void failedFallbackStillDoesNotSuppressCommands() {
        when(group.sendGroupForwardMessage(anyList())).thenReturn(0);
        when(group.sendMessage(any(MessageChain.class))).thenAnswer(invocation ->
                text(invocation.getArgument(0)).startsWith("药材分析详情（") ? 0 : -100);
        HerbAnalysisMessageFormatter.Report report = report(1);
        HerbAnalysisMessageSender.send(bot, GROUP_ID, report);
        ArgumentCaptor<MessageChain> plain = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, times(3)).sendMessage(plain.capture());
        assertEquals(report.getCommands().get(0), text(plain.getAllValues().get(2)));
    }

    @Test
    void largeDetailListIsSentInBoundedForwardBatchesWithoutLosingHerbs() {
        HerbAnalysisMessageFormatter.Report report = report(40);
        HerbAnalysisMessageSender.send(bot, GROUP_ID, report);
        ArgumentCaptor<List<ForwardNodeMessage>> forward = nodeCaptor();
        verify(group, times(3)).sendGroupForwardMessage(forward.capture());
        List<String> details = forward.getAllValues().stream().flatMap(nodes -> {
            assertTrue(nodes.size() <= 20);
            assertTrue(nodeText(nodes.get(0)).startsWith("药材积压分析详情"));
            return nodes.stream().skip(1).map(this::nodeText);
        }).collect(Collectors.toList());
        assertEquals(report.getDetails(), details);
        ArgumentCaptor<MessageChain> plain = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, times(report.getSummaries().size() + report.getCommands().size())).sendMessage(plain.capture());
        assertEquals(report.getCommands().get(0), text(plain.getAllValues().get(plain.getAllValues().size() - 1)));
    }

    @Test
    void zeroPlainMessageIdIsNotSilentlyReportedAsSuccessful() {
        when(group.sendMessage(any(MessageChain.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> HerbAnalysisMessageSender.send(
                bot, GROUP_ID, HerbAnalysisMessageFormatter.format("无需调整价格")));
    }

    @Test
    void missingTargetGroupDoesNotSendToAnyOtherGroup() {
        assertThrows(IllegalStateException.class, () -> HerbAnalysisMessageSender.send(
                bot, GROUP_ID + 1, report(1)));
        verifyNoInteractions(group);
    }

    private void assertFallbackAndCommands() {
        HerbAnalysisMessageFormatter.Report report = report(1);
        HerbAnalysisMessageSender.send(bot, GROUP_ID, report);
        ArgumentCaptor<MessageChain> plain = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, times(3)).sendMessage(plain.capture());
        String fallback = text(plain.getAllValues().get(1));
        assertTrue(fallback.startsWith("药材分析详情（合并转发失败，普通文本补发）\n"));
        assertTrue(fallback.endsWith(report.getDetails().get(0)));
        assertEquals(report.getCommands().get(0), text(plain.getAllValues().get(2)));
    }

    private HerbAnalysisMessageFormatter.Report report(int herbCount) {
        String details = IntStream.rangeClosed(1, herbCount).mapToObj(i ->
                i + ". 测试药" + i + " 76/30\n判断：搭配药库存不足\n关联配方：消冰宝丸\n原因：地龙干缺46；收益35万\n建议：补齐搭配药")
                .collect(Collectors.joining("\n\n"));
        return HerbAnalysisMessageFormatter.format("背包药材分析完成\n检测到超限药材：" + herbCount + "种\n\n"
                + details + "\n\n可复制调整命令：\n采购药材地龙干 430\n重复采购药材地龙干 430 30");
    }

    private String text(MessageChain chain) {
        assertEquals(1, chain.size());
        assertEquals(TextMessage.class, chain.get(0).getClass());
        JSONObject segment = JSON.parseArray(chain.toMessageString()).getJSONObject(0);
        assertEquals("text", segment.getString("type"));
        return segment.getJSONObject("data").getString("text");
    }

    private String nodeText(ForwardNodeMessage node) {
        assertEquals(1, node.getContent().size());
        JSONObject segment = node.getContent().getJSONObject(0);
        assertEquals("text", segment.getString("type"));
        return segment.getJSONObject("data").getString("text");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<List<ForwardNodeMessage>> nodeCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
