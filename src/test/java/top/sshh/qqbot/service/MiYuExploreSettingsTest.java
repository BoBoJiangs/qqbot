package top.sshh.qqbot.service;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Button;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.data.BotConfigPersist;
import top.sshh.qqbot.data.MiYuExploreContext;

import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class MiYuExploreSettingsTest {

    @Test
    void forbiddenZoneWithoutPillsIsRejectedWithReminder() {
        TestService service = new TestService();
        Group group = mock(Group.class);

        MiYuExploreContext context = parse(service, group, "开始自动秘域\n禁区选择：是");

        assertNull(context);
        org.mockito.ArgumentCaptor<MessageChain> reply = org.mockito.ArgumentCaptor.forClass(MessageChain.class);
        verify(group).sendMessage(reply.capture());
        String text = reply.getValue().getMessageByType(TextMessage.class).stream()
                .map(TextMessage::getText)
                .collect(Collectors.joining());
        assertTrue(text.contains("必须携带丹药才可以进入"));
    }

    @Test
    void forbiddenZoneWithRandomPillsIsAccepted() {
        TestService service = new TestService();
        Group group = mock(Group.class);

        MiYuExploreContext context = parse(service, group,
                "开始自动秘域\n禁区选择：是\n携带丹药：随机");

        assertNotNull(context);
        assertTrue(context.isForbiddenZone());
        assertTrue(context.isRandomPills());
    }

    @Test
    void omittedForbiddenAndPillsDefaultToNoForbiddenAndNoPills() {
        TestService service = new TestService();
        Group group = mock(Group.class);

        MiYuExploreContext context = parse(service, group,
                "开始自动秘域\n指定路线：否");

        assertNotNull(context);
        assertFalse(context.isForbiddenZone());
        assertFalse(context.isRandomPills());
        assertTrue(context.getPills().isEmpty());
        assertTrue(context.getRoutes().isEmpty());
    }

    @Test
    void settingsHelpDescribesFormatsAndEffects() {
        String help = ReflectionTestUtils.invokeMethod(new TestService(), "showHelpDetail", "秘域设置帮助");

        assertNotNull(help);
        assertTrue(help.contains("设置秘域路线"));
        assertTrue(help.contains("指定路线：是"));
        assertTrue(help.contains("最多刷新6次"));
        assertTrue(help.contains("必须携带丹药"));
        assertTrue(help.contains("确认秘域出发"));
    }

    @Test
    void selectingSpecifiedRouteLoadsAllPersistedCandidates() {
        TestService service = new TestService();
        Group group = mock(Group.class);
        Bot bot = mock(Bot.class);
        when(bot.getBotId()).thenReturn(42L);
        BotConfigManager botConfigManager = mock(BotConfigManager.class);
        List<List<String>> routes = Arrays.asList(
                Arrays.asList("青石道", "试剑崖", "弑师坪"),
                Arrays.asList("玄火洞", "寒潭谷", "落星原"));
        BotConfigPersist config = new BotConfigPersist();
        config.setMiYuRoutes(routes);
        when(botConfigManager.getBotConfig(42L)).thenReturn(config);
        ReflectionTestUtils.setField(service, "botConfigManager", botConfigManager);

        MiYuExploreContext context = parse(service, bot, group, "开始自动秘域\n指定路线：是");

        assertNotNull(context);
        assertEquals(routes, context.getRoutes());
    }

    @Test
    void settingCommandPersistsMultipleRoutes() {
        TestService service = new TestService();
        Group group = mock(Group.class);
        Bot bot = mock(Bot.class);
        when(bot.getBotId()).thenReturn(55L);
        BotConfigManager botConfigManager = mock(BotConfigManager.class);
        BotConfigPersist config = new BotConfigPersist();
        when(botConfigManager.getBotConfig(55L)).thenReturn(config);
        when(botConfigManager.persistBotConfig(eq(55L), any(BotConfigPersist.class))).thenReturn(true);
        ReflectionTestUtils.setField(service, "botConfigManager", botConfigManager);
        List<List<String>> expected = Arrays.asList(
                Arrays.asList("青石道", "试剑崖", "弑师坪"),
                Arrays.asList("玄火洞", "寒潭谷", "落星原"));

        ReflectionTestUtils.invokeMethod(service, "saveMiYuRoutes", bot, group,
                "设置秘域路线\n路线：青石道 试剑崖 弑师坪\n路线：玄火洞 寒潭谷 落星原", 1);

        org.mockito.ArgumentCaptor<BotConfigPersist> saved =
                org.mockito.ArgumentCaptor.forClass(BotConfigPersist.class);
        verify(botConfigManager).persistBotConfig(eq(55L), saved.capture());
        assertEquals(expected, saved.getValue().getMiYuRoutes());
    }

    @Test
    void anyFullyMatchingConfiguredRouteIsSelectedInConfigurationOrder() {
        TestService service = new TestService();
        List<List<String>> routes = Arrays.asList(
                Arrays.asList("青石道", "试剑崖", "弑师坪"),
                Arrays.asList("玄火洞", "寒潭谷", "落星原"));
        List<Button> mapButtons = Arrays.asList(
                region("低·青石道"),
                region("高·玄火洞"),
                region("中·寒潭谷"),
                region("低·落星原"),
                region("中·其他地区"));

        @SuppressWarnings("unchecked")
        List<Button> matched = ReflectionTestUtils.invokeMethod(
                service, "matchMiYuRouteButtons", routes, mapButtons);

        assertNotNull(matched);
        assertEquals(Arrays.asList("高·玄火洞", "中·寒潭谷", "低·落星原"),
                matched.stream().map(Button::getLabel).collect(Collectors.toList()));
    }

    private MiYuExploreContext parse(TestService service, Group group, String command) {
        return parse(service, mock(Bot.class), group, command);
    }

    private MiYuExploreContext parse(TestService service, Bot bot, Group group, String command) {
        return ReflectionTestUtils.invokeMethod(service, "parseMiYuExploreContext", bot, group,
                command, 1);
    }

    private Button region(String label) {
        Button button = mock(Button.class);
        when(button.getLabel()).thenReturn(label);
        return button;
    }
}
