package top.sshh.qqbot.service.liandan;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sshh.qqbot.data.BotConfigPersist;
import top.sshh.qqbot.service.BotConfigManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupRecipeMatchConfigServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsToDisabledAndPersistsByBotAndGroupAcrossReload() {
        Path configDirectory = tempDir.resolve("config");
        Path legacyConfigFile = configDirectory.resolve("group-recipe-match.json");
        GroupRecipeMatchConfigService first = new GroupRecipeMatchConfigService(
                new BotConfigManager(configDirectory), legacyConfigFile);
        first.load();

        assertFalse(first.isEnabled(1001L, 2001L));
        assertTrue(first.setEnabled(1001L, 2001L, true));
        assertTrue(first.isEnabled(1001L, 2001L));
        assertFalse(first.isEnabled(1002L, 2001L));
        assertFalse(first.isEnabled(1001L, 2002L));

        GroupRecipeMatchConfigService restarted = new GroupRecipeMatchConfigService(
                new BotConfigManager(configDirectory), legacyConfigFile);
        restarted.load();
        assertTrue(restarted.isEnabled(1001L, 2001L));

        assertTrue(restarted.setEnabled(1001L, 2001L, false));
        GroupRecipeMatchConfigService restartedAgain = new GroupRecipeMatchConfigService(
                new BotConfigManager(configDirectory), legacyConfigFile);
        restartedAgain.load();
        assertFalse(restartedAgain.isEnabled(1001L, 2001L));
    }

    @Test
    void migratesLegacySettingsIntoPerBotConfigAndRemovesLegacyFile() throws Exception {
        Path configDirectory = tempDir.resolve("config");
        Files.createDirectories(configDirectory);
        Path legacyConfigFile = configDirectory.resolve("group-recipe-match.json");
        Files.write(legacyConfigFile,
                "{\"1001:2001\":true,\"1002:2002\":true}".getBytes(StandardCharsets.UTF_8));

        BotConfigManager manager = new BotConfigManager(configDirectory);
        GroupRecipeMatchConfigService service = new GroupRecipeMatchConfigService(manager, legacyConfigFile);
        service.load();

        assertTrue(service.isEnabled(1001L, 2001L));
        assertTrue(service.isEnabled(1002L, 2002L));
        assertFalse(Files.exists(legacyConfigFile));
        BotConfigPersist firstBotConfig = JSON.parseObject(
                Files.readString(configDirectory.resolve("bot-1001.json")), BotConfigPersist.class);
        assertEquals(Boolean.TRUE, firstBotConfig.getGroupRecipeMatchEnabled().get(2001L));
    }

    @Test
    void retryingLegacyMigrationDoesNotOverrideAnExistingPerBotSetting() throws Exception {
        Path configDirectory = tempDir.resolve("config");
        Files.createDirectories(configDirectory);
        Path legacyConfigFile = configDirectory.resolve("group-recipe-match.json");
        Files.write(legacyConfigFile,
                "{\"1001:2001\":true}".getBytes(StandardCharsets.UTF_8));

        BotConfigManager manager = new BotConfigManager(configDirectory);
        BotConfigPersist botConfig = manager.getBotConfig(1001L);
        Map<Long, Boolean> groups = new HashMap<>();
        groups.put(2001L, false);
        botConfig.setGroupRecipeMatchEnabled(groups);
        assertTrue(manager.persistBotConfig(1001L, botConfig));

        GroupRecipeMatchConfigService service = new GroupRecipeMatchConfigService(manager, legacyConfigFile);
        service.load();

        assertFalse(service.isEnabled(1001L, 2001L));
        assertFalse(Files.exists(legacyConfigFile));
    }
}
