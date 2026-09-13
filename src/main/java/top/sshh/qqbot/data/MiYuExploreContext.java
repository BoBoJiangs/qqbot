package top.sshh.qqbot.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 秘域探索（路线/禁区/丹药自动选择）的运行时上下文。
 * 与 botConfig 的待执行命令机制一致采用内存态：重启丢失后重发「开始自动秘域」多行命令即可。
 */
public class MiYuExploreContext {

    public enum State {
        /** 设置已保存，等待出关后发出探索秘域（闭关可能持续数小时，此状态不参与超时清理） */
        WAIT_START,
        /** 已发出探索秘域，等待游戏的秘域地图卡片 */
        WAIT_MAP,
        /** 正在串行点击地区按钮，此期间忽略游戏回发的新卡片 */
        CLICKING_ROUTE,
        /** 地区已选完，等待游戏的备药卡片 */
        WAIT_PILLS,
        /** 正在点击丹药/出发按钮 */
        CLICKING_PILLS
    }

    /** 路线设置的地区名（3个），可为空表示未设置路线 */
    private List<String> route = new ArrayList<>();
    /** 禁区选择：是=true（追禁区，兜底时按 禁>高>中>低 取最高）；否=false（不选禁级） */
    private boolean forbiddenZone = true;
    /** 指定携带的丹药名（3种），空=不设置（选完地区直接出发） */
    private List<String> pills = new ArrayList<>();
    /** 携带丹药=随机 */
    private boolean randomPills = false;

    private State state = State.WAIT_START;
    /** 已点击刷新路线的次数（游戏上限6次/日） */
    private int refreshCount = 0;
    private final long createTime = System.currentTimeMillis();
    /** 最近一次活跃时间（发出探索秘域或收到卡片时刷新），用于超时清理 */
    private long lastActiveTime = createTime;

    public List<String> getRoute() {
        return route;
    }

    public void setRoute(List<String> route) {
        this.route = route;
    }

    public boolean isForbiddenZone() {
        return forbiddenZone;
    }

    public void setForbiddenZone(boolean forbiddenZone) {
        this.forbiddenZone = forbiddenZone;
    }

    public List<String> getPills() {
        return pills;
    }

    public void setPills(List<String> pills) {
        this.pills = pills;
    }

    public boolean isRandomPills() {
        return randomPills;
    }

    public void setRandomPills(boolean randomPills) {
        this.randomPills = randomPills;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public int getRefreshCount() {
        return refreshCount;
    }

    public void setRefreshCount(int refreshCount) {
        this.refreshCount = refreshCount;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getLastActiveTime() {
        return lastActiveTime;
    }

    public void setLastActiveTime(long lastActiveTime) {
        this.lastActiveTime = lastActiveTime;
    }

    public void touch() {
        this.lastActiveTime = System.currentTimeMillis();
    }
}
