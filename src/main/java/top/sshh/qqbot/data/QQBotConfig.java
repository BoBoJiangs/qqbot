package top.sshh.qqbot.data;

import java.io.Serializable;

public class QQBotConfig implements Serializable {

    private int FamilyTaskStatus = 0;
    private String controlQQ;
    private boolean isStartScheduled = false;
    private long lastSendTime = 0L;
    private long lastExecuteTime = 0;
    private long xslTime = 0;
    private long mjTime = 0;
    private String command;
    private boolean isStop;
    private boolean isPrivateChat = true;
    //1闭关 2灵石修炼
    private int cultivateMode = 1;

    public int getCultivateMode() {
        return cultivateMode;
    }

    public void setCultivateMode(int cultivateMode) {
        this.cultivateMode = cultivateMode;
    }

    public boolean isPrivateChat() {
        return isPrivateChat;
    }

    public void setPrivateChat(boolean privateChat) {
        isPrivateChat = privateChat;
    }

    public boolean isStop() {
        return isStop;
    }

    public void setStop(boolean stop) {
        isStop = stop;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public long getMjTime() {
        return mjTime;
    }

    public void setMjTime(long mjTime) {
        this.mjTime = mjTime;
    }

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
