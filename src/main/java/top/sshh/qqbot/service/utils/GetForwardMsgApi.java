package top.sshh.qqbot.service.utils;

import com.zhuangxv.bot.api.BaseApi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询合并转发消息详情。
 *
 * <p>NapCat 使用 {@code message_id}，SnowLuma 同时兼容 {@code id} 和
 * {@code message_id}。调用方可以在 {@code message_id} 查询失败时使用
 * {@link #withId(String)} 兼容旧版 SnowLuma。</p>
 */
public final class GetForwardMsgApi extends BaseApi {
    private final Map<String, Object> params = new LinkedHashMap<>();

    public GetForwardMsgApi(String forwardId) {
        this("message_id", forwardId);
    }

    private GetForwardMsgApi(String parameterName, String forwardId) {
        params.put(parameterName, forwardId);
    }

    public static GetForwardMsgApi withId(String forwardId) {
        return new GetForwardMsgApi("id", forwardId);
    }

    @Override
    public String getAction() {
        return "get_forward_msg";
    }

    @Override
    public Object getParams() {
        return params;
    }
}
