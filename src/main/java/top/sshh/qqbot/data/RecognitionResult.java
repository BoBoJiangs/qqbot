package top.sshh.qqbot.data;

import java.io.Serializable;
import java.util.List;

public class RecognitionResult implements Serializable {
    public List<String> emojiList;
    public String result;
    public int code;
    public String msg;
    public String answer;
    public String url;
    public String title;
    private int lastClickIndex;


    public RecognitionResult(List<String> list, String r) {
        this.emojiList = list;
        this.result = r;
    }

    public RecognitionResult(String title,String result, String url) {
        this.result = result;
        this.url = url;
        this.title = title;
    }

    public RecognitionResult() {
    }

    public int getLastClickIndex() {
        return lastClickIndex;
    }

    public void setLastClickIndex(int lastClickIndex) {
        this.lastClickIndex = lastClickIndex;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<String> getEmojiList() {
        return emojiList;
    }

    public void setEmojiList(List<String> emojiList) {
        this.emojiList = emojiList;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
