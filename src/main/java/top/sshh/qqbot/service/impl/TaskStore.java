package top.sshh.qqbot.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.alibaba.fastjson2.JSONWriter;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import top.sshh.qqbot.data.TaskInfo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TaskStore {
    private static final String CONFIG_FILE = "cache/time_tasks.json";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    // 线程安全 Map: QQ号 -> 任务列表
    public static Map<String, List<TaskInfo>> taskMap = new ConcurrentHashMap<>();

    /** 加载配置 */
    public static synchronized void loadTasks() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) {
            taskMap = new ConcurrentHashMap<>();
            return;
        }
        try {
            // 读取文件全部内容为字符串
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            taskMap = JSON.parseObject(content, new TypeReference<ConcurrentHashMap<String, List<TaskInfo>>>(){});
            if (taskMap == null) {
                taskMap = new ConcurrentHashMap<>();
            }
        } catch (Exception e) {
            e.printStackTrace();
            taskMap = new ConcurrentHashMap<>();
        }
    }

    /** 保存配置 */
    public static synchronized void saveTasks() {
        try (Writer writer = new FileWriter(CONFIG_FILE)) {
            writer.write(JSON.toJSONString(taskMap, JSONWriter.Feature.PrettyFormat));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** 添加任务 */
    public static void addTask(String qq, String time, String taskName) {
        taskMap.computeIfAbsent(qq, k -> Collections.synchronizedList(new ArrayList<>()));
        TaskInfo task = new TaskInfo();
        task.setTime(time);
        task.setTaskName(taskName);
        task.setExecuted(false);
        taskMap.get(qq).add(task);
        saveTasks();
    }

    /** 每分钟检查任务并执行 */
    public static void checkTasks() {
        String now = LocalTime.now().format(TIME_FORMAT);

        taskMap.forEach((qq, tasks) -> {
            synchronized (tasks) {
                for (TaskInfo task : tasks) {
                    if (!task.isExecuted() && now.equals(task.getTime())) {
                        System.out.println("QQ " + qq + " 执行任务：" + task.getTaskName());
                        task.setExecuted(true);
                        // 遍历当前所有 Bot，发送消息给对应 QQ 的群
                        BotFactory.getBots().values().forEach(bot -> {
                            try {

                                if((bot.getBotId()+"").equals(qq)){
                                    String taskName = task.getTaskName();
                                    BotConfig botConfig = bot.getBotConfig();
                                    Pattern pattern = Pattern.compile("@(\\d+)\\s*(.*)");
                                    Matcher matcher = pattern.matcher(taskName);

                                    if (matcher.matches()) {
                                        String atQQ = matcher.group(1);        // 3889001741
                                        String command = matcher.group(2);   // 灵石
                                        bot.getGroup(botConfig.getGroupId()).sendMessage(new MessageChain().at(atQQ).text(command));
                                    } else {
                                        bot.getGroup(botConfig.getGroupId()).sendMessage(new MessageChain().text(task.getTaskName()));
                                    }

                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }
                }
            }
        });
        saveTasks();
    }

    /** 每天凌晨重置任务执行状态 */
    public static void resetTasks() {
        taskMap.values().forEach(tasks -> {
            synchronized (tasks) {
                for (TaskInfo task : tasks) {
                    task.setExecuted(false);
                }
            }
        });
        saveTasks();
    }
}
