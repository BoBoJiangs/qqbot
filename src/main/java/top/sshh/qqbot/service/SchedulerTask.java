package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.TaskInfo;
import top.sshh.qqbot.service.impl.TaskStore;

import java.util.ArrayList;
import java.util.List;

@Component
public class SchedulerTask {

    public SchedulerTask() {
        TaskStore.loadTasks();
    }

    /** 接收用户消息设置定时任务 */
    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.ONLY_ITSELF)
    public void enableScheduled(Bot bot, Group group, Member member,
                                MessageChain messageChain, String message, Integer messageId) {
        message = message.trim();
        if (message.startsWith("设置定时任务")) {
            String qq = String.valueOf(bot.getBotId()); // 使用发送消息用户 QQ
//            TaskStore.taskMap.get(qq).clear();
//            TaskStore.taskMap.computeIfAbsent(qq, k -> new ArrayList<>()).clear();
            String[] lines = message.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(" ", 2);
                if (parts.length == 2) {
                    TaskStore.addTask(qq, parts[0], parts[1]);
                }
            }
            group.sendMessage(new MessageChain().text("✅ 已为你成功设置定时任务!"));
        }else if (message.equals("查询定时任务")) {
            String qq = String.valueOf(bot.getBotId());
            List<TaskInfo> tasks = TaskStore.taskMap.get(qq);
            if (tasks == null || tasks.isEmpty()) {
                group.sendMessage(new MessageChain().text("❌ 你还没有设置任何定时任务。"));
            } else {
                StringBuilder sb = new StringBuilder("📋 你的定时任务列表：\n");
                for (TaskInfo task : tasks) {
                    sb.append(task.getTime())
                            .append(" ")
                            .append(task.getTaskName())
                            .append(task.isExecuted() ? " ✅ 已执行" : " ⏳ 未执行")
                            .append("\n");
                }
                group.sendMessage(new MessageChain().text(sb.toString()));
            }
        }else if (message.startsWith("移除定时任务")) {
            String qq = String.valueOf(bot.getBotId());
            String[] parts = message.split(" ", 2);
            if (parts.length < 2) {
                group.sendMessage(new MessageChain().text("⚠️ 请指定要移除的任务时间，例如：移除定时任务 12:30"));
                return;
            }
            String time = parts[1].trim();
            boolean removed = TaskStore.removeTask(qq, time);
            if (removed) {
                group.sendMessage(new MessageChain().text("🗑️ 已成功移除定时任务：" + time));
            } else {
                group.sendMessage(new MessageChain().text("❌ 没有找到该时间的定时任务：" + time));
            }
        }else if (message.startsWith("清空定时任务")) {
            String qq = String.valueOf(bot.getBotId()); // 使用发送消息用户 QQ
            TaskStore.taskMap.computeIfAbsent(qq, k -> new ArrayList<>()).clear();

            group.sendMessage(new MessageChain().text("✅ 已成功清空定时任务！"));
        }


    }

    /** 每分钟执行一次检查任务 */
    @Scheduled(cron = "0 * * * * *")
    public void checkTasks() {TaskStore.checkTasks();
    }

    /** 每天凌晨重置任务执行状态 */
    @Scheduled(cron = "0 0 0 * * *")
    public void resetTasks() {
        TaskStore.resetTasks();
    }
}
