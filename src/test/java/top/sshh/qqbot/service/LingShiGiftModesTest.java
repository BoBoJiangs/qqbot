package top.sshh.qqbot.service;

import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.service.utils.Utils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LingShiGiftModesTest {
    private static final long GROUP = 6001L;
    private static final long OWNER = 3001L;
    private static final long OTHER = 3002L;
    private static final long CONTROLLER = 9001L;
    private static final String CODE_A = "12345678-abcd-1234-abcd-123456789012";
    private static final String CODE_B = "87654321-dcba-4321-dcba-210987654321";
    @TempDir
    Path directory;
    private LingShiGiftStore store;
    private LingShiGiftService gifts;
    private AtomicLong now;
    private TestService service;
    private Account a;
    private Account b;

    @BeforeEach
    void setUp() {
        store = new LingShiGiftStore(directory.resolve("gifts.json"));
        now = new AtomicLong(1000000L);
        gifts = new LingShiGiftService(store, now::get);
        service = spy(new TestService());
        doNothing().when(service).saveBotConfig(any(Bot.class));
        ReflectionTestUtils.setField(service, "lingShiGiftService", gifts);
        a = new Account(1001L);
        b = new Account(1002L);
    }

    @Test
    void savedLegacyAmountStillGivesWithoutNewConfigurationOrRequesterBinding() {
        a.config.setLingShiNum(100);
        broadcast(code(OTHER, CODE_A, null), 1);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sent(a));
        assertTrue(sent(b).isEmpty());
        broadcast(receipt(a.id, 800000, 200000).reply(a.sentId), 2);
        assertEquals(1000000L, a.config.getLingShiTotal());
        assertEquals(100, a.config.getLingShiNum());
    }

    @Test
    void legacyCommandAndZeroDoNotChangeNewSettings() throws Exception {
        store.configure(GROUP, OWNER, a.id, 99L);
        legacyCommand(a, OWNER, 100);
        assertEquals(100, a.config.getLingShiNum());
        assertEquals(99L, store.totalFor(a.id, GROUP));
        legacyCommand(a, OWNER, 0);
        broadcast(code(OWNER, CODE_A, "5400万"), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 54000000"), sent(a));
        assertEquals(0, a.config.getLingShiNum());
        assertEquals(Map.of(OWNER, a.id), store.recipientsFor(GROUP));
    }

    @Test
    void multilineConfigurationPersistsAndLeavesLegacyConfigurationUntouched() throws Exception {
        a.config.setLingShiNum(100);
        a.config.setLingShiTotal(123L);
        selfCommand(a, "设置赠送灵石\nQQ：3001\n群号：6002\n灵石：0");
        LingShiGiftStore restored = new LingShiGiftStore(directory.resolve("gifts.json"));
        assertEquals(Map.of(OWNER, a.id), restored.recipientsFor(6002));
        assertEquals(0L, restored.totalFor(a.id, 6002));
        assertEquals(GROUP, a.config.getLingShiQQ());
        assertEquals(100, a.config.getLingShiNum());
        assertEquals(123L, a.config.getLingShiTotal());
    }

    @Test
    void omittedTotalStillSendsMaximumButDoesNotCountReceipts() throws Exception {
        selfCommand(a, "设置赠送灵石\r\nQQ:3001\r\n群号:6001");
        broadcast(code(OWNER, CODE_A, "5400万"), 1);
        broadcast(receipt(a.id, 69600000, 17400000), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 54000000"), sent(a));
        assertNull(store.totalFor(a.id, GROUP));
    }

    @Test
    void secondBotOmittingTotalCannotDisableFirstBotsStatisticsEvenForTheSameDedicatedQq() throws Exception {
        selfCommand(a, "设置赠送灵石\nQQ：3001\n群号：6001\n灵石：100");
        selfCommand(b, "设置赠送灵石\nQQ：3001\n群号：6001");
        assertEquals(Map.of(OWNER, b.id), store.recipientsFor(GROUP));
        assertEquals(1000000L, store.totalFor(a.id, GROUP));
        assertNull(store.totalFor(b.id, GROUP));
        MessageChain result = receipt(9999L, 69600000, 17400000);
        deliver(b, result, 2);
        deliver(a, result, 2);
        assertEquals(88000000L, store.totalFor(a.id, GROUP));
        assertNull(store.totalFor(b.id, GROUP));
        selfCommand(a, "查询灵石赠送");
        selfCommand(a, "查看赠送灵石设置");
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        verify(a.group, atLeastOnce()).sendMessage(messages.capture());
        List<String> replies = messages.getAllValues().stream().map(Utils::getMessageText).collect(Collectors.toList());
        assertTrue(replies.contains("本号在本群赠送灵石累计（含手续费）：8800.00万"));
        assertTrue(replies.stream().anyMatch(text -> text.contains("QQ：无（当前仅累计统计）")
                && text.contains("当前累计（含手续费）：8800.00万")));
        setUp();
        assertEquals(88000000L, store.totalFor(a.id, GROUP));
        assertNull(store.totalFor(b.id, GROUP));
    }

    @Test
    void settingResettingAndDisablingAnotherBotsCounterDoesNotChangeFirstBotsTotal() throws Exception {
        selfCommand(a, "设置赠送灵石\nQQ：3001\n群号：6001\n灵石：100");
        selfCommand(b, "设置赠送灵石\nQQ：3002\n群号：6001\n灵石：200");
        MessageChain result = receipt(9999L, 69600000, 17400000);
        broadcast(result, 2);
        assertEquals(88000000L, store.totalFor(a.id, GROUP));
        assertEquals(89000000L, store.totalFor(b.id, GROUP));
        selfCommand(b, "设置赠送灵石\nQQ：3002\n群号：6001\n灵石：300");
        assertEquals(88000000L, store.totalFor(a.id, GROUP));
        assertEquals(3000000L, store.totalFor(b.id, GROUP));
        selfCommand(b, "设置赠送灵石\nQQ：3002\n群号：6001");
        broadcast(result, 3);
        assertEquals(175000000L, store.totalFor(a.id, GROUP));
        assertNull(store.totalFor(b.id, GROUP));
    }

    @Test
    void initialTotalIsEnteredInTenThousandsAndActualReceiptAddsUnscaledAmount() throws Exception {
        selfCommand(a, "设置赠送灵石\nQQ：3001\n群号：6001\n灵石：87000");
        assertEquals(870000000L, store.totalFor(a.id, GROUP));
        assertEquals(870000000L, new LingShiGiftStore(directory.resolve("gifts.json")).totalFor(a.id, GROUP));
        broadcast(receipt(999L, 69600000, 17400000), 2);
        assertEquals(957000000L, store.totalFor(a.id, GROUP));
        assertEquals(957000000L, new LingShiGiftStore(directory.resolve("gifts.json")).totalFor(a.id, GROUP));
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        verify(a.group, atLeastOnce()).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().stream().map(Utils::getMessageText)
                .anyMatch(text -> text.contains("累计初始值：8.70亿灵石")));
    }

    @Test
    void overflowingInitialTotalCannotOverwriteExistingConfiguration() throws Exception {
        selfCommand(a, "设置赠送灵石\nQQ：3001\n群号：6001\n灵石：100");
        selfCommand(a, "设置赠送灵石\nQQ：3002\n群号：6001\n灵石：922337203685478");
        assertEquals(Map.of(OWNER, a.id), store.recipientsFor(GROUP));
        assertEquals(1000000L, store.totalFor(a.id, GROUP));
    }

    @Test
    void selfCanViewAllPersistedSettingsFromAnotherGroupWithoutListingOtherBots() throws Exception {
        store.configure(GROUP, OWNER, a.id, 0L);
        store.configure(GROUP, OTHER, a.id, 0L);
        store.configure(GROUP + 1, OWNER, a.id, 1000000L);
        store.configure(GROUP + 2, OWNER, a.id, null);
        store.configure(GROUP + 3, OTHER, b.id, 0L);
        setUp();
        Group otherGroup = mock(Group.class);
        when(otherGroup.getGroupId()).thenReturn(GROUP + 9);
        MessageChain command = new MessageChain().text("查看赠送灵石设置");
        gifts.新模式赠送指令(a.bot, otherGroup, member(a.id), command, "查看赠送灵石设置", 1);
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        verify(otherGroup).sendMessage(messages.capture());
        String text = Utils.getMessageText(messages.getValue());
        assertTrue(text.contains("群号：6001\nQQ：3001、3002"));
        assertTrue(text.contains("群号：6002\nQQ：3001\n当前累计（含手续费）：100.00万"));
        assertTrue(text.contains("群号：6003\nQQ：3001\n累计统计：未启用"));
        assertTrue(text.contains("默认赠送：按接收码中的今日剩余最大可收额度"));
        assertFalse(text.contains("群号：6004"));
    }

    @Test
    void viewingSettingsWithoutConfigurationReturnsAnExplicitMessage() {
        selfCommand(a, "查看赠送灵石设置");
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        verify(a.group).sendMessage(messages.capture());
        assertEquals("本号尚未设置新模式赠送灵石。", Utils.getMessageText(messages.getValue()));
    }

    @Test
    void otherMembersCannotViewSettingsEvenWhenMentioningTheBot() throws Exception {
        store.configure(GROUP, OWNER, a.id, 0L);
        command(a, OTHER, "查看赠送灵石设置");
        command(a, CONTROLLER, "查看赠送灵石设置");
        verify(a.group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void onlyBotItselfMayConfigureAndAllFieldsAreValidated() throws Exception {
        command(a, OTHER, "设置赠送灵石\nQQ:3001\n群号:6001\n灵石:0");
        assertTrue(store.recipientsFor(GROUP).isEmpty());
        command(a, CONTROLLER, "设置赠送灵石\nQQ:3001\n群号:6001\n灵石:0");
        assertTrue(store.recipientsFor(GROUP).isEmpty());
        selfCommand(a, "设置赠送灵石\nQQ:3001\n群号:6001\n灵石:0\n灵石:9");
        assertTrue(store.recipientsFor(GROUP).isEmpty());
        selfCommand(a, "设置赠送灵石\nQQ:3001\n群号:6001\n灵石:0");
        assertEquals(Map.of(OWNER, a.id), store.recipientsFor(GROUP));
    }

    @Test
    void selfConfigurationDoesNotConfigureOtherBotsWatchingTheSameGroup() throws Exception {
        String text = "设置赠送灵石\nQQ：3001\n群号：6001\n灵石：0";
        MessageChain chain = new MessageChain().text(text);
        gifts.新模式赠送指令(a.bot, a.group, member(a.id), chain, text, 1);
        b.config.setControlQQ(String.valueOf(a.id));
        gifts.新模式赠送指令(b.bot, b.group, member(a.id), chain, text, 1);
        assertEquals(Map.of(OWNER, a.id), store.recipientsFor(GROUP));
        broadcast(code(OWNER, CODE_A, "100万"), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sent(a));
        assertTrue(sent(b).isEmpty());
    }

    @Test
    void newRecipientUsesMaximumAndIsNotAlsoProcessedByArmedLegacyAccounts() throws Exception {
        store.configure(GROUP, OWNER, b.id, 0L);
        a.config.setLingShiNum(100);
        b.config.setLingShiNum(200);
        broadcast(code(OWNER, CODE_A, "5400万"), 1);
        assertTrue(sent(a).isEmpty());
        assertEquals(List.of("赠送灵石 " + CODE_A + " 54000000"), sent(b));
        broadcast(receipt(b.id, 43200000, 10800000).reply(b.sentId), 2);
        assertEquals(54000000L, store.totalFor(b.id, GROUP));
        assertEquals(0L, a.config.getLingShiTotal());
        assertEquals(0L, b.config.getLingShiTotal());
        assertEquals(100, a.config.getLingShiNum());
        assertEquals(200, b.config.getLingShiNum());
    }

    @Test
    void unknownRecipientStillUsesLegacyAndOnlyItsBotCountsLegacyReceipt() throws Exception {
        store.configure(GROUP, OWNER, b.id, 0L);
        a.config.setLingShiNum(100);
        broadcast(code(OTHER, CODE_A, null), 1);
        broadcast(receipt(a.id, 800000, 200000).reply(a.sentId), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sent(a));
        assertTrue(sent(b).isEmpty());
        assertEquals(1000000L, a.config.getLingShiTotal());
        // 新模式统计按群观察所有赠送成功消息，也包含该群的旧模式赠送。
        assertEquals(1000000L, store.totalFor(b.id, GROUP));
    }

    @Test
    void anyDonorsReceiptCountsIncludingFeeWithoutRequestAndIsDeduplicatedPerBotAndRestart()
            throws Exception {
        store.configure(GROUP, OWNER, a.id, 0L);
        store.configure(GROUP, OTHER, b.id, 0L);
        MessageChain sample = new MessageChain().at("999999")
                .text("尼檀共赠送69600000枚灵石给井雲道友！收取手续费17400000枚");
        broadcast(sample, 20);
        broadcast(sample, 20);
        assertEquals(87000000L, store.totalFor(a.id, GROUP));
        assertEquals(87000000L, store.totalFor(b.id, GROUP));
        ArgumentCaptor<MessageChain> firstNotice = ArgumentCaptor.forClass(MessageChain.class);
        verify(a.group, times(1)).sendMessage(firstNotice.capture());
        ArgumentCaptor<MessageChain> otherNotice = ArgumentCaptor.forClass(MessageChain.class);
        verify(b.group, times(1)).sendMessage(otherNotice.capture());
        assertEquals("本号在本群赠送灵石累计（含手续费）：8700.00万", Utils.getMessageText(otherNotice.getValue()));
        assertEquals("20", otherNotice.getValue().getMessageByType(ReplyMessage.class).get(0).getId());
        assertEquals("本号在本群赠送灵石累计（含手续费）：8700.00万", Utils.getMessageText(firstNotice.getValue()));
        assertEquals("20", firstNotice.getValue().getMessageByType(ReplyMessage.class).get(0).getId());
        setUp();
        broadcast(sample, 20);
        verify(a.group, never()).sendMessage(any(MessageChain.class));
        verify(b.group, never()).sendMessage(any(MessageChain.class));
        assertEquals(87000000L, store.totalFor(a.id, GROUP));
        assertEquals(87000000L, store.totalFor(b.id, GROUP));
        broadcast(sample, 21);
        assertEquals(174000000L, store.totalFor(a.id, GROUP));
        assertEquals(174000000L, store.totalFor(b.id, GROUP));
        ArgumentCaptor<MessageChain> nextNotice = ArgumentCaptor.forClass(MessageChain.class);
        verify(a.group, times(1)).sendMessage(nextNotice.capture());
        assertEquals("本号在本群赠送灵石累计（含手续费）：1.74亿", Utils.getMessageText(nextNotice.getValue()));
        assertEquals("21", nextNotice.getValue().getMessageByType(ReplyMessage.class).get(0).getId());
    }

    @Test
    void groupTotalsAndSameMessageIdsAreIsolated() throws Exception {
        store.configure(GROUP, OWNER, a.id, 0L);
        store.configure(GROUP + 1, OWNER, a.id, 100L);
        broadcast(new MessageChain().text("尼檀共赠送69600000枚灵石给井雲道友！收取手续费17400000枚"), 1);
        Group second = mock(Group.class);
        when(second.getGroupId()).thenReturn(GROUP + 1);
        MessageChain receipt = receipt(999L, 69600000, 17400000);
        service.识别大号接收码(a.bot, second, null, receipt, Utils.getMessageText(receipt), 1);
        assertEquals(87000000L, store.totalFor(a.id, GROUP));
        assertEquals(87000100L, store.totalFor(a.id, GROUP + 1));
    }

    @Test
    void temporaryRequestOnlyUsesApplicantsCodeOnceAndDoesNotRegisterThemPermanently() throws Exception {
        store.configure(GROUP, OWNER, a.id, 0L);
        command(a, OTHER, "申请灵石100");
        broadcast(code(7777L, CODE_A, "100万"), 1);
        assertTrue(sent(a).isEmpty());
        broadcast(code(OTHER, CODE_A, null), 2);
        broadcast(code(OTHER, CODE_A, null), 2);
        broadcast(code(OTHER, CODE_B, "500万"), 3);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sent(a));
        assertEquals(Map.of(OWNER, a.id), store.recipientsFor(GROUP));
        assertEquals(0, a.config.getLingShiNum());
    }

    @Test
    void temporaryOverrideForDefaultRecipientOnlyChangesOneTransferThenMaximumResumes() throws Exception {
        store.configure(GROUP, OWNER, a.id, 0L);
        command(a, OWNER, "申请灵石200");
        broadcast(code(OWNER, CODE_A, "5400万"), 1);
        int temporaryMessageId = a.sentId;
        broadcast(code(OWNER, CODE_B, "1000万"), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 2000000"), sent(a));
        broadcast(receipt(a.id, 1600000, 400000).reply(temporaryMessageId), 3);
        broadcast(code(OWNER, CODE_B, "1000万"), 4);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 2000000", "赠送灵石 " + CODE_B + " 10000000"), sent(a));
        assertEquals(Map.of(OWNER, a.id), store.recipientsFor(GROUP));
    }

    @Test
    void requestingAnotherBotDoesNotChangeAutomaticBotAndUsesLongAmounts() throws Exception {
        store.configure(GROUP, OWNER, a.id, null);
        store.configure(GROUP, OTHER, b.id, null);
        command(b, OWNER, "申请灵石300000");
        broadcast(code(OWNER, CODE_A, "5400万"), 1);
        broadcast(code(OWNER, CODE_B, "1000万"), 2);
        assertTrue(sent(a).isEmpty());
        broadcast(receipt(b.id, 2400000000L, 600000000L).reply(b.sentId), 3);
        broadcast(code(OWNER, CODE_B, "1000万"), 4);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 3000000000"), sent(b));
        assertEquals(List.of("赠送灵石 " + CODE_B + " 10000000"), sent(a));
        assertEquals(a.id, store.recipientsFor(GROUP).get(OWNER));
    }

    @Test
    void temporaryApplicantTakesPriorityOverDedicatedQqUntilActualReceiptCompletes() throws Exception {
        store.configure(GROUP, OWNER, a.id, 0L);
        a.config.setLingShiNum(100);
        command(a, OTHER, "申请灵石100");
        broadcast(code(OWNER, CODE_A, "8700万"), 1);
        assertTrue(sent(a).isEmpty());
        broadcast(code(OTHER, CODE_B, null), 2);
        int applicantMessageId = a.sentId;
        broadcast(code(OWNER, CODE_A, "8700万"), 3);
        assertEquals(List.of("赠送灵石 " + CODE_B + " 1000000"), sent(a));
        broadcast(receipt(a.id, 800000, 200000).reply(applicantMessageId), 4);
        broadcast(code(OWNER, CODE_A, "8700万"), 5);
        assertEquals(List.of("赠送灵石 " + CODE_B + " 1000000", "赠送灵石 " + CODE_A + " 87000000"), sent(a));
        assertEquals(100, a.config.getLingShiNum());
        assertEquals(0L, a.config.getLingShiTotal());
    }

    @Test
    void dedicatedQqResumesExactlyOneMinuteAfterApplicationWithoutAnApplicantsCode() throws Exception {
        store.configure(GROUP, OWNER, a.id, null);
        command(a, OTHER, "申请灵石100");
        now.addAndGet(30000L);
        broadcast(code(OWNER, CODE_A, "8700万"), 1);
        assertTrue(sent(a).isEmpty());
        now.addAndGet(29999L);
        broadcast(code(OWNER, CODE_A, "8700万"), 2);
        assertTrue(sent(a).isEmpty());
        now.incrementAndGet();
        broadcast(code(OWNER, CODE_A, "8700万"), 3);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 87000000"), sent(a));
    }

    @Test
    void sendingCommandDoesNotResetOneMinuteDeadlineOrReleasePriorityBeforeReceipt() throws Exception {
        store.configure(GROUP, OWNER, a.id, null);
        command(a, OTHER, "申请灵石100");
        now.addAndGet(40000L);
        broadcast(code(OTHER, CODE_A, null), 1);
        now.addAndGet(19999L);
        broadcast(code(OWNER, CODE_B, "8700万"), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sent(a));
        now.incrementAndGet();
        broadcast(code(OWNER, CODE_B, "8700万"), 3);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000", "赠送灵石 " + CODE_B + " 87000000"), sent(a));
    }

    @Test
    void applicantPriorityOnlyPausesDedicatedQqForItsBotAndGroup() throws Exception {
        store.configure(GROUP, OWNER, a.id, null);
        store.configure(GROUP, OTHER, b.id, null);
        store.configure(GROUP + 1, OWNER, a.id, null);
        command(a, 7777L, "申请灵石100");
        broadcast(code(OWNER, CODE_A, "8700万"), 1);
        assertTrue(sent(a).isEmpty());
        broadcast(code(OTHER, CODE_B, "100万"), 2);
        assertEquals(List.of("赠送灵石 " + CODE_B + " 1000000"), sent(b));
        Group otherGroup = mock(Group.class);
        when(otherGroup.getGroupId()).thenReturn(GROUP + 1);
        MessageChain card = code(OWNER, CODE_A, "8700万");
        service.识别大号接收码(a.bot, otherGroup, null, card, Utils.getMessageText(card), 3);
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        verify(otherGroup).sendMessage(messages.capture());
        assertEquals("赠送灵石 " + CODE_A + " 87000000", Utils.getMessageText(messages.getValue()));
    }

    @Test
    void unrelatedReceiptsCountForGroupButCannotReleaseAnApplicantsPriority() throws Exception {
        store.configure(GROUP, OWNER, a.id, 0L);
        command(a, OTHER, "申请灵石100");
        broadcast(code(OTHER, CODE_A, null), 1);
        broadcast(receipt(9999L, 69600000, 17400000), 2);
        broadcast(receipt(a.id, 1600000, 400000), 3);
        broadcast(code(OWNER, CODE_B, "8700万"), 4);
        assertEquals(89000000L, store.totalFor(a.id, GROUP));
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sent(a));
        broadcast(receipt(a.id, 800000, 200000), 5);
        broadcast(code(OWNER, CODE_B, "8700万"), 6);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000", "赠送灵石 " + CODE_B + " 87000000"), sent(a));
    }

    @Test
    void lateReceiptFromExpiredApplicationCannotReleaseNewApplicationForSameUser() throws Exception {
        store.configure(GROUP, OWNER, a.id, null);
        command(a, OTHER, "申请灵石100");
        broadcast(code(OTHER, CODE_A, null), 1);
        int oldMessageId = a.sentId;
        now.addAndGet(60000L);
        command(a, OTHER, "申请灵石200");
        broadcast(receipt(a.id, 800000, 200000).reply(oldMessageId), 2);
        broadcast(code(OWNER, CODE_B, "8700万"), 3);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sent(a));
        broadcast(code(OTHER, CODE_B, null), 4);
        int newMessageId = a.sentId;
        broadcast(receipt(a.id, 1600000, 400000).reply(newMessageId), 5);
        broadcast(code(OWNER, CODE_A, "8700万"), 6);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000", "赠送灵石 " + CODE_B + " 2000000",
                "赠送灵石 " + CODE_A + " 87000000"), sent(a));
    }

    @Test
    void temporaryCommandMustMentionTheSelectedBotAndCannotUseAnotherGroup() throws Exception {
        store.configure(GROUP, OWNER, a.id, 0L);
        MessageChain request = new MessageChain().at(String.valueOf(b.id)).text("申请灵石100");
        gifts.新模式赠送指令(a.bot, a.group, member(OTHER), request, "申请灵石100", 1);
        gifts.新模式赠送指令(b.bot, b.group, member(OTHER), request, "申请灵石100", 1);
        broadcast(code(OTHER, CODE_A, "100万"), 2);
        assertTrue(sent(a).isEmpty());
        assertTrue(sent(b).isEmpty());
        Group otherGroup = mock(Group.class);
        when(otherGroup.getGroupId()).thenReturn(GROUP + 1);
        MessageChain valid = new MessageChain().at(String.valueOf(a.id)).text("申请灵石100");
        gifts.新模式赠送指令(a.bot, otherGroup, member(OTHER), valid, "申请灵石100", 1);
        broadcast(code(OTHER, CODE_B, "100万"), 3);
        assertTrue(sent(a).isEmpty());
    }

    @Test
    void zeroOrUnparseableMaximumNeverFallsBackToArmedLegacyAmount() throws Exception {
        store.configure(GROUP, OWNER, a.id, null);
        a.config.setLingShiNum(100);
        broadcast(code(OWNER, CODE_A, null), 1);
        broadcast(code(OWNER, CODE_B, "0万"), 2);
        assertTrue(sent(a).isEmpty());
    }

    @Test
    void defaultsAndCodeDeduplicationSurviveRestartAndDoNotDependOnReceipts() throws Exception {
        store.configure(GROUP, OWNER, a.id, null);
        broadcast(code(OWNER, CODE_A, "5400万"), 1);
        setUp();
        a.config.setLingShiNum(100);
        broadcast(code(OWNER, CODE_A, "5400万"), 1);
        broadcast(code(OWNER, CODE_B, "1000万"), 2);
        assertEquals(List.of("赠送灵石 " + CODE_B + " 10000000"), sent(a));
    }

    @Test
    void markdownMentionsAreSupportedButQuotedOldMentionsDoNotClaimCodes() throws Exception {
        store.configure(GROUP, OWNER, a.id, null);
        MessageChain markdown = new MessageChain().markdown("[道友](mqqapi://markdown/mention?at_tinyid=3001)\n"
                + "您的接收码为：" + CODE_A + "\n今日您还可收5400万灵石");
        broadcast(markdown, 1);
        ReplyMessage quote = new ReplyMessage();
        quote.setChain(new MessageChain().at(String.valueOf(OWNER)).text("旧消息"));
        MessageChain code = new MessageChain();
        code.add(quote);
        code.text("您的接收码为：" + CODE_B + "\n今日您还可收1000万灵石");
        broadcast(code, 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 54000000"), sent(a));
    }

    @Test
    void concurrentDuplicateCodeOnlySendsOnce() throws Exception {
        store.configure(GROUP, OWNER, a.id, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Runnable send = () -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                deliver(a, code(OWNER, CODE_A, "5400万"), 1);
            };
            Future<?> first = pool.submit(send);
            Future<?> second = pool.submit(send);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
        assertEquals(List.of("赠送灵石 " + CODE_A + " 54000000"), sent(a));
    }

    @Test
    void nicknameHandlerCannotRemoveMentionsNeededByTemporaryGiftRequest() throws Exception {
        store.configure(GROUP, OWNER, a.id, null);
        String text = "申请灵石100";
        MessageChain chain = new MessageChain().at(String.valueOf(a.id)).text(text);
        service.设置爱称(a.bot, a.group, member(CONTROLLER), chain, text, 1);
        gifts.新模式赠送指令(a.bot, a.group, member(CONTROLLER), chain, text, 1);
        assertEquals(1, chain.getMessageByType(AtMessage.class).size());
        broadcast(code(CONTROLLER, CODE_A, null), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sent(a));
    }

    @Test
    void replayedTemporaryCodeCannotFallThroughToLegacyAfterRequestWasConsumed() throws Exception {
        store.configure(GROUP, OWNER, b.id, null);
        a.config.setLingShiNum(100);
        command(b, OTHER, "申请灵石200");
        broadcast(code(OTHER, CODE_A, null), 1);
        broadcast(code(OTHER, CODE_A, null), 1);
        assertTrue(sent(a).isEmpty());
        assertEquals(List.of("赠送灵石 " + CODE_A + " 2000000"), sent(b));
    }

    @Test
    void sendingFailureDoesNotFallThroughToLegacyOrBlockTheNextCode() throws Exception {
        store.configure(GROUP, OWNER, a.id, null);
        a.config.setLingShiNum(100);
        when(a.group.sendMessage(any(MessageChain.class)))
                .thenThrow(new IllegalStateException("disconnected")).thenReturn(-100);
        assertDoesNotThrow(() -> broadcast(code(OWNER, CODE_A, "5400万"), 1));
        broadcast(code(OWNER, CODE_B, "1000万"), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 54000000", "赠送灵石 " + CODE_B + " 10000000"), sent(a));
        assertEquals(100, a.config.getLingShiNum());
    }

    @Test
    void receiptReplyDistinguishesLegacyAndNewTransfersOfTheSameAmountOnTheSameBot() throws Exception {
        store.configure(GROUP, OWNER, a.id, 0L);
        a.config.setLingShiNum(100);
        broadcast(code(OTHER, CODE_A, null), 1);
        int legacyMessageId = a.sentId;
        broadcast(code(OWNER, CODE_B, "100万"), 2);
        int newMessageId = a.sentId;
        broadcast(receipt(a.id, 800000, 200000).reply(newMessageId), 3);
        assertEquals(0L, a.config.getLingShiTotal());
        broadcast(receipt(a.id, 800000, 200000).reply(legacyMessageId), 4);
        broadcast(receipt(a.id, 800000, 200000).reply(legacyMessageId), 4);
        assertEquals(1000000L, a.config.getLingShiTotal());
        assertEquals(2000000L, store.totalFor(a.id, GROUP));
    }

    private void selfCommand(Account account, String text) {
        MessageChain chain = new MessageChain().text(text);
        gifts.新模式赠送指令(account.bot, account.group, member(account.id), chain, text, 1);
    }

    private void command(Account account, long requester, String text) {
        MessageChain chain = new MessageChain().at(String.valueOf(account.id)).text(text);
        gifts.新模式赠送指令(account.bot, account.group, member(requester), chain, text, 1);
    }

    private void legacyCommand(Account account, long requester, int amount) {
        MessageChain chain = new MessageChain().at(String.valueOf(account.id)).text("我要灵石" + amount);
        service.艾特小号执行(account.bot, account.group, member(requester), chain, Utils.getMessageText(chain), 1);
        assertEquals(String.valueOf(account.id), chain.getMessageByType(AtMessage.class).get(0).getQq());
    }

    private Member member(long id) {
        Member member = mock(Member.class);
        when(member.getUserId()).thenReturn(id);
        return member;
    }

    private MessageChain code(long owner, String code, String limit) {
        return new MessageChain().at(String.valueOf(owner)).text("您的接收码为：" + code
                + (limit == null ? "" : "\n今日您还可收 " + limit + "灵石"));
    }

    private MessageChain receipt(long donor, long amount, long fee) {
        return new MessageChain().at(String.valueOf(donor))
                .text("尼檀共赠送" + amount + "枚灵石给井雲道友！收取手续费" + fee + "枚");
    }

    private void broadcast(MessageChain chain, int id) {
        deliver(a, chain, id);
        deliver(b, chain, id);
    }

    private void deliver(Account account, MessageChain chain, int id) {
        service.识别大号接收码(account.bot, account.group, null, chain, Utils.getMessageText(chain), id);
    }

    private List<String> sent(Account account) {
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        verify(account.group, atLeast(0)).sendMessage(messages.capture());
        return messages.getAllValues().stream().map(Utils::getMessageText)
                .filter(text -> text.startsWith("赠送灵石 ")).collect(Collectors.toList());
    }

    private static class Account {
        final long id;
        final Bot bot = mock(Bot.class);
        final Group group = mock(Group.class);
        final BotConfig config = new BotConfig();
        int sentId;

        Account(long id) {
            this.id = id;
            this.sentId = (int) -id * 100;
            config.setLingShiQQ(GROUP);
            config.setControlQQ(String.valueOf(CONTROLLER));
            when(bot.getBotId()).thenReturn(id);
            when(bot.getBotConfig()).thenReturn(config);
            when(group.getGroupId()).thenReturn(GROUP);
            when(group.getBot()).thenReturn(bot);
            when(group.sendMessage(any(MessageChain.class))).thenAnswer(invocation -> --sentId);
        }
    }
}
