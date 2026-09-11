package top.sshh.qqbot.service.liandan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupRecipeMatchConfigServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsToDisabledAndPersistsByBotAndGroupAcrossReload() {
        Path configFile = tempDir.resolve("config").resolve("group-recipe-match.json");
        GroupRecipeMatchConfigService first = new GroupRecipeMatchConfigService(configFile);
        first.load();

        assertFalse(first.isEnabled(1001L, 2001L));
        assertTrue(first.setEnabled(1001L, 2001L, true));
        assertTrue(first.isEnabled(1001L, 2001L));
        assertFalse(first.isEnabled(1002L, 2001L));
        assertFalse(first.isEnabled(1001L, 2002L));

        GroupRecipeMatchConfigService restarted = new GroupRecipeMatchConfigService(configFile);
        restarted.load();
        assertTrue(restarted.isEnabled(1001L, 2001L));

        assertTrue(restarted.setEnabled(1001L, 2001L, false));
        GroupRecipeMatchConfigService restartedAgain = new GroupRecipeMatchConfigService(configFile);
        restartedAgain.load();
        assertFalse(restartedAgain.isEnabled(1001L, 2001L));
    }
}
