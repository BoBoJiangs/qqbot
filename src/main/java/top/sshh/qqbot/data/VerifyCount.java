package top.sshh.qqbot.data;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Locale;

@Setter
@Getter
public class VerifyCount implements Serializable {
    public int correctCount;    // 保留旧版历史；新分项统计不再写入这两个计数
    public int errorCount;

    public int todayCorrectCount; // 今日正确次数
    public int todayErrorCount;   // 今日错误次数

    private LocalDate today; // 用来判断是否需要重置
    private ModelStats primary = new ModelStats();
    private ModelStats knn = new ModelStats();

    public VerifyCount() {
        this.correctCount = 0;
        this.errorCount = 0;
        this.todayCorrectCount = 0;
        this.todayErrorCount = 0;
        this.today = LocalDate.now();
    }

    private void checkAndResetToday() {
        if (primary == null) { primary = new ModelStats(); }
        if (knn == null) { knn = new ModelStats(); }
        LocalDate now = LocalDate.now();
        if (!now.equals(today)) {
            today = now;
            todayCorrectCount = 0;
            todayErrorCount = 0;
            primary.resetToday();
            knn.resetToday();
        }
    }

    public synchronized void addCorrect() {
        checkAndResetToday();
        correctCount++;
        todayCorrectCount++;
    }

    public synchronized void addError() {
        checkAndResetToday();
        errorCount++;
        todayErrorCount++;
    }

    public synchronized double getAccuracy() {
        return accuracy(correctCount, errorCount);
    }

    public synchronized double getTodayAccuracy() {
        checkAndResetToday();
        return accuracy(todayCorrectCount, todayErrorCount);
    }

    public synchronized LocalDate recordAttempt(String recognizer) {
        checkAndResetToday();
        ModelStats stats = modelStats(recognizer);
        stats.attempts++;
        stats.todayAttempts++;
        return today;
    }

    public synchronized void recordOutcome(String recognizer, boolean success, LocalDate attemptedOn) {
        checkAndResetToday();
        ModelStats stats = modelStats(recognizer);
        if (success) {
            stats.successes++;
            if (today.equals(attemptedOn)) { stats.todaySuccesses++; }
        } else {
            stats.failures++;
            if (today.equals(attemptedOn)) { stats.todayFailures++; }
        }
    }

    private ModelStats modelStats(String recognizer) {
        if ("primary".equals(recognizer)) { return primary; }
        if ("knn".equals(recognizer)) { return knn; }
        throw new IllegalArgumentException("未知识别来源：" + recognizer);
    }

    private static double accuracy(int successes, int failures) {
        long total = (long) successes + failures;
        return total == 0 ? 0.0 : Math.round(successes * 10000.0 / total) / 100.0;
    }

    public synchronized String getVerifyCountString() {
        checkAndResetToday();
        return "主模型\n" + primary.describe() + "\n\nKNN 重试\n" + knn.describe();
    }

    @Getter
    @Setter
    public static class ModelStats implements Serializable {
        private int attempts;
        private int successes;
        private int failures;
        private int todayAttempts;
        private int todaySuccesses;
        private int todayFailures;

        public int getUnconfirmed() { return Math.max(0, attempts - successes - failures); }
        public int getTodayUnconfirmed() { return Math.max(0, todayAttempts - todaySuccesses - todayFailures); }
        public double getSuccessRate() { return accuracy(successes, failures); }
        public double getTodaySuccessRate() { return accuracy(todaySuccesses, todayFailures); }

        private void resetToday() {
            todayAttempts = 0;
            todaySuccesses = 0;
            todayFailures = 0;
        }

        private String describe() {
            return describeLine("累计", attempts, successes, failures)
                    + "\n" + describeLine("今日", todayAttempts, todaySuccesses, todayFailures);
        }

        private static String describeLine(String title, int attempts, int successes, int failures) {
            String rate = String.format(Locale.ROOT, "%.2f%%", accuracy(successes, failures));
            return title + "：调用 " + attempts + "，成功 " + successes + "，失败 " + failures
                    + "，未确认 " + Math.max(0, attempts - successes - failures) + "，成功率 " + rate;
        }
    }
}
