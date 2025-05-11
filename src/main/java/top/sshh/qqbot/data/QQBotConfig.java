package top.sshh.qqbot.data;

import java.io.Serializable;

public class QQBotConfig implements Serializable {

    private int FamilyTaskStatus = 0;
    private String controlQQ;
    private boolean isStartScheduled = false;
    private long lastSendTime = 0L;
    private long lastExecuteTime = 0;
    private long xslTime = 0;

    public long getXslTime() {
        return xslTime;
    }

    public void setXslTime(long xslTime) {
        this.xslTime = xslTime;
    }

    public long getLastExecuteTime() {
        return lastExecuteTime;
    }

    public void setLastExecuteTime(long lastExecuteTime) {
        this.lastExecuteTime = lastExecuteTime;
    }

    public long getLastSendTime() {
        return lastSendTime;
    }

    public void setLastSendTime(long lastSendTime) {
        this.lastSendTime = lastSendTime;
    }

    public int getFamilyTaskStatus() {
        return FamilyTaskStatus;
    }

    public void setFamilyTaskStatus(int familyTaskStatus) {
        FamilyTaskStatus = familyTaskStatus;
    }

    public String getControlQQ() {
        return controlQQ;
    }

    public void setControlQQ(String controlQQ) {
        this.controlQQ = controlQQ;
    }

    public boolean isStartScheduled() {
        return isStartScheduled;
    }

    public void setStartScheduled(boolean startScheduled) {
        isStartScheduled = startScheduled;
    }
}
