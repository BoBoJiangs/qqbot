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

import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestServiceGiftIsolationTest {
    private static final long GROUP_ID = 6001L;
    private static final long REQUESTER_A = 3001L;
    private static final long REQUESTER_B = 3002L;
    private static final String CODE_A = "12345678-abcd-1234-abcd-123456789012";
    private static final String CODE_B = "87654321-dcba-4321-dcba-210987654321";

    private TestService service;
    private Account accountA;
    private Account accountB;
    @TempDir
    Path directory;

    @BeforeEach
    void setUp() {
        service = spy(new TestService());
        doNothing().when(service).saveBotConfig(any(Bot.class));
        ReflectionTestUtils.setField(service, "lingShiGiftPreferenceStore",
                new LingShiGiftPreferenceStore(directory.resolve("defaults.json")));
        accountA = new Account(1001L);
        accountB = new Account(1002L);
    }

    @Test
    void savedPositiveAmountDoesNotArmAnyAccount() {
        accountA.config.setLingShiNum(100);
        accountB.config.setLingShiNum(200);

        broadcast(code(REQUESTER_A, CODE_A), 1);
        broadcast(confirmation(accountA.id, 1000000L), 2);

        assertEquals(0, sentTexts(accountA).size());
        assertEquals(0, sentTexts(accountB).size());
        assertEquals(0L, accountA.config.getLingShiTotal());
        assertEquals(0L, accountB.config.getLingShiTotal());
    }

    @Test
    void mentioningOneAccountDoesNotArmAnotherAndKeepsOriginalMentions() {
        MessageChain command = new MessageChain().at("1001").text("我要灵石100");
        service.艾特小号执行(accountA.bot, accountA.group, member(REQUESTER_A), command, "我要灵石100", 1);
        service.艾特小号执行(accountB.bot, accountB.group, member(REQUESTER_A), command, "我要灵石100", 1);
        assertEquals("1001", command.getMessageByType(AtMessage.class).get(0).getQq());
        clearInvocations(accountA.group, accountB.group);

        broadcast(code(REQUESTER_A, CODE_A), 2);

        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sentTexts(accountA));
        assertEquals(0, sentTexts(accountB).size());
    }

    @Test
    void twoAccountsInSameGroupOnlyUseTheirRequestersCodesAndTheirOwnReceipts() {
        arm(accountA, REQUESTER_A, 100);
        arm(accountB, REQUESTER_B, 200);
        clearInvocations(accountA.group, accountB.group);

        broadcast(code(REQUESTER_A, CODE_A), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sentTexts(accountA));
        assertEquals(0, sentTexts(accountB).size());

        broadcast(code(REQUESTER_B, CODE_B), 3);
        assertEquals(List.of("赠送灵石 " + CODE_B + " 2000000"), sentTexts(accountB));
        broadcast(confirmation(accountA.id, 1000000L).reply(accountA.sentId), 4);
        assertEquals(1000000L, accountA.config.getLingShiTotal());
        assertEquals(0L, accountB.config.getLingShiTotal());
        broadcast(confirmation(accountB.id, 2000000L).reply(accountB.sentId), 5);
        assertEquals(1000000L, accountA.config.getLingShiTotal());
        assertEquals(2000000L, accountB.config.getLingShiTotal());
        assertEquals(0, accountA.config.getLingShiNum());
        assertEquals(0, accountB.config.getLingShiNum());
    }

    @Test
    void latestSelectedBotAloneGivesEvenWhenBothHaveSameAmount() {
        arm(accountA, REQUESTER_A, 100);
        arm(accountB, REQUESTER_A, 100);
        broadcast(code(REQUESTER_A, CODE_A), 2);

        broadcast(confirmation(accountA.id, 1000000L), 3);

        assertEquals(0L, accountA.config.getLingShiTotal());
        assertEquals(0L, accountB.config.getLingShiTotal());
        broadcast(confirmation(accountB.id, 1000000L), 4);
        assertEquals(1000000L, accountB.config.getLingShiTotal());
        assertEquals(0L, accountA.config.getLingShiTotal());
    }

    @Test
    void laterCodeUsesSavedBotAndAmountWithoutAnotherCommand() {
        arm(accountA, REQUESTER_A, 100);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        MessageChain receipt = confirmation(accountA.id, 1000000L);
        broadcast(receipt, 3);
        clearInvocations(accountA.group);

        broadcast(receipt, 3);
        broadcast(code(REQUESTER_A, CODE_B), 4);

        assertEquals(1000000L, accountA.config.getLingShiTotal());
        assertEquals(List.of("赠送灵石 " + CODE_B + " 1000000"), sentTexts(accountA));
        broadcast(confirmation(accountA.id, 1000000L), 5);
        assertEquals(2000000L, accountA.config.getLingShiTotal());
    }

    @Test
    void oldDuplicateEventsCannotAffectANewRequest() {
        arm(accountA, REQUESTER_A, 100);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        broadcast(confirmation(accountA.id, 1000000L), 3);
        clearInvocations(accountA.group);

        broadcast(code(REQUESTER_A, CODE_A), 2);
        assertEquals(0, sentTexts(accountA).size());
        broadcast(code(REQUESTER_A, CODE_B), 4);
        broadcast(confirmation(accountA.id, 1000000L), 3);
        assertEquals(1000000L, accountA.config.getLingShiTotal());
        broadcast(confirmation(accountA.id, 1000000L), 5);
        assertEquals(2000000L, accountA.config.getLingShiTotal());
    }

    @Test
    void quotingBotWithoutMentionDoesNotArmGift() {
        ReplyMessage reply = new ReplyMessage();
        reply.setQq(String.valueOf(accountA.id));
        MessageChain command = new MessageChain();
        command.add(reply);
        command.text("我要灵石100");

        service.艾特小号执行(accountA.bot, accountA.group, member(REQUESTER_A), command, "我要灵石100", 1);
        broadcast(code(REQUESTER_A, CODE_A), 2);

        assertEquals(0, sentTexts(accountA).size());
    }

    @Test
    void concurrentDuplicateCodesOnlySendOnce() throws Exception {
        arm(accountA, REQUESTER_A, 100);
        clearInvocations(accountA.group);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Runnable processCode = () -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                deliver(accountA, code(REQUESTER_A, CODE_A), 2);
            };
            Future<?> first = pool.submit(processCode);
            Future<?> second = pool.submit(processCode);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sentTexts(accountA));
    }

    @Test
    void unrelatedAndWrongAmountReceiptsLeaveRequestPending() {
        arm(accountA, REQUESTER_A, 100);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        broadcast(confirmation(accountA.id, 1000000L).reply(accountA.sentId + 1), 3);
        broadcast(confirmation(accountA.id, 2000000L), 4);
        assertEquals(0L, accountA.config.getLingShiTotal());

        broadcast(confirmation(accountA.id, 1000000L).reply(accountA.sentId), 5);
        assertEquals(1000000L, accountA.config.getLingShiTotal());
    }

    @Test
    void replyToSentCommandCanIdentifyReceiptWithoutAnAtMention() {
        arm(accountA, REQUESTER_A, 100);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        deliver(accountA, new MessageChain().reply(accountA.sentId)
                .text("一共赠送800000枚灵石给道友！收取手续费200000枚"), 3);
        assertEquals(1000000L, accountA.config.getLingShiTotal());
    }

    @Test
    void markdownMentionsAndChainOnlyBodyAreSupported() {
        arm(accountA, REQUESTER_A, 100);
        clearInvocations(accountA.group);
        MessageChain card = new MessageChain().markdown("[一心](mqqapi://markdown/mention?at_tinyid=3001)\n"
                + "您的接收码为：" + CODE_A);
        service.识别大号接收码(accountA.bot, accountA.group, null, card, "", 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sentTexts(accountA));
    }

    @Test
    void mentionsInsideQuotedMessagesDoNotClaimAnUnaddressedCode() {
        arm(accountA, REQUESTER_A, 100);
        clearInvocations(accountA.group);
        ReplyMessage quote = new ReplyMessage();
        quote.setChain(new MessageChain().at(String.valueOf(REQUESTER_A)).text("旧消息"));
        MessageChain card = new MessageChain();
        card.add(quote);
        card.markdown("您的接收码为：" + CODE_A);

        deliver(accountA, card, 2);

        assertEquals(0, sentTexts(accountA).size());
    }

    @Test
    void otherGroupAndMalformedCodeCannotSendAGift() {
        arm(accountA, REQUESTER_A, 100);
        clearInvocations(accountA.group);
        Group otherGroup = mock(Group.class);
        when(otherGroup.getGroupId()).thenReturn(GROUP_ID + 1);
        MessageChain code = code(REQUESTER_A, CODE_A);
        service.识别大号接收码(accountA.bot, otherGroup, null, code, Utils.getMessageText(code), 2);
        deliver(accountA, new MessageChain().at(String.valueOf(REQUESTER_A))
                .text("您的接收码为：无效"), 3);

        verify(otherGroup, never()).sendMessage(any(MessageChain.class));
        assertEquals(0, sentTexts(accountA).size());
    }

    @Test
    void eachRecipientKeepsOwnAmountWhenUsingTheSameBot() {
        arm(accountA, REQUESTER_A, 100);
        arm(accountA, REQUESTER_B, 200);
        clearInvocations(accountA.group);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 1000000"), sentTexts(accountA));
        broadcast(confirmation(accountA.id, 1000000L), 3);
        clearInvocations(accountA.group);
        broadcast(code(REQUESTER_B, CODE_B), 4);
        assertEquals(List.of("赠送灵石 " + CODE_B + " 2000000"), sentTexts(accountA));
    }

    @Test
    void requesterCanCancelBeforeSendingCode() {
        arm(accountA, REQUESTER_A, 100);
        arm(accountA, REQUESTER_A, 0);
        clearInvocations(accountA.group);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        assertEquals(0, sentTexts(accountA).size());
        assertEquals(0, accountA.config.getLingShiNum());
    }

    @Test
    void expiredReceiptDoesNotCountButDefaultStillHandlesNewCode() {
        arm(accountA, REQUESTER_A, 100);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        Map<?, ?> requests = (Map<?, ?>) ReflectionTestUtils.getField(service, "lingShiGiftRequests");
        ReflectionTestUtils.setField(requests.get(accountA.id), "expiresAt", 0L);
        clearInvocations(accountA.group);

        broadcast(confirmation(accountA.id, 1000000L), 3);
        assertEquals(0, sentTexts(accountA).size());
        assertEquals(0, accountA.config.getLingShiNum());
        assertEquals(0L, accountA.config.getLingShiTotal());
        broadcast(code(REQUESTER_A, CODE_B), 4);
        assertEquals(List.of("赠送灵石 " + CODE_B + " 1000000"), sentTexts(accountA));
    }

    @Test
    void restartKeepsDefaultAndDoesNotReplayAlreadyUsedCodeEvent() {
        arm(accountA, REQUESTER_A, 100);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        broadcast(confirmation(accountA.id, 1000000L), 3);
        setUp();
        clearInvocations(accountA.group, accountB.group);

        broadcast(code(REQUESTER_A, CODE_A), 2);
        assertEquals(0, sentTexts(accountA).size());
        broadcast(code(REQUESTER_A, CODE_B), 4);
        assertEquals(List.of("赠送灵石 " + CODE_B + " 1000000"), sentTexts(accountA));
        assertEquals(0, sentTexts(accountB).size());
    }

    @Test
    void changingDefaultWhilePriorGiftIsPendingStillCountsPriorBotsReceipt() {
        arm(accountA, REQUESTER_A, 100);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        arm(accountB, REQUESTER_A, 200);
        clearInvocations(accountA.group, accountB.group);

        broadcast(code(REQUESTER_A, CODE_A), 2);
        assertEquals(0, sentTexts(accountB).size());
        broadcast(confirmation(accountA.id, 1000000L), 3);
        assertEquals(1000000L, accountA.config.getLingShiTotal());
        assertEquals(0L, accountB.config.getLingShiTotal());
        broadcast(code(REQUESTER_A, CODE_B), 4);
        assertEquals(List.of("赠送灵石 " + CODE_B + " 2000000"), sentTexts(accountB));
    }

    @Test
    void cancellingDefaultDoesNotLoseAlreadySentGiftsReceipt() {
        arm(accountA, REQUESTER_A, 100);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        arm(accountA, REQUESTER_A, 0);
        clearInvocations(accountA.group);

        broadcast(confirmation(accountA.id, 1000000L), 3);
        assertEquals(1000000L, accountA.config.getLingShiTotal());
        clearInvocations(accountA.group);
        broadcast(code(REQUESTER_A, CODE_B), 4);
        assertEquals(0, sentTexts(accountA).size());
    }

    @Test
    void largeAmountsUseLongMultiplication() {
        arm(accountA, REQUESTER_A, 300000);
        clearInvocations(accountA.group);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 3000000000"), sentTexts(accountA));
    }

    @Test
    void omittedAmountUsesLimitFromUsersExampleAndOnlySelectedBotSends() {
        armAutomatically(accountA, REQUESTER_A);
        clearInvocations(accountA.group, accountB.group);
        MessageChain card = new MessageChain().at(String.valueOf(REQUESTER_A)).text(
                "您的接收码为：c882710a-3fef-4c84-acd4-3362d6eb0069 点击赠送 （有效期10分钟）\n"
                        + "今日您还可收 5400万灵石 赠送上限\n"
                        + "（师徒/羁绊另有 1000万、好友另有 300万 的每日专属额度）");
        broadcast(card, 2);
        assertEquals(List.of("赠送灵石 c882710a-3fef-4c84-acd4-3362d6eb0069 54000000"), sentTexts(accountA));
        assertEquals(0, sentTexts(accountB).size());
    }

    @Test
    void automaticModeReadsNewRemainingLimitForEachCodeWithoutAnotherCommand() {
        armAutomatically(accountA, REQUESTER_A);
        broadcast(code(REQUESTER_A, CODE_A).text("\n今日您还可收5400万灵石"), 2);
        broadcast(confirmation(accountA.id, 54000000L), 3);
        clearInvocations(accountA.group);
        broadcast(code(REQUESTER_A, CODE_B).text("\n今日您还可收1000万灵石"), 4);
        assertEquals(List.of("赠送灵石 " + CODE_B + " 10000000"), sentTexts(accountA));
    }

    @Test
    void specifiedAmountWinsEvenWhenReceiveLimitIsDifferent() {
        arm(accountA, REQUESTER_A, 200);
        clearInvocations(accountA.group);
        broadcast(code(REQUESTER_A, CODE_A).text("\n今日您还可收100万灵石"), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 2000000"), sentTexts(accountA));
    }

    @Test
    void automaticModeDoesNotFallBackToPreviousFixedAmountWhenLimitIsMissingOrZero() {
        arm(accountA, REQUESTER_A, 100);
        armAutomatically(accountA, REQUESTER_A);
        clearInvocations(accountA.group);
        broadcast(code(REQUESTER_A, CODE_A), 2);
        broadcast(code(REQUESTER_A, CODE_B).text("\n今日您还可收0万灵石"), 3);
        assertTrue(sentTexts(accountA).stream().noneMatch(text -> text.startsWith("赠送灵石 ")));
        assertEquals(0L, accountA.config.getLingShiTotal());
    }

    @Test
    void automaticModeSurvivesRestartAndCanBeCancelledWithExplicitZero() {
        armAutomatically(accountA, REQUESTER_A);
        setUp();
        broadcast(code(REQUESTER_A, CODE_A).text("\n今日您还可收5400万灵石"), 2);
        assertEquals(List.of("赠送灵石 " + CODE_A + " 54000000"), sentTexts(accountA));
        broadcast(confirmation(accountA.id, 54000000L), 3);
        arm(accountA, REQUESTER_A, 0);
        clearInvocations(accountA.group);
        broadcast(code(REQUESTER_A, CODE_B).text("\n今日您还可收1000万灵石"), 4);
        assertEquals(0, sentTexts(accountA).size());
    }

    private void armAutomatically(Account account, long requester) {
        MessageChain command = new MessageChain().at(String.valueOf(account.id)).text("我要灵石");
        service.艾特小号执行(account.bot, account.group, member(requester), command, "我要灵石", 1);
    }

    private void arm(Account account, long requester, int amount) {
        MessageChain command = new MessageChain().at(String.valueOf(account.id)).text("我要灵石" + amount);
        service.艾特小号执行(account.bot, account.group, member(requester), command, Utils.getMessageText(command), 1);
    }

    private Member member(long id) {
        Member member = mock(Member.class);
        when(member.getUserId()).thenReturn(id);
        return member;
    }

    private MessageChain code(long owner, String code) {
        return new MessageChain().at(String.valueOf(owner)).text("您的接收码为：" + code);
    }

    private MessageChain confirmation(long botId, long amount) {
        long fee = amount / 5;
        return new MessageChain().at(String.valueOf(botId))
                .text("一共赠送" + (amount - fee) + "枚灵石给道友！收取手续费" + fee + "枚");
    }

    private void broadcast(MessageChain chain, int id) {
        deliver(accountA, chain, id);
        deliver(accountB, chain, id);
    }

    private void deliver(Account account, MessageChain chain, int id) {
        service.识别大号接收码(account.bot, account.group, null, chain, Utils.getMessageText(chain), id);
    }

    private List<String> sentTexts(Account account) {
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        org.mockito.Mockito.verify(account.group, org.mockito.Mockito.atLeast(0)).sendMessage(messages.capture());
        return messages.getAllValues().stream().map(Utils::getMessageText).collect(Collectors.toList());
    }

    private static class Account {
        private final long id;
        private final int sentId;
        private final Bot bot = mock(Bot.class);
        private final Group group = mock(Group.class);
        private final BotConfig config = new BotConfig();

        private Account(long id) {
            this.id = id;
            this.sentId = (int) -id;
            config.setLingShiQQ(GROUP_ID);
            when(bot.getBotId()).thenReturn(id);
            when(bot.getBotConfig()).thenReturn(config);
            when(group.getGroupId()).thenReturn(GROUP_ID);
            when(group.getBot()).thenReturn(bot);
            when(group.sendMessage(any(MessageChain.class))).thenReturn(sentId);
        }
    }
}
