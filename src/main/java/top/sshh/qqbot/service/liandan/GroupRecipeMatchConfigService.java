package top.sshh.qqbot.service.liandan;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.service.BotConfigManager;
import top.sshh.qqbot.service.utils.Utils;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** 将“本群丹方匹配”开关保存在对应机器人的 bot-{QQ}.json 中；未配置的群默认关闭。 */
@Component
public class GroupRecipeMatchConfigService {
    private static final Logger logger = LoggerFactory.getLogger(GroupRecipeMatchConfigService.class);
    private static final Path LEGACY_CONFIG_PATH = Paths.get("./config/group-recipe-match.json");

    private final BotConfigManager botConfigManager;
    private final Path configPath;

    @Autowired
    public GroupRecipeMatchConfigService(BotConfigManager botConfigManager) {
        this(botConfigManager, LEGACY_CONFIG_PATH);
    }

    GroupRecipeMatchConfigService(BotConfigManager botConfigManager, Path configPath) {
        this.botConfigManager = botConfigManager;
        this.configPath = configPath;
    }

    @PostConstruct
    void load() {
        if (!Files.exists(configPath)) {
            return;
        }
        try {
            Map<String, Boolean> saved = JSON.parseObject(
                    Utils.readString(configPath),
                    new TypeReference<Map<String, Boolean>>() {
                    });
            if (saved == null || saved.isEmpty()) {
                Files.deleteIfExists(configPath);
                return;
            }

            boolean migrated = true;
            int migratedCount = 0;
            for (Map.Entry<String, Boolean> entry : saved.entrySet()) {
                long[] ids = parseKey(entry.getKey());
                if (ids == null) {
                    logger.warn("旧群丹方配置键格式无效，保留旧文件供检查：{}", entry.getKey());
                    migrated = false;
                    continue;
                }
                if (!botConfigManager.migrateGroupRecipeMatchSetting(
                        ids[0], ids[1], Boolean.TRUE.equals(entry.getValue()))) {
                    migrated = false;
                    continue;
                }
                migratedCount++;
            }
            if (migrated) {
                Files.deleteIfExists(configPath);
                logger.info("已将 {} 个群丹方匹配开关迁入对应机器人配置", migratedCount);
            } else {
                logger.error("群丹方配置未能全部迁移，旧文件保留并会在下次启动时重试");
            }
        } catch (Exception e) {
            logger.error("迁移旧群丹方匹配配置失败，保留旧文件", e);
        }
    }

    public boolean isEnabled(long botId, long groupId) {
        return botConfigManager.isGroupRecipeMatchEnabled(botId, groupId);
    }

    public boolean setEnabled(long botId, long groupId, boolean enabled) {
        return botConfigManager.setGroupRecipeMatchEnabled(botId, groupId, enabled);
    }

    private long[] parseKey(String key) {
        if (key == null) {
            return null;
        }
        try {
            String[] parts = key.split(":", -1);
            if (parts.length != 2) {
                return null;
            }
            return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
