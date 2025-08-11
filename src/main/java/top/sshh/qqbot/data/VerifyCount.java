package top.sshh.qqbot.data;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;

@Setter
@Getter
public class VerifyCount implements Serializable {
    public int correctCount;    // 总正确次数
    public int errorCount;      // 总错误次数

    public int todayCorrectCount; // 今日正确次数
    public int todayErrorCount;   // 今日错误次数

    private LocalDate today; // 用来判断是否需要重置

    public VerifyCount() {
        this.correctCount = 0;
        this.errorCount = 0;
        this.todayCorrectCount = 0;
        this.todayErrorCount = 0;
        this.today = LocalDate.now();
    }

    private void checkAndResetToday() {
        LocalDate now = LocalDate.now();
        if (!now.equals(today)) {
            today = now;
            todayCorrectCount = 0;
            todayErrorCount = 0;
        }
    }

    public void addCorrect() {
        checkAndResetToday();
        correctCount++;
        todayCorrectCount++;
    }

    public void addError() {
        checkAndResetToday();
        errorCount++;
        todayErrorCount++;
    }

    public double getAccuracy() {
        double accuracy = (double) correctCount / (correctCount + errorCount);
        return Math.round(accuracy * 100.0);  // 保留两位
    }

    public double getTodayAccuracy() {
        if (todayCorrectCount + todayErrorCount == 0) return 0.0;
        double accuracy = (double) todayCorrectCount / (todayCorrectCount + todayErrorCount);
        return Math.round(accuracy * 100.0);
    }

    public String getVerifyCountString() {
        return "总统计：" + correctCount + "/" + (correctCount + errorCount) +
                "\n正确率：" + getAccuracy() + "%" +
                "\n今日统计：" + todayCorrectCount + "/" + (todayCorrectCount + todayErrorCount) +
                "\n今日正确率：" + getTodayAccuracy() + "%";
    }

}
