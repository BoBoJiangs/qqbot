package top.sshh.qqbot.service.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SQLiteDocumentStoreTest {
    @TempDir Path root;

    private SQLiteDocumentStore open() throws Exception {
        return new SQLiteDocumentStore(root.resolve("data/qqbot.db"), root);
    }

    @Test
    void importsLegacyOnceAndKeepsCommittedChangesAfterReopening() throws Exception {
        Path legacy = root.resolve("config/bot-1001.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{\"value\":\"旧配置\"}");
        SQLiteDocumentStore store = open();
        assertEquals("{\"value\":\"旧配置\"}", new String(store.read(legacy), StandardCharsets.UTF_8));
        store.write(legacy, "{\"value\":\"新配置\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("{\"value\":\"旧配置\"}", Files.readString(legacy), "迁移原件必须保留");
        SQLiteDocumentStore restarted = open();
        assertEquals("{\"value\":\"新配置\"}", new String(restarted.read(legacy), StandardCharsets.UTF_8));
    }

    @Test
    void deletedLegacyDocumentDoesNotReappearOnRestart() throws Exception {
        Path legacy = root.resolve("time_tasks.json");
        Files.writeString(legacy, "旧任务");
        assertTrue(open().delete(legacy));
        assertTrue(Files.exists(legacy));
        assertFalse(open().exists(legacy));
    }

    @Test
    void emptyDocumentStaysEmptyInsteadOfReimportingOldData() throws Exception {
        Path legacy = root.resolve("背包药材.txt");
        Files.writeString(legacy, "乌灵参 99");
        open().write(legacy, new byte[0]);
        assertTrue(open().exists(legacy));
        assertEquals(0, open().read(legacy).length);
    }

    @Test
    void monotonicRevisionInvalidatesCachesForRapidUpdates() throws Exception {
        SQLiteDocumentStore store = open();
        Path path = root.resolve("药材价格.txt");
        store.write(path, new byte[]{1});
        long before = store.lastModified(path);
        store.write(path, new byte[]{2});
        assertTrue(store.lastModified(path) > before);
    }

    @Test
    void relativeKeysSurviveMovingTheWholeApplicationDirectory() throws Exception {
        Path source = root.resolve("config/config.json");
        open().write(source, "配置".getBytes(StandardCharsets.UTF_8));
        Path moved = root.resolve("moved");
        Files.createDirectories(moved);
        Files.copy(root.resolve("data/qqbot.db"), moved.resolve("qqbot.db"));
        SQLiteDocumentStore relocated = new SQLiteDocumentStore(moved.resolve("qqbot.db"), moved);
        assertEquals("配置", new String(relocated.read(moved.resolve("config/config.json")), StandardCharsets.UTF_8));
    }

    @Test
    void concurrentWritersCommitCompleteDocuments() throws Exception {
        SQLiteDocumentStore first = open();
        SQLiteDocumentStore second = open();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int id = i;
                results.add(workers.submit(() -> {
                    try {
                        (id % 2 == 0 ? first : second).write(root.resolve("bot-" + id),
                                ("机器人" + id).getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> result : results) result.get(20, TimeUnit.SECONDS);
            for (int i = 0; i < 20; i++) {
                assertEquals("机器人" + i, new String(open().read(root.resolve("bot-" + i)), StandardCharsets.UTF_8));
            }
        } finally {
            workers.shutdownNow();
        }
    }
}
