package top.sshh.qqbot.data;

import com.alibaba.fastjson2.annotation.JSONField;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QQBotConfig implements Serializable {
    private int FamilyTaskStatus = 0;
    private String controlQQ;
    // 小北悬赏优先物品列表
    private List<String> xslRewardPriorityItems = new ArrayList<>(Arrays.asList(
            "宿命通", "灭剑血胧", "天剑破虚", "仙火焚天", "千慄鬼噬", "封神剑", "明心问道果",
            "离火梧桐芝", "剑魄竹笋", "尘磊岩麟果", "木灵三针花", "鎏鑫天晶草", "檀芒九叶花",
            "坎水玄冰果", "大荒星陨指", "八角玄冰草", "地心淬灵乳", "天麻翡石精", "奇茸通天菊",
            "风神诀", "合欢魔功"
    ));
    @JSONField(serialize = false)
    private boolean isStartScheduled = false;
    @JSONField(serialize = false)
    private long lastSendTime = 0L;
    @JSONField(serialize = false)
    private long lastExecuteTime = 0;
    @JSONField(serialize = false)
    private long xslTime = 0;
    @JSONField(serialize = false)
    private long mjTime = 0;
    @JSONField(serialize = false)
    private String command;
    @JSONField(serialize = false)
    private boolean isStop;
    private boolean isPrivateChat = true;
    //1闭关 2灵石修炼
    private int cultivateMode = 1;

    private boolean enableFamilyTask = true;

    private boolean enableMj = true;

    private boolean enableXsl = true;
    private int recoveryPillType = 1;

    public int getRecoveryPillType() {
        return recoveryPillType;
    }

    public void setRecoveryPillType(int recoveryPillType) {
        this.recoveryPillType = recoveryPillType;
    }

    public boolean isEnableFamilyTask() {
        return enableFamilyTask;
    }

    public void setEnableFamilyTask(boolean enableFamilyTask) {
        this.enableFamilyTask = enableFamilyTask;
    }

    public boolean isEnableMj() {
        return enableMj;
    }

    public void setEnableMj(boolean enableMj) {
        this.enableMj = enableMj;
    }

    public boolean isEnableXsl() {
        return enableXsl;
    }

    public void setEnableXsl(boolean enableXsl) {
        this.enableXsl = enableXsl;
    }

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

    public List<String> getXslRewardPriorityItems() {
        return xslRewardPriorityItems;
    }

    public void setXslRewardPriorityItems(List<String> xslRewardPriorityItems) {
        this.xslRewardPriorityItems = xslRewardPriorityItems;
    }
}
