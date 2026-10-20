package top.sshh.qqbot.service;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.ForwardMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import top.sshh.qqbot.constant.Constant;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class XiaoBeiServiceWorldBossCommandTest {
    private static final String ANALYSIS_FORWARD_ID =
            "/9UE+qY8YrEj8QeOlR0AywFlfgqW8Yw/jDmH9k+10RmO1EL1Ebp0Er5Iq6FggTQ/";

    @TempDir
    Path tempDir;

    private String originalTargetDir;
    private XiaoBeiService service;
    private Bot bot;
    private Group group;

    @BeforeEach
    void setUp() {
        originalTargetDir = Constant.targetDir;
        // 构造服务时不读取工作区的实际小北配置。
        Constant.targetDir = tempDir.toString() + File.separator;
        service = new XiaoBeiService();
        bot = mock(Bot.class);
        group = mock(Group.class);
        when(bot.getBotId()).thenReturn(1001L);
    }

    @AfterEach
    void tearDown() {
        Constant.targetDir = originalTargetDir;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/1", "/0", " /00123 ", "/123456789012345678901234567890"})
    void plainNumericShortcutStillQueriesWorldBoss(String command) throws Exception {
        handle(new MessageChain().text(command));

        assertBossQuery(command.trim().substring(1));
    }

    @Test
    void fragmentedPlainTextStillFormsOneShortcut() throws Exception {
        handle(new MessageChain().text(" /").text("12").text("3 "));

        assertBossQuery("123");
    }

    @ParameterizedTest
    @ValueSource(strings = {ANALYSIS_FORWARD_ID, "/123"})
    void forwardedResourceIdNeverBecomesWorldBossCommandEvenIfItLooksNumeric(String forwardId) throws Exception {
        ForwardMessage forward = new ForwardMessage();
        forward.setId(forwardId);
        MessageChain chain = new MessageChain();
        chain.add(forward);
        assertEquals(forwardId, chain.toString(), "复现框架将合并转发 ID 注入字符串参数的行为");

        handle(chain);

        verifyNoInteractions(group);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/ ", "/abc", ANALYSIS_FORWARD_ID, "/123 extra", "/123\n/456",
            "/ 123", "//123", "/12.3", "/-1", "/+1", "/１２３", "正文/123"})
    void shortcutMustMatchTheWholePlainTextAndContainOnlyAsciiDigits(String command) throws Exception {
        handle(new MessageChain().text(command));

        verifyNoInteractions(group);
    }

    @Test
    void numericTextMixedWithForwardIsNotAPlainTextCommand() throws Exception {
        ForwardMessage forward = new ForwardMessage();
        forward.setId("");
        MessageChain chain = new MessageChain().text("/123");
        chain.add(forward);
        assertEquals("/123", chain.toString());

        handle(chain);

        verifyNoInteractions(group);
    }

    @Test
    void nonTextSegmentsCannotBeIgnoredToCreateAShortcut() throws Exception {
        service.enableScheduled(bot, group, null, new MessageChain().text("/123").at("3889029313"),
                "/123", 1);

        verifyNoInteractions(group);
    }

    @Test
    void injectedFrameworkStringIsNotUsedAsCommandPayload() throws Exception {
        service.enableScheduled(bot, group, null,
                new MessageChain().text("背包药材分析完成\n1. 尘磊岩麟果 128/30"), "/123", 1);

        verifyNoInteractions(group);
    }

    @Test
    void missingOrEmptyChainCannotUseTheInjectedResourceId() throws Exception {
        service.enableScheduled(bot, group, null, null, "/123", 1);
        service.enableScheduled(bot, group, null, new MessageChain(), "/123", 1);

        verifyNoInteractions(group);
    }

    private void handle(MessageChain chain) throws Exception {
        service.enableScheduled(bot, group, null, chain, chain.toString(), 1);
    }

    private void assertBossQuery(String expectedNumber) {
        ArgumentCaptor<MessageChain> messages = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(messages.capture());
        MessageChain sent = messages.getValue();
        assertEquals(1, sent.getMessageByType(AtMessage.class).size());
        assertEquals("3889029313", sent.getMessageByType(AtMessage.class).get(0).getQq());
        assertEquals(1, sent.getMessageByType(TextMessage.class).size());
        assertEquals("查询世界BOSS " + expectedNumber, sent.getMessageByType(TextMessage.class).get(0).getText());
    }
}
