//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Config implements Serializable {
    private int danNumber = 6;
    private int makeNumber = 1000;
    private int alchemyNumber = 30;
    private boolean isAlchemy = true;
    private String makeName = "极品创世丹&极品混沌丹&极品创世丹&九天蕴仙丹&金仙造化丹&大道归一丹&菩提证道丹&太清玉液丹";
    private List<String> fixedDanName;
    private Long alchemyQQ;
    private boolean finishAutoBuyHerb;
    private int limitHerbsCount = 3;
    private int addPrice = -20;

    public Config() {
    }

    public List<String> getFixedDanName() {
        return fixedDanName;
    }

    public void setFixedDanName(List<String> fixedDanName) {
        this.fixedDanName = fixedDanName;
    }

    public int getLimitHerbsCount() {
        return this.limitHerbsCount;
    }

    public void setLimitHerbsCount(int limitHerbsCount) {
        this.limitHerbsCount = limitHerbsCount;
    }

    public int getAddPrice() {
        return this.addPrice;
    }

    public void setAddPrice(int addPrice) {
        this.addPrice = addPrice;
    }

    public Long getAlchemyQQ() {
        return this.alchemyQQ;
    }

    public void setAlchemyQQ(Long alchemyQQ) {
        this.alchemyQQ = alchemyQQ;
    }

    public boolean isFinishAutoBuyHerb() {
        return this.finishAutoBuyHerb;
    }

    public void setFinishAutoBuyHerb(boolean finishAutoBuyHerb) {
        this.finishAutoBuyHerb = finishAutoBuyHerb;
    }

    public String getMakeName() {
        return this.makeName;
    }

    public void setMakeName(String makeName) {
        this.makeName = makeName;
    }

    public int getDanNumber() {
        return this.danNumber;
    }

    public void setDanNumber(int danNumber) {
        this.danNumber = danNumber;
    }

    public int getMakeNumber() {
        return this.makeNumber;
    }

    public void setMakeNumber(int makeNumber) {
        this.makeNumber = makeNumber;
    }

    public int getAlchemyNumber() {
        return this.alchemyNumber;
    }

    public void setAlchemyNumber(int alchemyNumber) {
        this.alchemyNumber = alchemyNumber;
    }

    public boolean isAlchemy() {
        return this.isAlchemy;
    }

    public void setAlchemy(boolean alchemy) {
        this.isAlchemy = alchemy;
    }
}
