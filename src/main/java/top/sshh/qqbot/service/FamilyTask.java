//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.BotOfflineHandler;
import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.event.notice.BotOfflineEvent;
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
import top.sshh.qqbot.service.utils.Utils;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static top.sshh.qqbot.service.utils.Utils.isAtSelf;

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
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
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

    /**
     * 记录宗门任务状态机的关键状态变更，便于排查“设置已启用但没有继续执行”的问题。
     */
    private void setFamilyTaskStatus(Bot bot, BotConfig botConfig, int status, String reason) {
        int previousStatus = botConfig.getFamilyTaskStatus();
        botConfig.setFamilyTaskStatus(status);
        if (previousStatus != status) {
            logger.info("[宗门任务] 状态变更 botId={}, groupId={}, {} -> {}, reason={}, enableSectMission={}, sectMode={}, cultivationMode={}, stop={}, lastRefreshTime={}",
                    bot.getBotId(), botConfig.getGroupId(), previousStatus, status, reason,
                    botConfig.isEnableSectMission(), botConfig.getSectMode(), botConfig.getCultivationMode(),
                    botConfig.isStop(), botConfig.getLastRefreshTime());
        }
    }

    /**
     * 记录发给小小的宗门任务指令。返回值表示消息是否已交给发送流程，不代表小小已经回复。
     */
    private boolean sendFamilyTaskCommand(Bot bot, long groupId, String command, int status) {
        boolean accepted = Utils.sendGroupMessage(bot, groupId,
                (new MessageChain()).at("3889001741").text(command));
        logger.info("[宗门任务] 定时器发送命令 botId={}, groupId={}, status={}, command={}, accepted={}",
                bot.getBotId(), groupId, status, command, accepted);
        return accepted;
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

    @BotOfflineHandler
    public void handleBotOffline(Bot bot, BotOfflineEvent event) {
        logger.info("Bot offline: {}",  bot.getBotName()+event.getMessage());
        long botId = bot.getBotId();

        long maserId = bot.getBotConfig().getMasterQQ();

        for(Bot bot2 : BotFactory.getBots().values()) {
            BotConfig botConfig2 = bot2.getBotConfig();
            if (botConfig2.getMasterQQ() == maserId && bot2.getBotId() != botId) {
                bot2.sendPrivateMessage(maserId, (new MessageChain()).text("账号：" + botId +"(" + bot.getBotName()+ ")掉线啦\n\n" + event.getMessage()));
                break;
            }
        }

    }

    @Scheduled(
            cron = "*/5 * * * * *"
    )
    public void 宗门任务接取刷新() throws InterruptedException {
        BotFactory.getBots().values().forEach((bot) -> {
            BotConfig botConfig = bot.getBotConfig();
            if (!botConfig.isEnableSectMission()) {
                setFamilyTaskStatus(bot, botConfig, 0, "宗门任务开关关闭");
            } else {
                if (botConfig.isStop() && botConfig.getFamilyTaskStatus() != 0) {
                    logger.warn("[宗门任务] 检测到 stop=true，重置任务状态 botId={}, groupId={}, oldStatus={}",
                            bot.getBotId(), botConfig.getGroupId(), botConfig.getFamilyTaskStatus());
                    botConfig.setStop(false);
                    setFamilyTaskStatus(bot, botConfig, 0, "stop=true");
                }

                long groupId = botConfig.getGroupId();
//                if (botConfig.getTaskId() != 0L) {
//                    groupId = botConfig.getTaskId();
//                }

                Group group = bot.getGroup(groupId);
                if (group == null) {
                    if (botConfig.getFamilyTaskStatus() != 0) {
                        logger.warn("[宗门任务] 找不到任务群，跳过本次处理 botId={}, groupId={}, status={}",
                                bot.getBotId(), groupId, botConfig.getFamilyTaskStatus());
                    }
                    return;
                }
                switch (botConfig.getFamilyTaskStatus()) {
                    case 0:
                        return;
                    case 1:
                        sendFamilyTaskCommand(bot, group.getGroupId(), "宗门任务接取", 1);
                        return;
                    case 2:
                        if (botConfig.getLastRefreshTime() + 65000L < System.currentTimeMillis()) {
                            sendFamilyTaskCommand(bot, group.getGroupId(), "宗门任务刷新", 2);
                        }

                        return;
                    case 3:
                        if (botConfig.getLastRefreshTime() > System.currentTimeMillis()) {
                            return;
                        }

                        if (botConfig.getCultivationMode() == 2) {
                            sendFamilyTaskCommand(bot, group.getGroupId(), "出关", 3);
                        } else if (botConfig.getCultivationMode() == 3) {
                            sendFamilyTaskCommand(bot, group.getGroupId(), "宗门出关", 3);
                        }

                        try {
                            Thread.sleep(2000L);
                        } catch (InterruptedException var7) {
                        }

                        sendFamilyTaskCommand(bot, group.getGroupId(), "宗门任务完成", 3);
                        setFamilyTaskStatus(bot, botConfig, 1, "状态3已发送任务完成");

                        try {
                            Thread.sleep(2000L);
                        } catch (InterruptedException var6) {
                        }

                        if (botConfig.getCultivationMode() == 2) {
                            sendFamilyTaskCommand(bot, group.getGroupId(), "闭关", 3);
                        } else if (botConfig.getCultivationMode() == 3) {
                            sendFamilyTaskCommand(bot, group.getGroupId(), "宗门闭关", 3);
                        }

                        return;
                    case 4:
                        botConfig.setLastRefreshTime(System.currentTimeMillis() + 360000L);
                        if (botConfig.getCultivationMode() == 0) {
                            setFamilyTaskStatus(bot, botConfig, 0, "状态4且当前无修炼模式");
                        }

                        botConfig.setStartScheduled(true);
                        setFamilyTaskStatus(bot, botConfig, 3, "状态4进入出关完成流程");
                        return;
                    case 5:
                        sendFamilyTaskCommand(bot, group.getGroupId(), "宗门任务完成", 5);
                        setFamilyTaskStatus(bot, botConfig, 1, "状态5已发送任务完成");
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
        // NapCat 通常把正文直接注入 message，SnowLuma 的 Markdown 正文可能只在 messageChain 中。
        // 合并后仅用于状态匹配，原有 NapCat 文本行为保持不变。
        String taskMessage = StringUtils.defaultString(message);
        String messageChainText = Utils.getMessageText(messageChain);
        if (StringUtils.isNotBlank(messageChainText)) {
            taskMessage = taskMessage + "\n" + messageChainText;
        }
        boolean isAtSelf = isAtSelf(bot,group, taskMessage,xxGroupId);
        if (isAtSelf) {
            if (taskMessage.contains("道友目前还没有宗门任务")) {
                setFamilyTaskStatus(bot, botConfig, 1, "小小回复：当前没有宗门任务");
            }

            if (taskMessage.contains("今日无法再获取宗门任务")) {
                setFamilyTaskStatus(bot, botConfig, 0, "小小回复：今日无法再获取宗门任务");
                TestService.proccessCultivation(group);
                groupManager.setZonMenTaskFinished(bot);
//                bot.getGroup(xxGroupId).sendMessage(new MessageChain().text("今日宗门任务"))
            }

            if (taskMessage.contains("道友大战一番") && taskMessage.contains("获得修为") && taskMessage.contains("宗门建设度增加")) {
                setFamilyTaskStatus(bot, botConfig, 1, "小小回复：宗门任务战斗完成");
            }
            if (taskMessage.contains("恭喜道友完成宗门任务")) {
                setFamilyTaskStatus(bot, botConfig, 1, "小小回复：宗门任务完成");
            }

            if (taskMessage.contains("出门做任务") && taskMessage.contains("不扣你任务次数")) {
                if (botConfig.getCultivationMode() == 0) {
                    setFamilyTaskStatus(bot, botConfig, 0, "小小回复：任务无需出门且当前无修炼模式");
                }else{
                    botConfig.setLastRefreshTime(System.currentTimeMillis() + 360000L);
                    setFamilyTaskStatus(bot, botConfig, 3, "小小回复：任务需要出门执行");
                }

            }

            if (taskMessage.contains("时间还没到") && taskMessage.contains("歇会歇会")) {
                botConfig.setLastRefreshTime(System.currentTimeMillis() + 60000L);
            }


            if (botConfig.getSectMode() == 1) {
                if (taskMessage.contains("邪修抢夺灵石") || taskMessage.contains("私自架设小型窝点") || taskMessage.contains("宗门密令") ||
                        taskMessage.contains("除魔令")) {
                    botConfig.setLastRefreshTime(System.currentTimeMillis());
                    setFamilyTaskStatus(bot, botConfig, 3, "邪修查抄模式：任务可执行");
                }

                if (taskMessage.contains("被追打催债") ||
                        taskMessage.contains("坊市通告") ||
                        taskMessage.contains("九转仙丹") ||
                         taskMessage.contains("红尘寻宝") ||
                        taskMessage.contains("仗义疏财")
                        || taskMessage.contains("为宗门购买一些") || taskMessage.contains("请道友下山购买")) {
                    logger.info("[宗门任务] 识别为需要刷新任务 botId={}, taskMessage={}", bot.getBotId(), taskMessage);
                    setFamilyTaskStatus(bot, botConfig, 2, "邪修查抄模式：跳过当前任务");
                    botConfig.setLastRefreshTime(System.currentTimeMillis());
                }
            }

            if (botConfig.getSectMode() == 2 && (taskMessage.contains("邪修抢夺灵石") ||
                    taskMessage.contains("私自架设小型窝点") || taskMessage.contains("被追打催债")
                    || taskMessage.contains("请道友下山购买") ||
                    taskMessage.contains("为宗门购买一些") ||
                    taskMessage.contains("宗门密令") ||
                    taskMessage.contains("除魔令") ||
                    taskMessage.contains("红尘寻宝") ||
                    taskMessage.contains("坊市通告") ||
                    taskMessage.contains("九转仙丹") ||
                    taskMessage.contains("仗义疏财"))) {
                logger.info("[宗门任务] 识别为可执行任务 botId={}, taskMessage={}", bot.getBotId(), taskMessage);
                botConfig.setLastRefreshTime(System.currentTimeMillis());
                setFamilyTaskStatus(bot, botConfig, 5, "所有任务模式：当前任务自动完成");
            }
        }


    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 妖塔状态(Bot bot, Group group, Member member, MessageChain chain, String msg, Integer msgId) {
        BotConfig botConfig = bot.getBotConfig();
        if (Utils.isAtSelf(bot,group, msg,xxGroupId)) {
            if (msg.contains("气血") && msg.contains("真元") && msg.contains("道号") && (botConfig.getChallengeMode() == 11 || botConfig.getChallengeMode() == 21)) {
                // SnowLuma 下消息为 markdown 卡片，剥离链接避免道号等字段携带 mqqapi 语法
                String[] lines = Utils.stripMarkdownLink(msg).split("\\n");
                String daoHao = "";
                double currentHP = (double)0.0F;
                double maxHP = (double)0.0F;
                double zhenYuan = (double)0.0F;
                boolean hpFound = false;
                Pattern daoHaoPattern = Pattern.compile("道号\\s*：\\s*(\\S+)");
//                Pattern hpPattern = Pattern.compile("气血\\s*:\\s*([\\d\\.]+)(?:亿)?\\s*/\\s*([\\d\\.]+)(?:亿)?");
                Pattern hpPattern = Pattern.compile("气血\\s*[:：]\\s*([\\d\\.]+)(?:[亿万兆京]?)\\s*/\\s*([\\d\\.]+)(?:[亿万兆京]?)");
                Pattern zyPattern = Pattern.compile("真元\\s*:\\s*([\\d\\.]+)%");

                for(String line : lines) {
                    Matcher daoHaoMatcher = daoHaoPattern.matcher(line);
                    if (daoHaoMatcher.find()) {
                        daoHao = daoHaoMatcher.group(1);
                    }

                    Matcher hpMatcher = hpPattern.matcher(line);
                    if (hpMatcher.find()) {
                        currentHP = this.parseValue(hpMatcher.group(1));
                        maxHP = this.parseValue(hpMatcher.group(2));
                        hpFound = true;
                    }

                    Matcher zyMatcher = zyPattern.matcher(line);
                    if (zyMatcher.find()) {
                        zhenYuan = Double.parseDouble(zyMatcher.group(1));
                    }
                }

                if (!hpFound) {
                    System.out.println("未找到气血数据");
                    return;
                }

                double hpPercentage = maxHP > (double)0.0F ? currentHP / maxHP * (double)100.0F : (double)0.0F;
                if (hpPercentage > 0.8 && zhenYuan > (double)300.0F) {
                    try {
                        Thread.sleep(2000L);
                        Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 挑战九层妖塔"));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    if (botConfig.getChallengeMode() == 11) {
                        botConfig.setChallengeMode(12);
                    } else {
                        botConfig.setChallengeMode(22);
                    }
                } else {
                    xiuXi(bot, group);
                }
            }

            if ((botConfig.getChallengeMode() == 12 || botConfig.getChallengeMode() == 22) && msg.contains("第") && msg.contains("层")) {
                if (msg.contains("第2层")) {
                    try {
                        Thread.sleep(2000L);
                        Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 挑战九层妖塔强行挑战"));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                } else if (!msg.contains("第3层") && !msg.contains("第7层") && !msg.contains("第8层")) {
                    if (!msg.contains("第9层") && !msg.contains("已经没有道友可以和无上仙尊匹敌")) {
                        if (!msg.contains("第1层") && !msg.contains("第4层") && !msg.contains("第5层") && !msg.contains("第6层")) {
                            return;
                        }
                        try {
                            Thread.sleep(2000L);
                            Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 挑战九层妖塔"));
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                    } else {
                        if (botConfig.getCultivationMode() == 2) {
                            Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 闭关"));
                        } else if (botConfig.getCultivationMode() == 3) {
                            Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 宗门闭关"));
                        }

                        if (botConfig.getChallengeMode() == 12) {
                            botConfig.setChallengeMode(1);
                        } else {
                            botConfig.setChallengeMode(2);
                        }

                        botConfig.setCommand("");
                    }
                } else if (botConfig.getChallengeMode() == 22) {
                    xiuXi(bot, group);
                } else {
                    try {
                        Thread.sleep(2000L);
                        Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 挑战九层妖塔"));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            if ((botConfig.getChallengeMode() == 12 || botConfig.getChallengeMode() == 22) && msg.contains("大能对你提交的答案很满意，让你顺利的通过了")) {
                try {
                    Thread.sleep(2000L);
                    Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 挑战九层妖塔"));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            if ((botConfig.getChallengeMode() == 12 || botConfig.getChallengeMode() == 22) && msg.contains("道友的上一条指令还没执行完，稍等一会！")) {
                scheduler.schedule(() -> Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 挑战九层妖塔")), 1L, TimeUnit.MINUTES);
            }

            if ((botConfig.getChallengeMode() == 13 || botConfig.getChallengeMode() == 23) && (msg.contains("闭关结束") || msg.contains("闭关结算") || msg.contains("出关捷报"))) {
                if (botConfig.getCultivationMode() == 2) {
                    Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 闭关"));
                } else if (botConfig.getCultivationMode() == 3) {
                    Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 宗门闭关"));
                    botConfig.setCommand(" 宗门闭关");
                }

                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException var27) {
                }

                try {
                    Thread.sleep(2000L);
                    Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 挑战九层妖塔"));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                if (botConfig.getChallengeMode() == 13) {
                    botConfig.setChallengeMode(12);
                } else {
                    botConfig.setChallengeMode(22);
                }
            }

            if ((botConfig.getChallengeMode() == 12 || botConfig.getChallengeMode() == 22 || botConfig.getChallengeMode() == 13 || botConfig.getChallengeMode() == 23) && msg.contains("宗门系统繁忙，请稍后再试。")) {
                long delaySeconds = ThreadLocalRandom.current().nextLong(5L, 60L);
                String text = botConfig.getCommand();
                scheduler.schedule(() -> Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(text)), delaySeconds, TimeUnit.SECONDS);
            }

            if ((botConfig.getChallengeMode() == 1 || botConfig.getChallengeMode() == 2) && msg.contains("宗门系统繁忙，请稍后再试。")) {
                long delaySeconds = ThreadLocalRandom.current().nextLong(5L, 60L);
                scheduler.schedule(() -> Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 宗门闭关")), delaySeconds, TimeUnit.SECONDS);
            }
            if ((botConfig.getChallengeMode() == 1 || botConfig.getChallengeMode() == 2) && msg.contains("登上第九层")) {
                if(xxGroupId>0){
                    bot.sendGroupMessage(xxGroupId, (new MessageChain()).text("已完成本周妖塔挑战"));
                }


            }
        }

    }

    private double parseValue(String valueStr) {
        return valueStr.contains("亿") ? Double.parseDouble(valueStr.replace("亿", "")) * (double)1.0E8F : Double.parseDouble(valueStr);
    }

    private static void xiuXi(Bot bot, Group group) {
        BotConfig botConfig = bot.getBotConfig();
        if (botConfig.getCultivationMode() == 2) {
            Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 闭关"));
            if (botConfig.getChallengeMode() == 11) {
                botConfig.setChallengeMode(13);
            } else {
                botConfig.setChallengeMode(23);
            }

            scheduler.schedule(() -> Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 出关")), 6L, TimeUnit.MINUTES);
        } else if (botConfig.getCultivationMode() == 3) {
            Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 宗门闭关"));
            botConfig.setCommand(" 宗门闭关");
            if (botConfig.getChallengeMode() == 11) {
                botConfig.setChallengeMode(13);
            } else {
                botConfig.setChallengeMode(23);
            }

            scheduler.schedule(() -> Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text(" 宗门出关")), 6L, TimeUnit.MINUTES);
            botConfig.setCommand(" 宗门出关");
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 灵田领取结果(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isAtSelf = isAtSelf(bot,group,message,xxGroupId);
        if (isAtSelf) {
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
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("直接突破"));
            } else if (message.contains("道友成功收获药材")||(message.contains("道友本次采集成果") && message.contains("收获药材"))) {
                remindMap.put(bot.getBotId(), 9223372036854175807L);
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("灵田结算"));
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
                    Utils.sendGroupMessage(bot, groupId, (new MessageChain()).at("3889001741").text("灵田结算"));
                    continue;
                }

                if (remindMap.get(bot.getBotId()) + 60000L < System.currentTimeMillis()) {
                    logger.info(String.format("bot.getBotId()==%d", remindMap.get(bot.getBotId())));
//                    botConfig.setLastExecuteTime(9223372036854175807L);
                    remindMap.put(bot.getBotId(), 9223372036854175807L);
                    Utils.sendGroupMessage(bot, groupId, (new MessageChain()).at("3889001741").text("灵田结算"));

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
                boolean isAtSelf = isAtSelf(bot,group,message,xxGroupId);
                if (isAtSelf && message.contains("逆天之行") && message.contains("新的灵根为")) {
                    if (!message.contains("异世界之力") && !message.contains("机械核心")) {
                        Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("重入仙途"));
                    } else {
                        botConfig.setStartAutoLingG(false);
                    }
                }
            }
        }

    }


    List<String> gongFaList = new ArrayList<>(Arrays.asList("诛仙剑", "斩龙刃", "焚天烈焰掌", "火龙珠", "聚气诀", "烈火焚天", "土墙术", "血煞功", "金钟罩",
    "寒冰诀", "布甲", "聚灵珠", "金钟罩", "乾坤袋", "精铁长剑", "铁骨符", "水龙珠", "寒冰诀", "破军杀", "聚气诀", "土墙术", "锐金石",
     "精钢剑", "血玉坠", "固元腰带", "水龙珠", "凝神香", "九天玄甲", "万木复苏", "蓄力戒", "大地震颤", "抗蚀甲片", "养气玉佩", "五行灵盘", 
     "疾风靴"));
    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 苍穹秘境(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isAtSelf = isAtSelf(bot,group,message,xxGroupId);
        if (isAtSelf && botConfig.isEnableAutoCqMj()) {
            if (message.contains("可选择的路径") && message.contains("继续前进")) {
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("继续前进"));
            }else if (message.contains("可选择的路径") && message.contains("路径1")) {
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("选择路径1"));
            }else if (message.contains("基础剑诀")) {
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("释放功法基础剑诀"));
            }else if (message.contains("学习功法或选择奖励")) {
                for (String gongFa : gongFaList) {
                    if (message.contains(gongFa)) {
                        Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("战利品选择" + gongFa));
                        break; // 使用 break 真正跳出循环
                    }
                }
            }else if (message.contains("进入了事件房间") && message.contains("强行破阵")) {
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("事件选择 强行破阵"));
            }else if (message.contains("可选择的路径") && message.contains("走右边")) {
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("走右边"));
            }else if (message.contains("进入了事件房间") && message.contains("离开")) {
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("事件选择 离开"));
            }else if (message.contains("离开商店")) {
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("离开商店"));
            }else if (message.contains("打开宝箱")) {
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("打开宝箱"));
            }else if (message.contains("休息") && message.contains("灵气充沛")) {
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("休息"));
            }else if (message.contains("进入了事件房间") && message.contains("反对")) {
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("事件选择 反对"));
            }else if (message.contains("你已经在苍穹秘境")) {
                Utils.sendGroupMessage(bot, group.getGroupId(), (new MessageChain()).at("3889001741").text("查看苍穹秘境地图"));
            }else if (message.contains("存活层数") && message.contains("陨落")) {
                botConfig.setEnableAutoCqMj(false);
            }
        }

    }

    @Scheduled(cron = "0 34 4 * * *")
    public void 签到() throws InterruptedException {
        Iterator var1 = BotFactory.getBots().values().iterator();

        while (var1.hasNext()) {
            Bot bot = (Bot) var1.next();
            if (bot.getBotConfig().isEnableAutoTask()) {
                try {
                    Utils.sendGroupMessage(bot, bot.getBotConfig().getGroupId(), (new MessageChain().at("3889001741")).text("修仙签到"));
                } catch (Exception e) {
                    logger.error("定时发送消息失败", e);
                }
            }

        }
    }

    @Scheduled(cron = "0 35 4 * * *")
    public void 宗门丹药领取() throws InterruptedException {
        Iterator var1 = BotFactory.getBots().values().iterator();

        while (var1.hasNext()) {
            Bot bot = (Bot) var1.next();
            if (bot.getBotConfig().isEnableAutoTask()) {
                try {
                    Utils.sendGroupMessage(bot, bot.getBotConfig().getGroupId(), (new MessageChain().at("3889001741")).text("宗门丹药领取"));
                } catch (Exception e) {
                    logger.error("定时发送消息失败", e);
                }
            }

        }
    }

    // @Scheduled(cron = "0 35 12 * * *" ,zone = "Asia/Shanghai")
    // public void 准备秘境() throws InterruptedException {
    //     Iterator var1 = BotFactory.getBots().values().iterator();

    //     while (var1.hasNext()) {
    //         Bot bot = (Bot) var1.next();
    //         if (bot.getBotConfig().isEnableAutoTask()) {
    //             try {
    //                 Utils.sendGroupMessage(bot, bot.getBotConfig().getGroupId(), (new MessageChain()).text("开始自动秘境"));
    //             } catch (Exception e) {
    //                 logger.error("定时发送消息失败", e);
    //             }
    //         }

    //     }
    // }

    @Scheduled(
            cron = "0 10 20 ? * SUN",
            zone = "Asia/Shanghai"
    )
    public void 挑战妖塔() throws InterruptedException {
        for(Bot bot : BotFactory.getBots().values()) {
            BotConfig config = bot.getBotConfig();
            if (config.getChallengeMode() == 1 || config.getChallengeMode() == 2) {
                Utils.sendGroupMessage(bot, config.getGroupId(), (new MessageChain()).at("3889001741").text(" 我的状态"));
                if (config.getChallengeMode() == 1) {
                    config.setChallengeMode(11);
                } else {
                    config.setChallengeMode(21);
                }
            }
        }

    }


    @Scheduled(cron = "0 36 4 * * *")
    public void 开始宗门任务() throws InterruptedException {
        Iterator var1 = BotFactory.getBots().values().iterator();

        while (var1.hasNext()) {
            Bot bot = (Bot) var1.next();
            if (bot.getBotConfig().isEnableAutoTask()) {
                try {
                    bot.getBotConfig().setStop(false);
                    Utils.sendGroupMessage(bot, bot.getBotConfig().getGroupId(), (new MessageChain().at("3889001741")).text("宗门任务接取"));
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
                    Utils.sendGroupMessage(bot, bot.getBotConfig().getGroupId(), (new MessageChain()).text("开始自动悬赏"));
                } catch (Exception e) {
                    logger.info("开始悬赏任务失败", e);
                }
            }

        }
    }


}
