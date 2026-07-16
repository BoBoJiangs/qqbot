package top.sshh.qqbot.data;

public class TaskInfo {
    private String time; // HH:mm
    private String taskName;
    private boolean executed; // 是否已执行（每天模式使用）
    private Long executeGroup;
    /** 间隔小时数，null 或 <=0 表示每天执行一次 */
    private Integer intervalHours;
    /** 上次执行时间戳（毫秒），间隔模式使用 */
    private long lastExecuteTime;

    public Long getExecuteGroup() {
        return executeGroup;
    }

    public void setExecuteGroup(Long executeGroup) {
        this.executeGroup = executeGroup;
    }

    public String getTime() {
        return time;
    }
    public void setTime(String time) {
        this.time = time;
    }
    public String getTaskName() {
        return taskName;
    }
    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }
    public boolean isExecuted() {
        return executed;
    }
    public void setExecuted(boolean executed) {
        this.executed = executed;
    }
    public Integer getIntervalHours() {
        return intervalHours;
    }
    public void setIntervalHours(Integer intervalHours) {
        this.intervalHours = intervalHours;
    }
    public long getLastExecuteTime() {
        return lastExecuteTime;
    }
    public void setLastExecuteTime(long lastExecuteTime) {
        this.lastExecuteTime = lastExecuteTime;
    }
}
