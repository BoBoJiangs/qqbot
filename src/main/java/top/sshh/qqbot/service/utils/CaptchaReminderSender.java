package top.sshh.qqbot.service.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zhuangxv.bot.api.ApiResult;
import com.zhuangxv.bot.api.support.GetGroups;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.message.MessageChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 验证码提醒只发到发送账号实际加入的群，提醒失败不打断验证处理。 */
public final class CaptchaReminderSender {
    private static final Logger logger = LoggerFactory.getLogger(CaptchaReminderSender.class);

    private CaptchaReminderSender() {
    }

    public static boolean send(Bot bot, long groupId, MessageChain messageChain) {
        if (bot == null || groupId <= 0L || messageChain == null) {
            return false;
        }
        try {
            // getGroup() 会查询并缓存未加入群的资料，flushGroups() 也不会移除旧条目；
            // 必须使用当前账号 get_group_list 的实际返回判断是否已加入目标群。
            ApiResult result = bot.invoke(new GetGroups());
            if (result == null || !"ok".equals(result.getStatus()) || result.getRetCode() != 0
                    || result.getData() == null) {
                logger.warn("无法确认机器人已加入提醒群，跳过验证码提醒 botId={} groupId={}",
                        bot.getBotId(), groupId);
                return false;
            }
            Object data = result.getData();
            JSONArray groups = JSON.parseArray(data instanceof String ? (String) data : JSON.toJSONString(data));
            boolean joined = false;
            if (groups != null) {
                for (int i = 0; i < groups.size(); i++) {
                    JSONObject group = groups.getJSONObject(i);
                    if (group != null && group.getLongValue("group_id") == groupId) {
                        joined = true;
                        break;
                    }
                }
            }
            if (!joined) {
                logger.info("机器人未加入提醒群，跳过验证码提醒 botId={} groupId={}", bot.getBotId(), groupId);
                return false;
            }
            int messageId = bot.sendGroupMessage(groupId, messageChain);
            if (messageId == 0) {
                logger.warn("验证码提醒未返回有效消息ID botId={} groupId={}", bot.getBotId(), groupId);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.warn("发送验证码提醒失败，跳过本次转发 botId={} groupId={}", bot.getBotId(), groupId, e);
            return false;
        }
    }
}
