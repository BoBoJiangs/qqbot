package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sshh.qqbot.data.BotConfigPersist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotConfigManagerMiYuRoutesTest {

    @TempDir
    Path tempDir;

    @Test
    void routesAreSavedInPerBotConfigAndPreservedWhenAnUpdateOmitsThem() throws Exception {
        long botId = 2718281828459045L;
        BotConfigManager manager = new BotConfigManager(tempDir);
        BotConfigPersist config = manager.getBotConfig(botId);
        List<List<String>> routes = Arrays.asList(
                Arrays.asList("青石道", "试剑崖", "弑师坪"),
                Arrays.asList("玄火洞", "寒潭谷", "落星原"));
        config.setMiYuRoutes(routes);

        assertTrue(manager.updateBotConfig(botId, config));
        Path botConfigPath = tempDir.resolve("bot-" + botId + ".json");
        assertTrue(Files.exists(botConfigPath));
        assertEquals(routes,
                JSON.parseObject(Files.readString(botConfigPath), BotConfigPersist.class).getMiYuRoutes());

        BotConfigPersist partialUpdate = JSON.parseObject(
                JSON.toJSONString(manager.getBotConfig(botId)), BotConfigPersist.class);
        partialUpdate.setMiYuRoutes(null);
        assertTrue(manager.updateBotConfig(botId, partialUpdate));

        BotConfigManager reloadedManager = new BotConfigManager(tempDir);
        assertEquals(routes, reloadedManager.getBotConfig(botId).getMiYuRoutes());
    }

    @Test
    void groupRecipeMatchSettingsAreSavedPerBotAndPreservedWhenAnUpdateOmitsThem() throws Exception {
        long botId = 3141592653589793L;
        long firstGroupId = 123456789L;
        long secondGroupId = 987654321L;
        BotConfigManager manager = new BotConfigManager(tempDir);
        BotConfigPersist config = manager.getBotConfig(botId);
        Map<Long, Boolean> groups = new HashMap<>();
        groups.put(firstGroupId, true);
        groups.put(secondGroupId, false);
        config.setGroupRecipeMatchEnabled(groups);

        assertTrue(manager.updateBotConfig(botId, config));
        assertTrue(manager.isGroupRecipeMatchEnabled(botId, firstGroupId));
        Path botConfigPath = tempDir.resolve("bot-" + botId + ".json");
        BotConfigPersist saved = JSON.parseObject(Files.readString(botConfigPath), BotConfigPersist.class);
        assertEquals(Boolean.FALSE, saved.getGroupRecipeMatchEnabled().get(secondGroupId));

        BotConfigPersist partialUpdate = JSON.parseObject(
                JSON.toJSONString(manager.getBotConfig(botId)), BotConfigPersist.class);
        partialUpdate.setGroupRecipeMatchEnabled(null);
        assertTrue(manager.updateBotConfig(botId, partialUpdate));

        BotConfigManager reloadedManager = new BotConfigManager(tempDir);
        assertTrue(reloadedManager.isGroupRecipeMatchEnabled(botId, firstGroupId));
        assertFalse(reloadedManager.isGroupRecipeMatchEnabled(botId, secondGroupId));
    }
}
