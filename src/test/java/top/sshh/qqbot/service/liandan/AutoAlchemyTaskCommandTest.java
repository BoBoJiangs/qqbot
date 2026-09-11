package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAlchemyTaskCommandTest {

    private final AutoAlchemyTask task = new AutoAlchemyTask();

    @Test
    void extractsBackpackMatchCommandWithAtBeforeOrAfterCommand() {
        MessageChain atBefore = new MessageChain().at("123456").text("匹配炼金丹");
        MessageChain atAfter = new MessageChain().text("匹配炼金丹").at("123456");
        MessageChain countThenAt = new MessageChain().text("匹配炼金丹 8 ").at("123456");

        assertEquals("匹配炼金丹", task.extractBackpackMatchCommand(atBefore.toString(), atBefore));
        assertEquals("匹配炼金丹", task.extractBackpackMatchCommand(atAfter.toString(), atAfter));
        assertEquals("匹配炼金丹 8", task.extractBackpackMatchCommand(countThenAt.toString(), countThenAt));
    }

    @Test
    void removingAtDoesNotHideInvalidDanCount() {
        MessageChain invalidCount = new MessageChain().text("匹配炼金丹 abc ").at("123456");
        String command = task.extractBackpackMatchCommand(invalidCount.toString(), invalidCount);

        assertEquals("匹配炼金丹 abc", command);
        assertFalse(new HerbBackpackMatchService().parseCommand(command, 6).isValid());
    }

    @Test
    void disabledGroupDoesNotSubmitBackpackMatch() {
        AutoAlchemyTask configuredTask = new AutoAlchemyTask();
        GroupRecipeMatchConfigService switchService = Mockito.mock(GroupRecipeMatchConfigService.class);
        HerbBackpackMatchService matchService = Mockito.mock(HerbBackpackMatchService.class);
        ReflectionTestUtils.setField(configuredTask, "groupRecipeMatchConfigService", switchService);
        ReflectionTestUtils.setField(configuredTask, "herbBackpackMatchService", matchService);

        Bot bot = Mockito.mock(Bot.class);
        Group group = Mockito.mock(Group.class);
        Member member = Mockito.mock(Member.class);
        when(bot.getBotId()).thenReturn(1001L);
        when(group.getGroupId()).thenReturn(2001L);
        MessageChain command = new MessageChain().at("1001").text("匹配炼金丹");

        configuredTask.匹配背包丹方(bot, group, member, command, command.toString(), 7);

        verify(matchService, never()).submitMatch(any(), any(), any(), any(), any());
        ArgumentCaptor<MessageChain> response = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(response.capture());
        assertTrue(textOf(response.getValue()).contains("本群丹方匹配未启用"));
    }

    @Test
    void enabledGroupSubmitsBackpackMatch() {
        AutoAlchemyTask configuredTask = new AutoAlchemyTask();
        GroupRecipeMatchConfigService switchService = Mockito.mock(GroupRecipeMatchConfigService.class);
        HerbBackpackMatchService matchService = Mockito.mock(HerbBackpackMatchService.class);
        ReflectionTestUtils.setField(configuredTask, "groupRecipeMatchConfigService", switchService);
        ReflectionTestUtils.setField(configuredTask, "herbBackpackMatchService", matchService);

        Bot bot = Mockito.mock(Bot.class);
        Group group = Mockito.mock(Group.class);
        Member member = Mockito.mock(Member.class);
        when(bot.getBotId()).thenReturn(1001L);
        when(group.getGroupId()).thenReturn(2001L);
        when(switchService.isEnabled(1001L, 2001L)).thenReturn(true);
        MessageChain command = new MessageChain().text("匹配坊市丹 8").at("1001");

        configuredTask.匹配背包丹方(bot, group, member, command, command.toString(), 8);

        verify(matchService).submitMatch(eq("匹配坊市丹 8"), eq(command), eq(group), eq(bot), eq(member));
        verify(group, never()).sendMessage(any(MessageChain.class));
    }

    @Test
    void groupAdminCanEnableAndPersistCurrentGroupSwitch() {
        AutoAlchemyTask configuredTask = new AutoAlchemyTask();
        GroupRecipeMatchConfigService switchService = Mockito.mock(GroupRecipeMatchConfigService.class);
        ReflectionTestUtils.setField(configuredTask, "groupRecipeMatchConfigService", switchService);

        Bot bot = Mockito.mock(Bot.class);
        Group group = Mockito.mock(Group.class);
        Member member = Mockito.mock(Member.class);
        when(bot.getBotId()).thenReturn(1001L);
        when(bot.getBotName()).thenReturn("小小");
        when(group.getGroupId()).thenReturn(2001L);
        when(member.getUserId()).thenReturn(3001L);
        when(member.getRole()).thenReturn("admin");
        when(switchService.setEnabled(1001L, 2001L, true)).thenReturn(true);
        MessageChain command = new MessageChain().text("启用本群丹方匹配").at("1001");

        configuredTask.开关本群丹方匹配(bot, group, member, command, command.toString(), 9);

        verify(switchService).setEnabled(1001L, 2001L, true);
        ArgumentCaptor<MessageChain> response = ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(response.capture());
        assertTrue(textOf(response.getValue()).contains("已启用本群丹方匹配"));
        assertTrue(textOf(response.getValue()).contains("设置已保存"));
    }

    private String textOf(MessageChain chain) {
        return chain.getMessageByType(TextMessage.class).stream()
                .map(TextMessage::getText)
                .reduce("", String::concat);
    }
}
