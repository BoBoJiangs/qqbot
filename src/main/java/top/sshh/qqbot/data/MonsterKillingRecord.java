package top.sshh.qqbot.data;

import java.io.Serializable;

public class MonsterKillingRecord implements Serializable {
    private static final long serialVersionUID = 1L;
    private String date;        // 打怪日期，格式：yyyy-MM-dd
    private String cultivation; // 修为，已格式化（如：213兆）
    private String monsterName; // 怪物名称
    private String daoName;     // 道号（用户名）

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getCultivation() {
        return cultivation;
    }

    public void setCultivation(String cultivation) {
        this.cultivation = cultivation;
    }

    public String getMonsterName() {
        return monsterName;
    }

    public void setMonsterName(String monsterName) {
        this.monsterName = monsterName;
    }

    public String getDaoName() {
        return daoName;
    }

    public void setDaoName(String daoName) {
        this.daoName = daoName;
    }
}
