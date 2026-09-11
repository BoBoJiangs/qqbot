package top.sshh.qqbot.service.liandan;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.service.utils.Utils;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按机器人和群号持久化“本群丹方匹配”开关；没有配置的群默认关闭。 */
@Component
public class GroupRecipeMatchConfigService {
    private static final Logger logger = LoggerFactory.getLogger(GroupRecipeMatchConfigService.class);
    private static final Path DEFAULT_CONFIG_PATH = Paths.get("./config/group-recipe-match.json");

    private final Path configPath;
    private final Map<String, Boolean> enabledGroups = new ConcurrentHashMap<>();

    public GroupRecipeMatchConfigService() {
        this(DEFAULT_CONFIG_PATH);
    }

    GroupRecipeMatchConfigService(Path configPath) {
        this.configPath = configPath;
    }

    @PostConstruct
    void load() {
        enabledGroups.clear();
        if (!Files.exists(configPath)) {
            logger.info("群丹方匹配配置不存在，所有群默认关闭");
            return;
        }
        try {
            Map<String, Boolean> saved = JSON.parseObject(
                    Utils.readString(configPath),
                    new TypeReference<Map<String, Boolean>>() {
                    });
            if (saved != null) {
                saved.forEach((key, enabled) -> {
                    if (Boolean.TRUE.equals(enabled)) {
                        enabledGroups.put(key, true);
                    }
                });
            }
            logger.info("已加载 {} 个群丹方匹配开关", enabledGroups.size());
        } catch (Exception e) {
            logger.error("加载群丹方匹配配置失败，所有群保持默认关闭", e);
        }
    }

    public boolean isEnabled(long botId, long groupId) {
        return Boolean.TRUE.equals(enabledGroups.get(key(botId, groupId)));
    }

    /** 保存成功后才更新内存状态，避免写盘失败时提示与实际配置不一致。 */
    public synchronized boolean setEnabled(long botId, long groupId, boolean enabled) {
        Map<String, Boolean> snapshot = new LinkedHashMap<>(enabledGroups);
        String key = key(botId, groupId);
        if (enabled) {
            snapshot.put(key, true);
        } else {
            snapshot.remove(key);
        }

        try {
            saveAtomically(snapshot);
            if (enabled) {
                enabledGroups.put(key, true);
            } else {
                enabledGroups.remove(key);
            }
            return true;
        } catch (Exception e) {
            logger.error("保存群丹方匹配配置失败 botId={} groupId={} enabled={}",
                    botId, groupId, enabled, e);
            return false;
        }
    }

    private void saveAtomically(Map<String, Boolean> snapshot) throws IOException {
        Path target = configPath.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("群丹方匹配配置路径缺少父目录：" + target);
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "group-recipe-match-", ".tmp");
        try {
            Files.write(temp,
                    JSON.toJSONString(snapshot).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String key(long botId, long groupId) {
        return botId + ":" + groupId;
    }
}
