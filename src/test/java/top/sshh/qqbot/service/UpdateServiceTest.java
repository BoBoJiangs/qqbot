package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.qqbot.config.UpdateProperties;
import top.sshh.qqbot.data.UpdateManifest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UpdateServiceTest {
    private final UpdateProperties properties = new UpdateProperties();
    private UpdateService service;

    @BeforeEach
    void setUp() throws Exception {
        service = spy(new UpdateService());
        ReflectionTestUtils.setField(service, "updateProperties", properties);
        doReturn("1.1.16").when(service).getCurrentVersionText();
        // 测试只核对是否触发安装，禁止真实下载、替换运行包或重启。
        doNothing().when(service).applyUpdateAndRestart(any(UpdateManifest.class));
        doNothing().when(service).applyUpdateAndRestartNow(any(UpdateManifest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "null", "missing"})
    void optionalUpdateNeverInstallsAutomaticallyButStillAllowsTheImmediateUpdateCommand(String force) throws Exception {
        UpdateManifest manifest = manifest(force, "1.1.17");
        doReturn(manifest).when(service).checkForUpdate();

        service.scheduledAutoUpdate();
        service.scheduledAutoUpdate();

        assertTrue(service.hasUpdate(manifest));
        verify(service, never()).applyUpdateAndRestart(any(UpdateManifest.class));
        verify(service, never()).applyUpdateAndRestartNow(any(UpdateManifest.class));

        TestService commands = new TestService();
        ReflectionTestUtils.setField(commands, "updateService", service);
        commands.版本更新指令(null, mock(Group.class), null, new MessageChain(), "立即更新", 1);

        verify(service).applyUpdateAndRestartNow(manifest);
        verify(service, never()).applyUpdateAndRestart(any(UpdateManifest.class));
    }

    @Test
    void forcedNewVersionInstallsAutomatically() throws Exception {
        UpdateManifest manifest = manifest("true", "1.1.17");
        doReturn(manifest).when(service).checkForUpdate();

        service.scheduledAutoUpdate();

        verify(service).applyUpdateAndRestart(manifest);
        verify(service, never()).applyUpdateAndRestartNow(any(UpdateManifest.class));
    }

    @Test
    void forcedVersionDoesNotInstallAgainOnceTheCurrentVersionHasCaughtUp() throws Exception {
        UpdateManifest manifest = manifest("true", "1.1.17");
        AtomicReference<String> current = new AtomicReference<>("1.1.16");
        doAnswer(call -> current.get()).when(service).getCurrentVersionText();
        doReturn(manifest).when(service).checkForUpdate();
        doAnswer(call -> {
            current.set(manifest.getVersion());
            return null;
        }).when(service).applyUpdateAndRestart(manifest);

        service.scheduledAutoUpdate();
        service.scheduledAutoUpdate();

        verify(service, times(1)).applyUpdateAndRestart(manifest);
    }

    @ParameterizedTest
    @CsvSource({
            "false, 1.1.17, true", "true, 1.1.17, true", "null, 1.1.17, true",
            "false, 1.1.16, false", "true, 1.1.16, false", "null, 1.1.16, false",
            "false, 1.1.15, false", "true, 1.1.15, false", "null, 1.1.15, false"
    })
    void forceControlsInstallationPolicyRatherThanBypassingVersionComparison(
            String force, String version, boolean available) {
        assertEquals(available, service.hasUpdate(manifest(force, version)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1.16", "1.1.15"})
    void forcedSameOrOlderVersionNeverDownloadsFromEitherEntryPoint(String version) throws Exception {
        UpdateManifest manifest = manifest("true", version);
        doReturn(manifest).when(service).checkForUpdate();
        service.scheduledAutoUpdate();
        TestService commands = new TestService();
        ReflectionTestUtils.setField(commands, "updateService", service);
        commands.版本更新指令(null, mock(Group.class), null, new MessageChain(), "立即更新", 1);

        verify(service, never()).applyUpdateAndRestart(any(UpdateManifest.class));
        verify(service, never()).applyUpdateAndRestartNow(any(UpdateManifest.class));
    }

    @Test
    void disabledAutoApplyBlocksForcedUpdatesButRetainsManualUpdates() throws Exception {
        properties.setAutoApply(false);
        UpdateManifest manifest = manifest("true", "1.1.17");
        doReturn(manifest).when(service).checkForUpdate();
        service.scheduledAutoUpdate();
        verify(service, never()).checkForUpdate();

        TestService commands = new TestService();
        ReflectionTestUtils.setField(commands, "updateService", service);
        commands.版本更新指令(null, mock(Group.class), null, new MessageChain(), "立即更新", 1);

        verify(service).applyUpdateAndRestartNow(manifest);
        verify(service, never()).applyUpdateAndRestart(any(UpdateManifest.class));
    }

    @Test
    void disabledUpdateFeatureSkipsAutomaticChecksAndRejectsManualChecks() throws Exception {
        properties.setEnabled(false);

        service.scheduledAutoUpdate();

        verify(service, never()).checkForUpdate();
        assertThrows(IllegalStateException.class, () -> service.checkForUpdate());
        verify(service, never()).applyUpdateAndRestart(any(UpdateManifest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "true", "missing"})
    void checkingAndReadingChangelogNeverInstallAnUpdate(String force) throws Exception {
        UpdateManifest manifest = manifest(force, "1.1.17");
        doReturn(manifest).when(service).checkForUpdate();

        String message = service.buildCheckUpdateMessage();
        String changelog = service.buildLatestChangelogMessage();

        assertTrue(message.contains("检测到新版本"));
        assertTrue(message.contains("立即更新"));
        assertTrue(message.contains("true".equals(force) ? "强制更新" : "手动更新"));
        assertTrue(changelog.contains("发布说明"));
        verify(service, never()).applyUpdateAndRestart(any(UpdateManifest.class));
        verify(service, never()).applyUpdateAndRestartNow(any(UpdateManifest.class));
    }

    @Test
    void stateShowsANewOptionalVersionAsAvailable() {
        ReflectionTestUtils.setField(service, "lastManifest", manifest("false", "1.1.17"));

        Map<String, Object> state = service.getState();

        assertEquals(true, state.get("updateAvailable"));
        assertEquals("1.1.17", state.get("latestVersion"));
        assertFalse(((UpdateManifest) state.get("latest")).getForce());
    }

    private UpdateManifest manifest(String force, String version) {
        String field = "missing".equals(force) ? "" : ",\"force\":" + force;
        return JSON.parseObject("{\"version\":\"" + version + "\",\"notes\":\"发布说明\"" + field + "}",
                UpdateManifest.class);
    }
}
