package top.sshh.qqbot.config;

import org.springframework.stereotype.Component;
import top.sshh.qqbot.service.storage.LocalDataFiles;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** 迁移已有运行时文档，保留原文件供回退；后续读取不覆盖数据库内容。 */
@Component
public class LegacyStorageMigration {
    @PostConstruct
    public void migrate() throws IOException {
        for (String name : List.of("config/admin-auth.json", "config/ling-shi-gifts.json",
                "config/group-recipe-match.json", "cache/time_tasks.json", "cache/task_data.json",
                "cache/field_remind_data.ser", "xb_bot_config_map.json", "emoji_records.txt",
                "update/last_update_notice.json", "update/applied_version.txt")) {
            importFile(Paths.get(name));
        }
        Path config = Paths.get("config");
        if (Files.isDirectory(config)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(config, "bot-*.json")) {
                for (Path entry : entries) importFile(entry);
            }
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(Paths.get("."))) {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry) || !entry.getFileName().toString().matches("\\d+")) continue;
                for (String name : List.of("炼丹配置.txt", "药材价格.txt", "背包药材.txt",
                        "炼丹配方.txt", "重复采购药材.txt")) {
                    importFile(entry.resolve(name));
                }
            }
        }
    }

    private void importFile(Path path) throws IOException {
        if (Files.isRegularFile(path)) LocalDataFiles.exists(path);
    }
}
