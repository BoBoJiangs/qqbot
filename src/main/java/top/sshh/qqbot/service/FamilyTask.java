//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.QQBotConfig;
import top.sshh.qqbot.data.RemindTime;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FamilyTask {
    private static final Logger logger = LoggerFactory.getLogger(FamilyTask.class);
    public static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss") {
        {
            this.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        }
    };
    private static final String FILE_PATH = "./cache/field_remind_data.ser";
    Map<Long, Long> remindMap = new ConcurrentHashMap();
    @Value("${xxGroupId:0}")
    private long xxGroupId;
    @Autowired
    private GroupManager groupManager;

    public FamilyTask() {
    }

    @PostConstruct
    public void init() {
        this.loadTasksFromFile();
        logger.info("已从本地加载{}个灵田提醒任务", this.remindMap.size(), this.remindMap.size());
    }

    @Scheduled(
            fixedDelay = 43200000L,
            initialDelay = 600000L
    )
    public void autoSaveTasks() {
        this.saveTasksToFile();
    }

    public synchronized void saveTasksToFile() {
        try {
            ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(Paths.get(FILE_PATH)));

            try {
                Iterator<Map.Entry<Long, Long>> iterator = remindMap.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Long, Long> entry = iterator.next();
                    if (entry.getValue() == 9223372036854175807L) {
                        logger.info("移除异常时间的灵田提醒：{}", entry.getKey());
                        iterator.remove();
                    }
                }
                Map<String, Object> data = new HashMap();
                data.put("灵田提醒", this.remindMap);
                oos.writeObject(data);
            } catch (Throwable var5) {

            }

            oos.close();
        } catch (Exception e) {
            logger.info("任务数据保存失败：", e);
        }

        logger.info("正在同步 {} 个灵田提醒任务", this.remindMap.size());
    }

    private synchronized void loadTasksFromFile() {
        File dataFile = new File("./cache");
        if (dataFile.exists()) {
            try {
                ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(new File(FILE_PATH).toPath()));
                Map<String, Object> data = (Map) ois.readObject();
                this.remindMap = (ConcurrentHashMap) data.get("灵田提醒");

                ois.close();
            } catch (Exception var4) {
                Exception e = var4;
                logger.info("任务数据加载失败：", e);
            }
        } else {
            dataFile.mkdirs();
            logger.info("未找到序列化文件: {}", "./cache/field_remind_data.ser");
        }

    }

    @Scheduled(
            cron = "*/5 * * * * *"
    )
    public void 宗门任务接取刷新() throws InterruptedException {
        BotFactory.getBots().values().forEach((bot) -> {
            BotConfig botConfig = bot.getBotConfig();
            if (!botConfig.isEnableSectMission()) {
                botConfig.setFamilyTaskStatus(0);
            } else {
                if (botConfig.isStop() && botConfig.getFamilyTaskStatus() != 0) {
                    botConfig.setStop(false);
                    botConfig.setFamilyTaskStatus(0);
                }

                long groupId = botConfig.getGroupId();
//                if (botConfig.getTaskId() != 0L) {
//                    groupId = botConfig.getTaskId();
//                }

                Group group = bot.getGroup(groupId);
                switch (botConfig.getFamilyTaskStatus()) {
                    case 0:
                        return;
                    case 1:
                        group.sendMessage((new MessageChain()).at("3889001741").text("宗门任务接取"));
                        return;
                    case 2:
                        if (botConfig.getLastRefreshTime() + 65000L < System.currentTimeMillis()) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("宗门任务刷新"));
                        }

                        return;
                    case 3:
                        if (botConfig.getLastRefreshTime() > System.currentTimeMillis()) {
                            return;
                        }

                        if (botConfig.getCultivationMode() == 2) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("出关"));
                        } else if (botConfig.getCultivationMode() == 3) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("宗门出关"));
                        }

                        try {
                            Thread.sleep(2000L);
                        } catch (InterruptedException var7) {
                        }

                        group.sendMessage((new MessageChain()).at("3889001741").text("宗门任务完成"));
                        botConfig.setFamilyTaskStatus(1);

                        try {
                            Thread.sleep(2000L);
                        } catch (InterruptedException var6) {
                        }

                        if (botConfig.getCultivationMode() == 2) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("闭关"));
                        } else if (botConfig.getCultivationMode() == 3) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("宗门闭关"));
                        }

                        return;
                    case 4:
                        botConfig.setLastRefreshTime(System.currentTimeMillis() + 360000L);
                        if (botConfig.getCultivationMode() == 0) {
                            botConfig.setFamilyTaskStatus(0);
                        }

                        botConfig.setStartScheduled(true);
                        botConfig.setFamilyTaskStatus(3);
                        return;
                    case 5:
                        group.sendMessage((new MessageChain()).at("3889001741").text("宗门任务完成"));
                        botConfig.setFamilyTaskStatus(1);
                        return;
                }
            }

        });
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 宗门任务状态管理(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isAtSelf = isAtSelf(message,bot,group);
        boolean isGroup = group.getGroupId() == botConfig.getGroupId();
        if (isAtSelf && isGroup) {
            if (message.contains("道友目前还没有宗门任务")) {
                botConfig.setFamilyTaskStatus(1);
            }

            if (message.contains("今日无法再获取宗门任务")) {
                botConfig.setFamilyTaskStatus(0);
                TestService.proccessCultivation(group);
                groupManager.setZonMenTaskFinished(bot);
//                bot.getGroup(xxGroupId).sendMessage(new MessageChain().text("今日宗门任务"))
            }

            if (message.contains("道友大战一番") && message.contains("获得修为") && message.contains("宗门建设度增加")) {
                botConfig.setFamilyTaskStatus(1);
            }
            if (message.contains("恭喜道友完成宗门任务")) {
                botConfig.setFamilyTaskStatus(1);
            }

            if (message.contains("出门做任务") && message.contains("不扣你任务次数")) {
                if (botConfig.getCultivationMode() == 0) {
                    botConfig.setFamilyTaskStatus(0);
                }else{
                    botConfig.setLastRefreshTime(System.currentTimeMillis() + 360000L);
                    botConfig.setFamilyTaskStatus(3);
                }

            }

            if (message.contains("时间还没到") && message.contains("歇会歇会")) {
                botConfig.setLastRefreshTime(System.currentTimeMillis() + 60000L);
            }


            if (botConfig.getSectMode() == 1) {
                if (message.contains("邪修抢夺灵石") || message.contains("私自架设小型窝点") || message.contains("宗门密令") ||
                        message.contains("除魔令")) {
                    botConfig.setLastRefreshTime(System.currentTimeMillis());
                    botConfig.setFamilyTaskStatus(3);
                }

                if (message.contains("被追打催债") ||
                        message.contains("坊市通告") ||
                        message.contains("九转仙丹") ||
                        message.contains("仗义疏财")
                        || message.contains("为宗门购买一些") || message.contains("请道友下山购买")) {
                    botConfig.setFamilyTaskStatus(2);
                    botConfig.setLastRefreshTime(System.currentTimeMillis());
                }
            }

            if (botConfig.getSectMode() == 2 && (message.contains("邪修抢夺灵石") ||
                    message.contains("私自架设小型窝点") || message.contains("被追打催债")
                    || message.contains("请道友下山购买") ||
                    message.contains("为宗门购买一些") ||
                    message.contains("宗门密令") ||
                    message.contains("除魔令") ||
                    message.contains("坊市通告") ||
                    message.contains("九转仙丹") ||
                    message.contains("仗义疏财"))) {
                botConfig.setLastRefreshTime(System.currentTimeMillis());
                botConfig.setFamilyTaskStatus(5);
            }
        }


    }
    private boolean isAtSelf(String message, Bot bot,Group group) {
//        String botName = bot.getBotName();
//        String cardName = group.getMember(bot.getBotId()).getCard();
//        if(StringUtils.isNotBlank(cardName)){
//            botName = cardName;
//        }
//        return message.contains("@" + bot.getBotId()) || message.contains("@" + botName);
        return true;
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 灵田领取结果(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId();
        boolean isAtSelf = isAtSelf(message,bot,group);
        if (isGroup && isAtSelf) {
            if (message.contains("灵田还不能收取") || message.contains("道友的灵田灵气未满，尚需孕育")) {
                String[] parts = message.split("：|小时");
                if (parts.length < 2) {
                    group.sendMessage((new MessageChain()).text("输入格式不正确，请确保格式为 '下次收取时间为：XX.XX小时"));
                    return;
                }

                try {
                    double hours = Double.parseDouble(parts[1].trim());
                    long remindTime = (long) ((double) System.currentTimeMillis() + hours * 60.0 * 60.0 * 1000.0);
                    remindMap.put(bot.getBotId(), remindTime);
                    group.sendMessage((new MessageChain()).text("下次收取时间为：" + sdf.format(new Date(remindTime))));
                } catch (Exception e) {
                    logger.error("灵田收取时间失败", e);
                }
//                bot.getBotConfig().setLastExecuteTime(remindTime);
//                group.sendMessage((new MessageChain()).text("下次收取时间为：" + sdf.format(new Date(remindTime))));
            } else if (message.contains("还没有洞天福地")) {
                System.out.println(LocalDateTime.now() + " " + group.getGroupName() + " 收到灵田领取结果,还没有洞天福地");
//                bot.getBotConfig().setLastExecuteTime(9223372036854175807L);
                remindMap.put(bot.getBotId(), 9223372036854175807L);
            } else if (message.contains("本次修炼到达上限")) {
                group.sendMessage((new MessageChain()).at("3889001741").text("直接突破"));
            } else if (message.contains("道友成功收获药材")||(message.contains("道友本次采集成果") && message.contains("收获药材"))) {
                remindMap.put(bot.getBotId(), 9223372036854175807L);
                group.sendMessage((new MessageChain()).at("3889001741").text("灵田结算"));
            }
        }

    }

    @Scheduled(
            cron = "0 */1 * * * *"
    )
    public void 灵田领取() throws InterruptedException {
        Iterator var1 = BotFactory.getBots().values().iterator();

        while (var1.hasNext()) {
            Bot bot = (Bot) var1.next();
            BotConfig botConfig = bot.getBotConfig();

            if (botConfig.isEnableAutoField()) {

                long groupId = botConfig.getGroupId();
//                if (botConfig.getTaskId() != 0L) {
//                    groupId = botConfig.getTaskId();
//                }


                if (remindMap.get(bot.getBotId()) == null) {
                    logger.info("bot.getBotId()==" + remindMap.get(bot.getBotId()));
                    remindMap.put(bot.getBotId(), 9223372036854175807L);
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("灵田结算"));
                    continue;
                }

                if (remindMap.get(bot.getBotId()) + 60000L < System.currentTimeMillis()) {
                    logger.info(String.format("bot.getBotId()==%d", remindMap.get(bot.getBotId())));
//                    botConfig.setLastExecuteTime(9223372036854175807L);
                    remindMap.put(bot.getBotId(), 9223372036854175807L);
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("灵田结算"));

                }


            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 一键刷灵根(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        if (botConfig.isStartAutoLingG()) {
            if (message.contains("你的灵石还不够呢")) {
                botConfig.setStartAutoLingG(false);
            } else {
                boolean isAtSelf = isAtSelf(message,bot,group);
                if (isAtSelf && message.contains("逆天之行") && message.contains("新的灵根为")) {
                    if (!message.contains("异世界之力") && !message.contains("机械核心")) {
                        group.sendMessage((new MessageChain()).at("3889001741").text("重入仙途"));
                    } else {
                        botConfig.setStartAutoLingG(false);
                    }
                }
            }
        }

    }

    @Scheduled(cron = "0 30 7 * * *")
    public void 签到() throws InterruptedException {
        Iterator var1 = BotFactory.getBots().values().iterator();

        while (var1.hasNext()) {
            Bot bot = (Bot) var1.next();
            if (bot.getBotConfig().isEnableAutoTask()) {
                try {
                    bot.getGroup(bot.getBotConfig().getGroupId()).sendMessage((new MessageChain().at("3889001741")).text("修仙签到"));
                } catch (Exception e) {
                    logger.error("定时发送消息失败", e);
                }
            }

        }
    }

    @Scheduled(cron = "0 35 7 * * *")
    public void 宗门丹药领取() throws InterruptedException {
        Iterator var1 = BotFactory.getBots().values().iterator();

        while (var1.hasNext()) {
            Bot bot = (Bot) var1.next();
            if (bot.getBotConfig().isEnableAutoTask()) {
                try {
                    bot.getGroup(bot.getBotConfig().getGroupId()).sendMessage((new MessageChain().at("3889001741")).text("宗门丹药领取"));
                } catch (Exception e) {
                    logger.error("定时发送消息失败", e);
                }
            }

        }
    }

    @Scheduled(cron = "0 35 12 * * *")
    public void 准备秘境() throws InterruptedException {
        Iterator var1 = BotFactory.getBots().values().iterator();

        while (var1.hasNext()) {
            Bot bot = (Bot) var1.next();
            if (bot.getBotConfig().isEnableAutoTask()) {
                try {
                    bot.getGroup(bot.getBotConfig().getGroupId()).sendMessage((new MessageChain()).text("开始自动秘境"));
                } catch (Exception e) {
                    logger.error("定时发送消息失败", e);
                }
            }

        }
    }


    @Scheduled(cron = "0 36 7 * * *")
    public void 开始宗门任务() throws InterruptedException {
        Iterator var1 = BotFactory.getBots().values().iterator();

        while (var1.hasNext()) {
            Bot bot = (Bot) var1.next();
            if (bot.getBotConfig().isEnableAutoTask()) {
                try {
                    bot.getGroup(bot.getBotConfig().getGroupId()).sendMessage((new MessageChain().at("3889001741")).text("宗门任务接取"));
                } catch (Exception e) {
                    logger.error("开始宗门任务失败", e);
                }
            }

        }
    }

    @Scheduled(cron = "0 5 8 * * *")
    public void 开始悬赏任务() throws InterruptedException {
        Iterator var1 = BotFactory.getBots().values().iterator();

        while (var1.hasNext()) {
            Bot bot = (Bot) var1.next();
            if (bot.getBotConfig().isEnableAutoTask()) {
                try {
                    bot.getGroup(bot.getBotConfig().getGroupId()).sendMessage((new MessageChain()).text("开始自动悬赏"));
                } catch (Exception e) {
                    logger.info("开始悬赏任务失败", e);
                }
            }

        }
    }


}
