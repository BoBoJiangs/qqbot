package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LingShiGiftStoreTest {
    @TempDir
    Path directory;

    @Test
    void settingsAndOptionalGroupTotalsSurviveRestart() throws Exception {
        Path path = directory.resolve("gifts.json");
        LingShiGiftStore store = new LingShiGiftStore(path);
        store.configure(100, 200, 300, 0L);
        store.configure(101, 201, 300, null);
        LingShiGiftStore restored = new LingShiGiftStore(path);
        assertEquals(Map.of(200L, 300L), restored.recipientsFor(100));
        assertEquals(Map.of(201L, 300L), restored.recipientsFor(101));
        assertEquals(Map.of(100L, List.of(200L), 101L, List.of(201L)), restored.recipientsByGroupFor(300));
        assertTrue(restored.recipientsByGroupFor(301).isEmpty());
        assertEquals(0L, restored.totalFor(300, 100));
        assertNull(restored.totalFor(300, 101));
    }

    @Test
    void totalsAreScopedByGroupAndReceiptDeduplicationSurvivesRestart() throws Exception {
        Path path = directory.resolve("gifts.json");
        LingShiGiftStore store = new LingShiGiftStore(path);
        store.configure(100, 200, 300, 0L);
        store.configure(101, 200, 300, 50L);
        assertEquals(87000000L, store.recordReceipt(300, 100, "100:1", 87000000L));
        assertNull(new LingShiGiftStore(path).recordReceipt(300, 100, "100:1", 87000000L));
        assertEquals(87000050L, store.recordReceipt(300, 101, "101:1", 87000000L));
        assertEquals(87000000L, store.totalFor(300, 100));
    }

    @Test
    void switchingRecipientBotAndOmittingAmountOnlyChangesCurrentBotsCounting() throws Exception {
        LingShiGiftStore store = new LingShiGiftStore(directory.resolve("gifts.json"));
        store.configure(100, 200, 300, 0L);
        store.configure(100, 201, 300, 0L);
        store.configure(100, 200, 301, null);
        assertEquals(Map.of(200L, 301L, 201L, 300L), store.recipientsFor(100));
        assertEquals(0L, store.totalFor(300, 100));
        assertNull(store.recordReceipt(301, 100, "100:1", 87000000L));
        assertNull(store.totalFor(301, 100));
        assertEquals(87000000L, store.recordReceipt(300, 100, "100:1", 87000000L));
    }

    @Test
    void claimedCodeSurvivesRestart() throws Exception {
        Path path = directory.resolve("gifts.json");
        LingShiGiftStore store = new LingShiGiftStore(path);
        store.configure(100, 200, 300, null);
        assertTrue(store.claimCode("100:1"));
        LingShiGiftStore restored = new LingShiGiftStore(path);
        assertTrue(restored.hasClaimedCode("100:1"));
        assertFalse(restored.claimCode("100:1"));
        assertTrue(restored.claimCode("101:1"));
    }

    @Test
    void invalidSettingsAndOverflowCannotOverwriteSavedState() throws Exception {
        Path path = directory.resolve("gifts.json");
        LingShiGiftStore store = new LingShiGiftStore(path);
        store.configure(100, 200, 300, Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> store.recordReceipt(300, 100, "100:1", 1));
        assertThrows(IllegalArgumentException.class, () -> store.configure(0, 200, 300, 0L));
        assertEquals(Long.MAX_VALUE, new LingShiGiftStore(path).totalFor(300, 100));
        Files.writeString(path, "{invalid");
        assertThrows(IOException.class, () -> new LingShiGiftStore(path).configure(100, 200, 300, 0L));
        assertEquals("{invalid", Files.readString(path));
    }

    @Test
    void separateBotsKeepOwnInitialTotalsDeduplicationAndDisableFlagsAcrossRestart() throws Exception {
        Path path = directory.resolve("gifts.json");
        LingShiGiftStore store = new LingShiGiftStore(path);
        store.configure(100, 200, 300, 1000000L);
        store.configure(100, 201, 301, 2000000L);
        assertEquals(88000000L, store.recordReceipt(300, 100, "100:1", 87000000L));
        assertEquals(89000000L, store.recordReceipt(301, 100, "100:1", 87000000L));
        LingShiGiftStore restored = new LingShiGiftStore(path);
        assertNull(restored.recordReceipt(300, 100, "100:1", 87000000L));
        assertNull(restored.recordReceipt(301, 100, "100:1", 87000000L));
        restored.configure(100, 201, 301, null);
        assertEquals(88000000L, restored.totalFor(300, 100));
        assertNull(restored.totalFor(301, 100));
        restored.configure(100, 201, 301, 0L);
        assertEquals(88000000L, restored.totalFor(300, 100));
        assertEquals(0L, restored.totalFor(301, 100));
    }

    @Test
    void legacySharedTotalsAndReceiptHistoryMigrateWithoutRecountingAndKeepAnOriginalBackup() throws Exception {
        Path path = directory.resolve("gifts.json");
        String legacy = "{\"recipients\":{\"100:200\":300,\"100:201\":301},"
                + "\"totals\":{\"100\":87000000},\"codes\":[\"100:code:1\"],\"receipts\":[\"100:message:1\"]}";
        Files.writeString(path, legacy);
        LingShiGiftStore store = new LingShiGiftStore(path);
        assertEquals(87000000L, store.totalFor(300, 100));
        assertEquals(87000000L, store.totalFor(301, 100));
        assertFalse(store.claimCode("100:code:1"));
        assertNull(store.recordReceipt(300, 100, "100:message:1", 87000000L));
        assertNull(store.recordReceipt(301, 100, "100:message:1", 87000000L));
        assertEquals(legacy, Files.readString(path));
        assertEquals(88000000L, store.recordReceipt(300, 100, "100:message:2", 1000000L));
        assertEquals(2, JSON.parseObject(Files.readString(path)).getIntValue("version"));
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            Path backup = files.filter(file -> file.getFileName().toString().startsWith("gifts.json.v1-"))
                    .findFirst().orElseThrow();
            assertEquals(legacy, Files.readString(backup));
        }
        LingShiGiftStore restored = new LingShiGiftStore(path);
        assertEquals(88000000L, restored.totalFor(300, 100));
        assertEquals(87000000L, restored.totalFor(301, 100));
        assertNull(restored.recordReceipt(300, 100, "100:message:2", 1000000L));
        assertEquals(88000000L, restored.recordReceipt(301, 100, "100:message:2", 1000000L));
    }

    @Test
    void migrationKeepsReceiptHistoryWhenCountingWasDisabledAndIsLaterEnabledAgain() throws Exception {
        Path path = directory.resolve("gifts.json");
        Files.writeString(path, "{\"recipients\":{\"100:200\":300},\"totals\":{},"
                + "\"receipts\":[\"100:message:1\"]}");
        LingShiGiftStore store = new LingShiGiftStore(path);
        assertNull(store.totalFor(300, 100));
        store.configure(100, 200, 300, 0L);
        assertNull(store.recordReceipt(300, 100, "100:message:1", 1000000L));
        assertEquals(1000000L, store.recordReceipt(300, 100, "100:message:2", 1000000L));
    }
}
