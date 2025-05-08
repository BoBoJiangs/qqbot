package top.sshh.qqbot.data;

import java.io.Serializable;

public class RemindTime implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long qq;
    private Long expireTime;
    private String text;
    private Long groupId;
    private Long remindQq;

    public Long getRemindQq() {
        return remindQq;
    }

    public void setRemindQq(Long remindQq) {
        this.remindQq = remindQq;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public Long getQq() {
        return qq;
    }

    public void setQq(Long qq) {
        this.qq = qq;
    }

    public Long getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(Long expireTime) {
        this.expireTime = expireTime;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
