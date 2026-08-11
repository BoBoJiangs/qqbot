package top.sshh.qqbot.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.alibaba.fastjson2.JSONWriter;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import top.sshh.qqbot.service.utils.Utils;
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
    public static void addTask(String qq, String time, String taskName, Long group, Integer intervalHours) {
        taskMap.computeIfAbsent(qq, k -> Collections.synchronizedList(new ArrayList<>()));
        TaskInfo task = new TaskInfo();
        task.setTime(time);
        task.setTaskName(taskName);
        task.setExecuted(false);
        task.setExecuteGroup(group);
        task.setIntervalHours(intervalHours);
        task.setLastExecuteTime(0L);
        taskMap.get(qq).add(task);
        saveTasks();
    }

    public static boolean removeTask(String qq, String time) {
        List<TaskInfo> tasks = taskMap.get(qq);
        if (tasks == null) return false;
        return tasks.removeIf(task -> task.getTime().equals(time));
    }


    /** 每分钟检查任务并执行 */
    public static void checkTasks() {
        String now = LocalTime.now().format(TIME_FORMAT);
        long currentMillis = System.currentTimeMillis();

        taskMap.forEach((qq, tasks) -> {
            synchronized (tasks) {
                for (TaskInfo task : tasks) {
                    boolean shouldExecute = false;
                    if (task.getIntervalHours() == null || task.getIntervalHours() <= 0) {
                        // 每天模式：按 HH:mm 匹配，executed 防止当天重复
                        if (!task.isExecuted() && now.equals(task.getTime())) {
                            shouldExecute = true;
                        }
                    } else {
                        // 间隔模式
                        if (task.getLastExecuteTime() <= 0L) {
                            // 首次执行：按 HH:mm 触发
                            if (now.equals(task.getTime())) {
                                shouldExecute = true;
                            }
                        } else {
                            // 后续：到点（上次执行 + 间隔）即触发
                            long next = task.getLastExecuteTime() + task.getIntervalHours() * 3600_000L;
                            if (currentMillis >= next) {
                                shouldExecute = true;
                            }
                        }
                    }

                    if (shouldExecute) {
                        System.out.println("QQ " + qq + " 执行任务：" + task.getTaskName());
                        task.setExecuted(true);
                        task.setLastExecuteTime(currentMillis);
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
                                        Utils.sendGroupMessage(bot, task.getExecuteGroup(), new MessageChain().at(atQQ).text(command));
                                    } else {
                                        Utils.sendGroupMessage(bot, task.getExecuteGroup(), new MessageChain().text(task.getTaskName()));
                                    }
                                    Thread.sleep(1000);
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

    /** 每天凌晨重置任务执行状态（仅对每天模式生效） */
    public static void resetTasks() {
        taskMap.values().forEach(tasks -> {
            synchronized (tasks) {
                for (TaskInfo task : tasks) {
                    if (task.getIntervalHours() == null || task.getIntervalHours() <= 0) {
                        // 每天模式：重置已执行状态
                        task.setExecuted(false);
                    }
                    // 间隔模式：不重置，由 checkTasks 按时间戳持续触发
                }
            }
        });
        saveTasks();
    }
}
