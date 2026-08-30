package top.sshh.qqbot.service.utils;

import com.zhuangxv.bot.api.BaseApi;

import java.util.HashMap;
import java.util.Map;

/**
 * 查询消息详情。用于把 OneBot 的 message_id 换算成协议端侧的 message_seq
 * （如 SnowLuma 的 click_inline_keyboard_button 要求 msg_seq 为无符号的消息序号）。
 */
public class GetMsgApi extends BaseApi {
    private final Map<String, Object> params = new HashMap<>();

    public GetMsgApi(int messageId) {
        params.put("message_id", messageId);
    }

    @Override
    public String getAction() {
        return "get_msg";
    }

    @Override
    public Object getParams() {
        return params;
    }
}
