package top.sshh.qqbot.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LingShiGiftPreferenceStoreTest {
    @TempDir
    Path directory;

    @Test
    void defaultsAreScopedByGroupAndRecipientAndLastBotReplacesPreviousOne() throws Exception {
        Path path = directory.resolve("defaults.json");
        LingShiGiftPreferenceStore store = new LingShiGiftPreferenceStore(path);
        store.select(100L, 200L, 300L, 10);
        store.select(100L, 201L, 300L, 20);
        store.select(101L, 200L, 300L, 30);
        store.select(100L, 200L, 301L, 40);

        LingShiGiftPreferenceStore restored = new LingShiGiftPreferenceStore(path);
        assertEquals(Map.of(201L, 20), restored.recipientsFor(100L, 300L));
        assertEquals(Map.of(200L, 40), restored.recipientsFor(100L, 301L));
        assertEquals(Map.of(200L, 30), restored.recipientsFor(101L, 300L));
        restored.clear(100L, 200L);
        assertTrue(new LingShiGiftPreferenceStore(path).recipientsFor(100L, 301L).isEmpty());
        assertEquals(Map.of(200L, 30), restored.recipientsFor(101L, 300L));
    }

    @Test
    void codeHistorySurvivesRestartAndStaysBounded() throws Exception {
        Path path = directory.resolve("defaults.json");
        LingShiGiftPreferenceStore store = new LingShiGiftPreferenceStore(path);
        for (int i = 0; i < 514; i++) {
            store.rememberCode("100:message:" + i);
        }
        LingShiGiftPreferenceStore restored = new LingShiGiftPreferenceStore(path);
        assertFalse(restored.hasHandledCode("100:message:0"));
        assertTrue(restored.hasHandledCode("100:message:513"));
        assertFalse(restored.hasHandledCode("101:message:513"));
    }

    @Test
    void corruptSettingsAreNotOverwrittenOrSilentlyUsed() throws Exception {
        Path path = directory.resolve("defaults.json");
        String broken = "{invalid";
        Files.writeString(path, broken);
        LingShiGiftPreferenceStore store = new LingShiGiftPreferenceStore(path);
        assertThrows(IOException.class, () -> store.select(100L, 200L, 300L, 10));
        assertEquals(broken, Files.readString(path));
    }

    @Test
    void automaticLimitModeSurvivesRestartAndCanBeChangedToFixedAmount() throws Exception {
        Path path = directory.resolve("defaults.json");
        LingShiGiftPreferenceStore store = new LingShiGiftPreferenceStore(path);
        store.selectAutoLimit(100L, 200L, 300L);
        LingShiGiftPreferenceStore restored = new LingShiGiftPreferenceStore(path);
        assertEquals(Map.of(200L, 0), restored.recipientsFor(100L, 300L));
        restored.select(100L, 200L, 300L, 100);
        assertEquals(Map.of(200L, 100), new LingShiGiftPreferenceStore(path).recipientsFor(100L, 300L));
    }

    @Test
    void oldFixedAmountConfigurationRemainsCompatible() throws Exception {
        Path path = directory.resolve("defaults.json");
        Files.writeString(path, "{\"defaults\":{\"100:200\":{\"botId\":300,\"amount\":100}},\"handledCodes\":[]}");
        assertEquals(Map.of(200L, 100), new LingShiGiftPreferenceStore(path).recipientsFor(100L, 300L));
    }
}
