package top.sshh.qqbot.data;

import java.io.Serializable;

public class VerifyCodeData implements Serializable {
    private String title;
    private String picText;
    private String url;

    public VerifyCodeData(String title, String picText, String url) {
        this.title = title;
        this.picText = picText;
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPicText() {
        return picText;
    }

    public void setPicText(String picText) {
        this.picText = picText;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
