package top.sshh.qqbot.data;

import java.io.Serializable;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 用户定制专属购买提醒配置：订阅物品、提醒时段、启用开关。
 */
public class UserRemindConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long userQq;
    /** 设置提醒时所在的群，提醒回发到该群 */
    private Long groupId;
    private boolean enabled = true;
    /** 是否全天提醒 */
    private boolean allDay = true;
    /** 时段开始分钟数（含） */
    private int startMinutes;
    /** 时段结束分钟数（不含），小于 startMinutes 表示跨零点 */
    private int endMinutes;
    /** 展示用时段文本，如"全天"、"09:00 - 21:00" */
    private String timeRangeText = "全天";
    private Set<String> items = new HashSet<>();

    /**
     * 当前时间是否在提醒时段内，跨零点时段（如 22:00-06:00）按两段判断。
     */
    public boolean matchTime(LocalTime now) {
        if (this.allDay) {
            return true;
        }
        int cur = now.getHour() * 60 + now.getMinute();
        if (this.startMinutes == this.endMinutes) {
            return true;
        }
        if (this.startMinutes < this.endMinutes) {
            return cur >= this.startMinutes && cur < this.endMinutes;
        }
        return cur >= this.startMinutes || cur < this.endMinutes;
    }

    public Long getUserQq() {
        return userQq;
    }

    public void setUserQq(Long userQq) {
        this.userQq = userQq;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllDay() {
        return allDay;
    }

    public void setAllDay(boolean allDay) {
        this.allDay = allDay;
    }

    public int getStartMinutes() {
        return startMinutes;
    }

    public void setStartMinutes(int startMinutes) {
        this.startMinutes = startMinutes;
    }

    public int getEndMinutes() {
        return endMinutes;
    }

    public void setEndMinutes(int endMinutes) {
        this.endMinutes = endMinutes;
    }

    public String getTimeRangeText() {
        return timeRangeText;
    }

    public void setTimeRangeText(String timeRangeText) {
        this.timeRangeText = timeRangeText;
    }

    public Set<String> getItems() {
        if (items == null) {
            items = new HashSet<>();
        }
        return items;
    }

    public void setItems(Set<String> items) {
        this.items = items;
    }
}
