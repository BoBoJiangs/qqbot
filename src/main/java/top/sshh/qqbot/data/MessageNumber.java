package top.sshh.qqbot.data;

import lombok.Getter;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;

public class MessageNumber implements Serializable {
    private static final long serialVersionUID = 1L;
    private long userId;
    private int number;
    private long time;
    private static final LocalTime RESET_TIME = LocalTime.of(7, 55); // 7:55

    public boolean isCrossResetTime() {
        LocalDateTime lastTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(this.time), ZoneId.systemDefault());
        LocalDateTime currentTime = LocalDateTime.now();

        // 计算当前时间的"理论重置时间"（今天的07:55）
        LocalDateTime todayReset = LocalDateTime.of(currentTime.toLocalDate(), RESET_TIME);

        // 检查是否跨越了重置时间点
        return lastTime.isBefore(todayReset) &&
                currentTime.isAfter(todayReset);
    }

    public MessageNumber() {
    }

    public MessageNumber(int number, long time) {
        this.number = number;
        this.time = time;
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
