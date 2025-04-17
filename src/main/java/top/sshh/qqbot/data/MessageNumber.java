package top.sshh.qqbot.data;

import lombok.Getter;

import java.io.Serializable;
import java.util.Calendar;

public class MessageNumber implements Serializable {
    private static final long serialVersionUID = 1L;
    private long userId;
    private int number;
    private long time;
    private static final int RESET_HOUR = 8; // 重置时间点(早上8点)

    // getters and setters
    public boolean isCrossResetTime() {
        Calendar lastCal = Calendar.getInstance();
        lastCal.setTimeInMillis(this.time);

        Calendar currentCal = Calendar.getInstance();
        currentCal.setTimeInMillis(System.currentTimeMillis());

        // 检查是否跨越了8点 (前一天 <8点 且 当前 >=8点)
        return lastCal.get(Calendar.HOUR_OF_DAY) < RESET_HOUR &&
                currentCal.get(Calendar.HOUR_OF_DAY) >= RESET_HOUR;
    }

    public MessageNumber() {
    }


    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }
}
