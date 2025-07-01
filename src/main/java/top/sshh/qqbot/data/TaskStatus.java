package top.sshh.qqbot.data;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

public class TaskStatus implements Serializable {
    private static final long serialVersionUID = 1L;
    private long userId;
    private long time;
    //宗门丹药
    private boolean isDanyaoFinished;
    //宗门任务
    private boolean isZongMenFinished;
    //悬赏令
    private boolean isXslFinished;
    //秘境
    private boolean isMjFinished;
    private static final LocalTime RESET_TIME = LocalTime.of(4, 0); // 7:55

    public boolean isResetTaskTime() {
        LocalDateTime lastTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(this.time), ZoneId.systemDefault());
        LocalDateTime currentTime = LocalDateTime.now();

        LocalDateTime todayReset = LocalDateTime.of(currentTime.toLocalDate(), RESET_TIME);

        // 检查是否跨越了重置时间点
        return lastTime.isBefore(todayReset) &&
                currentTime.isAfter(todayReset);
    }

    public TaskStatus() {
    }

//    public TaskStatus(int number, long time) {
//        this.number = number;
//        this.time = time;
//    }


    public TaskStatus init(long userId, long time) {
        this.userId = userId;
        this.time = time;
        this.isDanyaoFinished = false;
        this.isZongMenFinished = false;
        this.isXslFinished = false;
        this.isMjFinished = false;
        return this;
    }

    public TaskStatus(long userId) {
        this.userId = userId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public boolean isDanyaoFinished() {
        return isDanyaoFinished;
    }

    public void setDanyaoFinished(boolean danyaoFinished) {
        isDanyaoFinished = danyaoFinished;
    }

    public boolean isZongMenFinished() {
        return isZongMenFinished;
    }

    public void setZongMenFinished(boolean zongMenFinished) {
        isZongMenFinished = zongMenFinished;
    }

    public boolean isXslFinished() {
        return isXslFinished;
    }

    public void setXslFinished(boolean xslFinished) {
        isXslFinished = xslFinished;
    }

    public boolean isMjFinished() {
        return isMjFinished;
    }

    public void setMjFinished(boolean mjFinished) {
        isMjFinished = mjFinished;
    }
}
