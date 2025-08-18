package top.sshh.qqbot.data;

public class TaskInfo {
    private String time; // HH:mm
    private String taskName;
    private boolean executed; // 是否已执行

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
}
