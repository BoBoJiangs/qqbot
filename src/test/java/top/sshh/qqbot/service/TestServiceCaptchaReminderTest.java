package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.api.ApiResult;
import com.zhuangxv.bot.api.support.GetGroups;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Button;
import com.zhuangxv.bot.core.Buttons;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TestServiceCaptchaReminderTest {
    private static final long REMIND_GROUP_ID = 690933736L;
    private final TestService service = new TestService();
    private final Bot bot = mock(Bot.class);
    private final Group sourceGroup = mock(Group.class);
    private final BotConfig config = new BotConfig();

    @BeforeEach
    void setUp() {
        config.setGroupId(2001L);
        config.setMasterQQ(3001L);
        when(bot.getBotId()).thenReturn(1001L);
        when(bot.getBotConfig()).thenReturn(config);
        when(sourceGroup.getGroupId()).thenReturn(config.getGroupId());
        ReflectionTestUtils.setField(service, "xxGroupId", REMIND_GROUP_ID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bothPictureAndPlainRemindersSkipUnjoinedGroup(boolean withButtons) {
        groupList("[]");

        showReminder(withButtons);

        verify(bot, never()).sendGroupMessage(anyLong(), any(MessageChain.class));
        verify(bot, never()).getGroup(anyLong());
    }

    @Test
    void joinedGroupStillReceivesCaptchaPictureAndPrompt() {
        groupList("[{\"group_id\":690933736}]");
        when(bot.sendGroupMessage(eq(REMIND_GROUP_ID), any(MessageChain.class))).thenReturn(-100);

        showReminder(true);

        ArgumentCaptor<MessageChain> message = ArgumentCaptor.forClass(MessageChain.class);
        verify(bot).sendGroupMessage(eq(REMIND_GROUP_ID), message.capture());
        assertTrue(message.getValue().toMessageString().contains("验证码.png"));
        assertTrue(message.getValue().toMessageString().contains("点击序号"));
    }

    @Test
    void checksConfiguredTaskGroupInsteadOfGlobalReminderGroup() {
        config.setTaskId(4001L);
        groupList("[{\"group_id\":690933736}]");

        showReminder(true);

        verify(bot, never()).sendGroupMessage(anyLong(), any(MessageChain.class));
    }

    @Test
    void reminderFailureDoesNotInterruptCallerOrClearCaptchaPause() {
        config.setStop(true);
        groupList("[{\"group_id\":690933736}]");
        when(bot.sendGroupMessage(eq(REMIND_GROUP_ID), any(MessageChain.class)))
                .thenThrow(new IllegalStateException("群消息发送失败"));

        assertDoesNotThrow(() -> showReminder(true));
        assertTrue(config.isStop());
    }

    @Test
    void captchaAddressedToAnotherBotDoesNotPauseCurrentBot() {
        Buttons buttons = new Buttons();
        buttons.setButtonList(Collections.nCopies(6, mock(Button.class)));
        String prompt = "at_tinyid=1002) 请点击图中第2个表情对应的按钮";

        service.验证码判断(bot, sourceGroup, null, new MessageChain().text(prompt), prompt, 1, buttons);

        assertFalse(config.isStop());
        verify(bot, never()).sendGroupMessage(anyLong(), any(MessageChain.class));
    }

    @Test
    void missingSourceGroupSkipsReminderWithoutInterruptingVerification() {
        assertDoesNotThrow(() -> service.showButtonMsg(bot, null, 1, "验证码", null, new MessageChain()));
        verify(bot, never()).sendGroupMessage(anyLong(), any(MessageChain.class));
    }

    private void groupList(String data) {
        ApiResult result = new ApiResult();
        result.setStatus("ok");
        result.setRetCode(0);
        result.setData(JSON.parseArray(data));
        when(bot.invoke(any(GetGroups.class))).thenReturn(result);
    }

    private void showReminder(boolean withButtons) {
        Buttons buttons = null;
        if (withButtons) {
            Button button = mock(Button.class);
            when(button.getLabel()).thenReturn("兔子");
            buttons = new Buttons();
            buttons.setButtonList(Collections.nCopies(6, button));
            buttons.setImageUrl("https://example.com/验证码.png");
        }
        String prompt = "请点击图中第2个表情对应的按钮";
        service.showButtonMsg(bot, sourceGroup, 1, prompt, buttons, new MessageChain().text(prompt));
    }
}
