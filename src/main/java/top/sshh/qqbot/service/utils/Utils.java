package top.sshh.qqbot.service.utils;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.Message;
import com.zhuangxv.bot.message.MessageChain;

public class Utils {
    public static boolean isAtSelf(Bot bot, Group group) {

        return  group.getGroupId() == bot.getBotConfig().getGroupId() ;
    }
    public static boolean isAtSelf(Bot bot, Group group, String message) {

        return  group.getGroupId() == bot.getBotConfig().getGroupId() || message.contains(""+bot.getBotId()) ;
    }
    public static Group getRemindGroup(Bot bot,long xxGroupId) {
        long groupId = bot.getBotConfig().getGroupId();
        long taskId = bot.getBotConfig().getTaskId();
        if (taskId > 0) {
            groupId = taskId;
        }
        if (xxGroupId != 0L) {
            groupId = xxGroupId;
        }
        return bot.getGroup(groupId);
    }

    public static void forwardMessage(Bot bot,long xxGroupId, String message){
        if(bot.getBotConfig().isEnableForwardMessage()){
            getRemindGroup(bot,xxGroupId).sendMessage(new MessageChain().text(cleanMessage(message)));
        }
    }

    public static String cleanMessage(String message) {
        String cleaned = message.replaceAll("content\\[\\[.*?\\][\\s\\S]*?](?=\\s|$)", "");
        return cleaned.replaceAll("(\\n\\s*)+$", "").trim();
    }
}
