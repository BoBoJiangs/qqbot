package top.sshh.qqbot.data;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Setter
@Getter
public class VerifyCount implements Serializable {
    public int correctCount;
    public int errorCount;

    public VerifyCount() {
        this.correctCount = 0;
        this.errorCount = 0;
    }

    public double getAccuracy() {
        double accuracy = (double) correctCount / (correctCount + errorCount);
        return Math.round(accuracy * 100.0);  // 保留两位
    }

}
