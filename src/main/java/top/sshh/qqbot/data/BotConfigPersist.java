package top.sshh.qqbot.data;

import java.io.Serializable;

public class BotConfigPersist implements Serializable {
    //悬赏令价格查询
    private boolean enableXslPriceQuery = false;
    //主号
    private String controlQQ;
    //无偿双修
    private boolean enableAutoRepair = true;
    //悬赏令模式 1：手动 2：手动接取自动结算 3：全自动价值优先（默认） 4时长最短 5修为最高
    private int rewardMode = 5;
    //是否开启查价格
    private boolean enableCheckPrice = false;
    //是否开启猜成语
    private boolean enableGuessTheIdiom = false;
    //修炼模式：0什么都不干，1修炼，2普通闭关，3宗门闭关
    private int cultivationMode;
    //#宗门任务1：邪修和查抄（默认） 2：所有任务
    private int sectMode;
    //自动灵田结算（默认 true）
    private boolean enableAutoField = true;
    //自动秘境结算 （默认 true）
    private boolean enableAutoSecret = true;
    //悬赏令价格限制（超过这个价格会优先接取）
    private int xslPriceLimit = 1000;
    //编号设置
    private int botNumber;
    //编号设置
    private String aiCheng;
    //赠送灵石的Q群
    private Long lingShiQQ;
    //群消息自动回复
    private boolean enableAutomaticReply = false;
    //定时自动任务
    private boolean enableAutoTask = true;
    //自动验证模式 0：不验证 1：自动验证(失败停止验证) 2:自动验证（不停止验证）
    private int autoVerifyModel = 0;
    //提醒群号
    private String groupQQ;
    //宗门任务
    private boolean enableSectMission = true;

    private long lingShiTotal;

    private boolean enableAlchemy;

    private int lingShiNum;

    private int challengeMode;

    public int getChallengeMode() {
        return challengeMode;
    }

    public void setChallengeMode(int challengeMode) {
        this.challengeMode = challengeMode;
    }

    public int getLingShiNum() {
        return lingShiNum;
    }

    public void setLingShiNum(int lingShiNum) {
        this.lingShiNum = lingShiNum;
    }

    public boolean isEnableAlchemy() {
        return enableAlchemy;
    }

    public void setEnableAlchemy(boolean enableAlchemy) {
        this.enableAlchemy = enableAlchemy;
    }

    public long getLingShiTotal() {
        return lingShiTotal;
    }

    public void setLingShiTotal(long lingShiTotal) {
        this.lingShiTotal = lingShiTotal;
    }

    public String getAiCheng() {
        return aiCheng;
    }

    public void setAiCheng(String aiCheng) {
        this.aiCheng = aiCheng;
    }

    public boolean isEnableSectMission() {
        return enableSectMission;
    }

    public void setEnableSectMission(boolean enableSectMission) {
        this.enableSectMission = enableSectMission;
    }

    public String getGroupQQ() {
        return groupQQ;
    }

    public void setGroupQQ(String groupQQ) {
        this.groupQQ = groupQQ;
    }

    public String getControlQQ() {
        return controlQQ;
    }

    public void setControlQQ(String controlQQ) {
        this.controlQQ = controlQQ;
    }

    public boolean isEnableXslPriceQuery() {
        return enableXslPriceQuery;
    }

    public void setEnableXslPriceQuery(boolean enableXslPriceQuery) {
        this.enableXslPriceQuery = enableXslPriceQuery;
    }

    public boolean isEnableAutoRepair() {
        return enableAutoRepair;
    }

    public void setEnableAutoRepair(boolean enableAutoRepair) {
        this.enableAutoRepair = enableAutoRepair;
    }

    public int getRewardMode() {
        return rewardMode;
    }

    public void setRewardMode(int rewardMode) {
        this.rewardMode = rewardMode;
    }

    public boolean isEnableCheckPrice() {
        return enableCheckPrice;
    }

    public void setEnableCheckPrice(boolean enableCheckPrice) {
        this.enableCheckPrice = enableCheckPrice;
    }

    public boolean isEnableGuessTheIdiom() {
        return enableGuessTheIdiom;
    }

    public void setEnableGuessTheIdiom(boolean enableGuessTheIdiom) {
        this.enableGuessTheIdiom = enableGuessTheIdiom;
    }

    public int getCultivationMode() {
        return cultivationMode;
    }

    public void setCultivationMode(int cultivationMode) {
        this.cultivationMode = cultivationMode;
    }

    public int getSectMode() {
        return sectMode;
    }

    public void setSectMode(int sectMode) {
        this.sectMode = sectMode;
    }

    public boolean isEnableAutoField() {
        return enableAutoField;
    }

    public void setEnableAutoField(boolean enableAutoField) {
        this.enableAutoField = enableAutoField;
    }

    public boolean isEnableAutoSecret() {
        return enableAutoSecret;
    }

    public void setEnableAutoSecret(boolean enableAutoSecret) {
        this.enableAutoSecret = enableAutoSecret;
    }

    public int getXslPriceLimit() {
        return xslPriceLimit;
    }

    public void setXslPriceLimit(int xslPriceLimit) {
        this.xslPriceLimit = xslPriceLimit;
    }

    public int getBotNumber() {
        return botNumber;
    }

    public void setBotNumber(int botNumber) {
        this.botNumber = botNumber;
    }

    public Long getLingShiQQ() {
        return lingShiQQ;
    }

    public void setLingShiQQ(Long lingShiQQ) {
        this.lingShiQQ = lingShiQQ;
    }

    public boolean isEnableAutomaticReply() {
        return enableAutomaticReply;
    }

    public void setEnableAutomaticReply(boolean enableAutomaticReply) {
        this.enableAutomaticReply = enableAutomaticReply;
    }

    public boolean isEnableAutoTask() {
        return enableAutoTask;
    }

    public void setEnableAutoTask(boolean enableAutoTask) {
        this.enableAutoTask = enableAutoTask;
    }

    public int getAutoVerifyModel() {
        return autoVerifyModel;
    }

    public void setAutoVerifyModel(int autoVerifyModel) {
        this.autoVerifyModel = autoVerifyModel;
    }
}
