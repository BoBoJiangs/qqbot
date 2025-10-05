package top.sshh.qqbot.data;

import java.time.LocalDateTime;

/**
 * Bot配置DTO类，用于Web界面展示和交互
 */
public class BotConfigDTO {
    
    // 基本信息
    private Long botId;
    private String botName;
    private Long groupId;
    
    // 悬赏令相关配置
    private boolean enableXslPriceQuery = false;  // 悬赏令价格查询
    private int rewardMode = 5;  // 悬赏令模式 1：手动 2：半自动 3：全自动价值优先 4：时长最短 5：修为最高
    private int xslPriceLimit = 1000;  // 悬赏令价格限制
    
    // 修炼相关配置
    private int cultivationMode = 0;  // 修炼模式：0无 1修炼 2普通闭关 3宗门闭关
    private boolean enableAutoRepair = true;  // 无偿双修
    
    // 宗门任务配置
    private boolean enableSectMission = true;  // 宗门任务
    private int sectMode = 1;  // 宗门任务模式 1：邪修查抄 2：所有任务
    private boolean enableAutoField = true;  // 自动灵田结算
    
    // 秘境相关配置
    private boolean enableAutoSecret = true;  // 自动秘境结算
    
    // 查询功能配置
    private boolean enableCheckPrice = false;  // 价格查询
    private boolean enableGuessTheIdiom = false;  // 猜成语查询
    
    // 群管理配置
    private boolean enableAutomaticReply = false;  // 群消息自动回复
    private boolean enableAutoTask = true;  // 定时自动任务
    
    // 验证配置
    private int autoVerifyModel = 0;  // 自动验证模式 0：手动 1：半自动 2：全自动
    
    // 控制配置
    private String controlQQ;  // 主号QQ
    private String groupQQ;  // 提醒群号
    private Long lingShiQQ;  // 赠送灵石的Q群
    private int botNumber;  // 编号设置
    
    // 运行时状态（只读）
    private boolean isStop;
    private boolean isStartScheduled;
    private String command;
    private String verificationStatus;
    private int challengeMode;
    
    private LocalDateTime lastSendTime;
    
    private Long xslTime;
    private Long mjTime;
    
    // 任务状态
    private int taskStatusEquip;
    private int taskStatusSkills;
    private int taskStatusHerbs;
    
    // 定时任务状态
    private boolean isStartScheduledMarket;
    private boolean isStartScheduledEquip;
    private boolean isStartScheduledSkills;
    private boolean isStartScheduledHerbs;
    
    // 其他功能状态
    private boolean isEnableAutoBuyLowPrice;
    private boolean isStartAutoLingG;
    private boolean isEnableAutoCqMj;
    private boolean isEnableCheckMarket;
    
    // 构造函数
    public BotConfigDTO() {}
    
    // Getter和Setter方法
    public Long getBotId() {
        return botId;
    }
    
    public void setBotId(Long botId) {
        this.botId = botId;
    }
    
    public String getBotName() {
        return botName;
    }
    
    public void setBotName(String botName) {
        this.botName = botName;
    }
    
    public Long getGroupId() {
        return groupId;
    }
    
    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }
    
    public boolean isEnableXslPriceQuery() {
        return enableXslPriceQuery;
    }
    
    public void setEnableXslPriceQuery(boolean enableXslPriceQuery) {
        this.enableXslPriceQuery = enableXslPriceQuery;
    }
    
    public int getRewardMode() {
        return rewardMode;
    }
    
    public void setRewardMode(int rewardMode) {
        this.rewardMode = rewardMode;
    }
    
    public int getXslPriceLimit() {
        return xslPriceLimit;
    }
    
    public void setXslPriceLimit(int xslPriceLimit) {
        this.xslPriceLimit = xslPriceLimit;
    }
    
    public int getCultivationMode() {
        return cultivationMode;
    }
    
    public void setCultivationMode(int cultivationMode) {
        this.cultivationMode = cultivationMode;
    }
    
    public boolean isEnableAutoRepair() {
        return enableAutoRepair;
    }
    
    public void setEnableAutoRepair(boolean enableAutoRepair) {
        this.enableAutoRepair = enableAutoRepair;
    }
    
    public boolean isEnableSectMission() {
        return enableSectMission;
    }
    
    public void setEnableSectMission(boolean enableSectMission) {
        this.enableSectMission = enableSectMission;
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
    
    public String getControlQQ() {
        return controlQQ;
    }
    
    public void setControlQQ(String controlQQ) {
        this.controlQQ = controlQQ;
    }
    
    public String getGroupQQ() {
        return groupQQ;
    }
    
    public void setGroupQQ(String groupQQ) {
        this.groupQQ = groupQQ;
    }
    
    public Long getLingShiQQ() {
        return lingShiQQ;
    }
    
    public void setLingShiQQ(Long lingShiQQ) {
        this.lingShiQQ = lingShiQQ;
    }
    
    public int getBotNumber() {
        return botNumber;
    }
    
    public void setBotNumber(int botNumber) {
        this.botNumber = botNumber;
    }
    
    // 运行时状态只读属性
    public boolean isStop() {
        return isStop;
    }
    
    public void setStop(boolean stop) {
        isStop = stop;
    }
    
    public boolean isStartScheduled() {
        return isStartScheduled;
    }
    
    public void setStartScheduled(boolean startScheduled) {
        isStartScheduled = startScheduled;
    }
    
    public String getCommand() {
        return command;
    }
    
    public void setCommand(String command) {
        this.command = command;
    }
    
    public String getVerificationStatus() {
        return verificationStatus;
    }
    
    public void setVerificationStatus(String verificationStatus) {
        this.verificationStatus = verificationStatus;
    }
    
    public int getChallengeMode() {
        return challengeMode;
    }
    
    public void setChallengeMode(int challengeMode) {
        this.challengeMode = challengeMode;
    }
    
    public LocalDateTime getLastSendTime() {
        return lastSendTime;
    }
    
    public void setLastSendTime(LocalDateTime lastSendTime) {
        this.lastSendTime = lastSendTime;
    }
    
    public Long getXslTime() {
        return xslTime;
    }
    
    public void setXslTime(Long xslTime) {
        this.xslTime = xslTime;
    }
    
    public Long getMjTime() {
        return mjTime;
    }
    
    public void setMjTime(Long mjTime) {
        this.mjTime = mjTime;
    }
    
    public int getTaskStatusEquip() {
        return taskStatusEquip;
    }
    
    public void setTaskStatusEquip(int taskStatusEquip) {
        this.taskStatusEquip = taskStatusEquip;
    }
    
    public int getTaskStatusSkills() {
        return taskStatusSkills;
    }
    
    public void setTaskStatusSkills(int taskStatusSkills) {
        this.taskStatusSkills = taskStatusSkills;
    }
    
    public int getTaskStatusHerbs() {
        return taskStatusHerbs;
    }
    
    public void setTaskStatusHerbs(int taskStatusHerbs) {
        this.taskStatusHerbs = taskStatusHerbs;
    }
    
    public boolean isStartScheduledMarket() {
        return isStartScheduledMarket;
    }
    
    public void setStartScheduledMarket(boolean startScheduledMarket) {
        isStartScheduledMarket = startScheduledMarket;
    }
    
    public boolean isStartScheduledEquip() {
        return isStartScheduledEquip;
    }
    
    public void setStartScheduledEquip(boolean startScheduledEquip) {
        isStartScheduledEquip = startScheduledEquip;
    }
    
    public boolean isStartScheduledSkills() {
        return isStartScheduledSkills;
    }
    
    public void setStartScheduledSkills(boolean startScheduledSkills) {
        isStartScheduledSkills = startScheduledSkills;
    }
    
    public boolean isStartScheduledHerbs() {
        return isStartScheduledHerbs;
    }
    
    public void setStartScheduledHerbs(boolean startScheduledHerbs) {
        isStartScheduledHerbs = startScheduledHerbs;
    }
    
    public boolean isEnableAutoBuyLowPrice() {
        return isEnableAutoBuyLowPrice;
    }
    
    public void setEnableAutoBuyLowPrice(boolean enableAutoBuyLowPrice) {
        isEnableAutoBuyLowPrice = enableAutoBuyLowPrice;
    }
    
    public boolean isStartAutoLingG() {
        return isStartAutoLingG;
    }
    
    public void setStartAutoLingG(boolean startAutoLingG) {
        isStartAutoLingG = startAutoLingG;
    }
    
    public boolean isEnableAutoCqMj() {
        return isEnableAutoCqMj;
    }
    
    public void setEnableAutoCqMj(boolean enableAutoCqMj) {
        isEnableAutoCqMj = enableAutoCqMj;
    }
    
    public boolean isEnableCheckMarket() {
        return isEnableCheckMarket;
    }
    
    public void setEnableCheckMarket(boolean enableCheckMarket) {
        isEnableCheckMarket = enableCheckMarket;
    }
    
    /**
     * 转换为BotConfigPersist对象
     */
    public BotConfigPersist toBotConfigPersist() {
        BotConfigPersist persist = new BotConfigPersist();
        persist.setEnableXslPriceQuery(this.enableXslPriceQuery);
        persist.setRewardMode(this.rewardMode);
        persist.setXslPriceLimit(this.xslPriceLimit);
        persist.setCultivationMode(this.cultivationMode);
        persist.setEnableAutoRepair(this.enableAutoRepair);
        persist.setEnableSectMission(this.enableSectMission);
        persist.setSectMode(this.sectMode);
        persist.setEnableAutoField(this.enableAutoField);
        persist.setEnableAutoSecret(this.enableAutoSecret);
        persist.setEnableCheckPrice(this.enableCheckPrice);
        persist.setEnableGuessTheIdiom(this.enableGuessTheIdiom);
        persist.setEnableAutomaticReply(this.enableAutomaticReply);
        persist.setEnableAutoTask(this.enableAutoTask);
        persist.setAutoVerifyModel(this.autoVerifyModel);
        persist.setControlQQ(this.controlQQ);
        persist.setGroupQQ(this.groupQQ);
        persist.setLingShiQQ(this.lingShiQQ);
        persist.setBotNumber(this.botNumber);
        return persist;
    }
    
    /**
     * 从BotConfigPersist对象创建DTO
     */
    public static BotConfigDTO fromBotConfigPersist(Long botId, String botName, Long groupId, BotConfigPersist persist) {
        BotConfigDTO dto = new BotConfigDTO();
        dto.setBotId(botId);
        dto.setBotName(botName);
        dto.setGroupId(groupId);
        
        if (persist != null) {
            dto.setEnableXslPriceQuery(persist.isEnableXslPriceQuery());
            dto.setRewardMode(persist.getRewardMode());
            dto.setXslPriceLimit(persist.getXslPriceLimit());
            dto.setCultivationMode(persist.getCultivationMode());
            dto.setEnableAutoRepair(persist.isEnableAutoRepair());
            dto.setEnableSectMission(persist.isEnableSectMission());
            dto.setSectMode(persist.getSectMode());
            dto.setEnableAutoField(persist.isEnableAutoField());
            dto.setEnableAutoSecret(persist.isEnableAutoSecret());
            dto.setEnableCheckPrice(persist.isEnableCheckPrice());
            dto.setEnableGuessTheIdiom(persist.isEnableGuessTheIdiom());
            dto.setEnableAutomaticReply(persist.isEnableAutomaticReply());
            dto.setEnableAutoTask(persist.isEnableAutoTask());
            dto.setAutoVerifyModel(persist.getAutoVerifyModel());
            dto.setControlQQ(persist.getControlQQ());
            dto.setGroupQQ(persist.getGroupQQ());
            dto.setLingShiQQ(persist.getLingShiQQ());
            dto.setBotNumber(persist.getBotNumber());
        }
        
        return dto;
    }
}
