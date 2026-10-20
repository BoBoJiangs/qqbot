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

/** 批量任务和妖塔的完成通知：优先提醒群，未入群时改发本号修炼群。 */
public final class CompletionMessageSender {
    private static final Logger logger = LoggerFactory.getLogger(CompletionMessageSender.class);

    private CompletionMessageSender() {
    }

    public static boolean send(Bot bot, long reminderGroupId, String message) {
        if (bot == null || message == null) {
            return false;
        }
        long targetGroupId = 0L;
        try {
            targetGroupId = bot.getBotConfig().getGroupId();
            if (reminderGroupId > 0L && reminderGroupId != targetGroupId
                    && hasJoinedReminderGroup(bot, reminderGroupId)) {
                targetGroupId = reminderGroupId;
            }
            if (targetGroupId <= 0L) {
                logger.warn("完成通知缺少有效目标群 botId={} message={}", bot.getBotId(), message);
                return false;
            }
            int messageId = bot.sendGroupMessage(targetGroupId, new MessageChain().text(message));
            if (messageId == 0) {
                logger.warn("完成通知未返回有效消息ID botId={} groupId={} message={}",
                        bot.getBotId(), targetGroupId, message);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.warn("完成通知发送失败 botId={} groupId={} message={}",
                    bot.getBotId(), targetGroupId, message, e);
            return false;
        }
    }

    private static boolean hasJoinedReminderGroup(Bot bot, long groupId) {
        try {
            // getGroup() 缓存的是群资料，不能据此判断当前账号是否实际入群。
            ApiResult result = bot.invoke(new GetGroups());
            if (result == null || !"ok".equals(result.getStatus()) || result.getRetCode() != 0
                    || result.getData() == null) {
                logger.warn("无法获取实际群列表，完成通知改发本号修炼群 botId={} reminderGroupId={}",
                        bot.getBotId(), groupId);
                return false;
            }
            Object data = result.getData();
            JSONArray groups = JSON.parseArray(data instanceof String ? (String) data : JSON.toJSONString(data));
            if (groups != null) {
                for (int i = 0; i < groups.size(); i++) {
                    JSONObject group = groups.getJSONObject(i);
                    if (group != null && group.getLongValue("group_id") == groupId) {
                        return true;
                    }
                }
            }
            logger.info("机器人未加入提醒群，完成通知改发本号修炼群 botId={} reminderGroupId={}",
                    bot.getBotId(), groupId);
        } catch (Exception e) {
            logger.warn("查询实际群列表失败，完成通知改发本号修炼群 botId={} reminderGroupId={}",
                    bot.getBotId(), groupId, e);
        }
        return false;
    }
}
