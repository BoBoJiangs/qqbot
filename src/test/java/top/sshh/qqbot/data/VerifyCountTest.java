package top.sshh.qqbot.data;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class VerifyCountTest {
    @Test
    void outputMatchesTheRequestedTwoBlocksAndPreservesLegacyCounters() {
        VerifyCount count = new VerifyCount();
        count.setCorrectCount(123);
        LocalDate day = count.recordAttempt("primary");
        for (int i = 1; i < 49; i++) { count.recordAttempt("primary"); }
        count.recordOutcome("primary", false, day);
        count.recordOutcome("primary", false, day);
        for (int i = 0; i < 2; i++) {
            LocalDate knnDay = count.recordAttempt("knn");
            count.recordOutcome("knn", false, knnDay);
        }
        assertEquals("主模型\n"
                + "累计：调用 49，成功 0，失败 2，未确认 47，成功率 0.00%\n"
                + "今日：调用 49，成功 0，失败 2，未确认 47，成功率 0.00%\n\n"
                + "KNN 重试\n"
                + "累计：调用 2，成功 0，失败 2，未确认 0，成功率 0.00%\n"
                + "今日：调用 2，成功 0，失败 2，未确认 0，成功率 0.00%", count.getVerifyCountString());
        assertEquals(123, count.getCorrectCount());
    }

    @Test
    void separatesModelsAndExcludesUnconfirmedFromSuccessRate() {
        VerifyCount count = new VerifyCount();
        LocalDate date = count.recordAttempt("primary");
        count.recordOutcome("primary", true, date);
        count.recordAttempt("primary");
        count.recordOutcome("primary", false, date);
        count.recordAttempt("primary");
        count.recordAttempt("knn");
        count.recordOutcome("knn", true, date);
        assertEquals(50.0, count.getPrimary().getSuccessRate());
        assertEquals(1, count.getPrimary().getUnconfirmed());
        assertEquals(100.0, count.getKnn().getSuccessRate());
        assertTrue(count.getVerifyCountString().contains("成功率 50.00%"));
        assertTrue(count.getVerifyCountString().contains("KNN 重试"));
    }

    @Test
    void oldStatisticsLoadWithoutInventingModelAttributionAndNewFieldsSurviveReload() {
        VerifyCount count = JSON.parseObject("{\"correctCount\":12,\"errorCount\":3}", VerifyCount.class);
        assertEquals(12, count.correctCount);
        assertEquals(0, count.getPrimary().getAttempts());
        assertFalse(count.getVerifyCountString().contains("旧版历史"));
        LocalDate date = count.recordAttempt("knn");
        count.recordOutcome("knn", true, date);
        VerifyCount loaded = JSON.parseObject(JSON.toJSONString(count), VerifyCount.class);
        assertEquals(1, loaded.getKnn().getAttempts());
        assertEquals(1, loaded.getKnn().getSuccesses());
        assertEquals(12, loaded.correctCount);
    }

    @Test
    void queryingAfterMidnightClearsTodayAndLateOldOutcomeOnlyAffectsAllTime() {
        VerifyCount count = new VerifyCount();
        count.recordAttempt("primary");
        count.setToday(LocalDate.now().minusDays(1));
        assertTrue(count.getVerifyCountString().contains("今日：调用 0"));
        count.recordOutcome("primary", true, LocalDate.now().minusDays(1));
        assertEquals(1, count.getPrimary().getSuccesses());
        assertEquals(0, count.getPrimary().getTodaySuccesses());
        assertEquals(1, count.getPrimary().getAttempts());
    }

    @Test
    void zeroSamplesAndNonEnglishLocaleNeverProduceNanOrFormattingExceptions() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            VerifyCount count = new VerifyCount();
            assertEquals(0.0, count.getAccuracy());
            assertFalse(count.getVerifyCountString().contains("NaN"));
            assertTrue(count.getVerifyCountString().contains("成功率 0.00%"));
            count.addCorrect();
            count.addError();
            assertEquals(50.0, count.getAccuracy());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void parallelBotsDoNotLoseCounts() {
        VerifyCount count = new VerifyCount();
        IntStream.range(0, 1000).parallel().forEach(i -> {
            LocalDate date = count.recordAttempt(i % 2 == 0 ? "primary" : "knn");
            count.recordOutcome(i % 2 == 0 ? "primary" : "knn", true, date);
        });
        assertEquals(500, count.getPrimary().getAttempts());
        assertEquals(500, count.getPrimary().getSuccesses());
        assertEquals(500, count.getKnn().getSuccesses());
    }
}
