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
import com.zhuangxv.bot.message.Message;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import top.sshh.qqbot.data.MessageNumber;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.data.RemindTime;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GroupManager {
    private static final Logger logger = LoggerFactory.getLogger(GroupManager.class);
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    Map<String, RemindTime> mjXslmap = new ConcurrentHashMap();
    Map<String, RemindTime> ltmap = new ConcurrentHashMap();
    private static final ForkJoinPool customPool = new ForkJoinPool(20);
    private static final List<String> MJ_TEXT_LIST = Arrays.asList(" 【秘境结算提醒】秘境试炼已结束！此番奇遇定让您感悟大道，快查看收获，或许有突破境界的机缘！",
            "【秘境结算提醒】叮！秘境探索结算中，本次收获：四库全书 1 本，戏书 1 本，以及队友的嫌弃三连～",
            "【秘境结算提醒】秘境探索时间已到！您在秘境中历经磨砺，珍贵法宝与上古传承正等待您开启！",
            "【秘境结算提醒】秘境之行圆满收官！天地灵气凝聚的机缘已化作奖励，助您打破修为桎梏！",
            "【秘境结算提醒】秘境探索结束，本次成就：迷路 10 次，摔进陷阱 3 次，荣获‘秘境路痴’称号！",
            "【秘境结算提醒】秘境时限已至！您探索秘境的收获即将揭晓，说不定能获得改变命运的仙缘！",
            "【秘境结算提醒】秘境任务已结算！您在秘境中的探索成果斐然，丰厚奖励助您早日飞升！",
            "【秘境结算提醒】秘境时间到！您在秘境中积累的气运，已化作珍贵资源，快来开启这份修仙大礼！");
    private static final List<String> XSL_TEXT_LIST = Arrays.asList("【悬赏结算提醒】悬赏令时限已至！斩妖除魔的功绩化作海量灵石，速来领取这份天道酬勤的嘉奖！",
            "【悬赏结算提醒】通缉妖邪的期限已到，您的英勇战绩已被悬赏阁收录，丰厚奖赏静待仙友笑纳！",
            "【悬赏结算提醒】悬赏任务圆满收官！此番降魔除祟之举，必能助您在修仙路上再添助力，速来领奖！",
            "【悬赏结算提醒】悬赏令结算时刻已到！您的侠义之名远扬，丰厚灵石与珍贵功法正等着您来领取！",
            "【悬赏结算提醒】悬赏令时间到！您的努力终有回报，海量资源已备好，助您在修仙之途大步迈进！",
            "【悬赏结算提醒】悬赏任务结算啦！您的英勇无畏让修真界重归安宁，快来领取这份荣耀奖赏！",
            "【悬赏结算提醒】悬赏令结算时刻来临！您的付出已化作修仙至宝，速来开启这份惊喜收获！");
    private static final List<String> LT_TEXT_LIST = Arrays.asList("【灵田结算提醒】隔壁炼丹童子盯着您的灵田流口水，请速速收药！",
            "【灵田结算提醒】灵田中的灵植已成熟！饱满的灵果蕴含天地精华，采摘后可助您炼丹突破，福泽深厚！",
            "【灵田结算提醒】灵植成熟，丰收已至！这片灵田的产出，将为您的修仙大业增添无限生机与灵力！",
            "【灵田结算提醒】灵田丰收啦！您的辛勤耕耘终有回报，成熟的灵植能炼制出逆天丹药，快来收取！",
            "【灵田结算提醒】灵植已到收获时节！采摘灵田中的宝贝，为您的修仙之路积累丰厚资源，前程似锦！",
            "【灵田结算提醒】灵田中的灵植焕发生机！此刻收取，定能收获满满灵气，助您修为一日千里！",
            "【灵田结算提醒】灵植成熟可收！您的灵田培育出的珍稀作物，能为您带来意想不到的修仙助力！",
            "【灵田结算提醒】灵田收获时间到！这片充满灵气的土地，为您孕育出了珍贵的灵植，速来采摘！");
    private static final String FILE_PATH = "./cache/task_data.ser";
    @Value("${botId}")
    private Long botId;
    public Map<Long, MessageNumber> MESSAGE_NUMBER_MAP = new ConcurrentHashMap();
    public static List<Long> remindGroupIdList = Arrays.asList(1023764416L,971327442L,679831529L,824484501L,690933736L);
    @Autowired
    public DanCalculator danCalculator;

    public GroupManager() {
    }


    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        message = message.trim();
        if (message.equals("清空发言统计")) {
            MESSAGE_NUMBER_MAP.put(bot.getBotId(), new MessageNumber(0,  System.currentTimeMillis()));
            group.sendMessage((new MessageChain()).reply(messageId).text("执行成功"));
        }else if (message.equals("同步发言统计")) {
            saveTasksToFile();
            group.sendMessage((new MessageChain()).reply(messageId).text("执行成功"));
        }

    }

    // cron 表达式：0 55 7 * * * 表示每天早上 7 点 55 分执行
    @Scheduled(cron = "0 58 7 * * *")
    public void executeMessageTask() {
        logger.info("定时清空发言统计");
        BotFactory.getBots().values().forEach((bot) -> {
            MESSAGE_NUMBER_MAP.put(bot.getBotId(), new MessageNumber(0,  System.currentTimeMillis()));

        });
    }


    @PostConstruct
    public void init() {
        this.loadTasksFromFile();
        logger.info("已从本地加载{}个灵田任务 {}个发言统计",  this.ltmap.size(),this.MESSAGE_NUMBER_MAP.size());
    }

    @Scheduled(
            fixedRate = 3600000L
    )
    public void autoSaveTasks() {
        this.saveTasksToFile();
        logger.debug("定时任务数据持久化完成");
    }

    @PreDestroy
    public void onShutdown() {
        this.saveTasksToFile();
        logger.info("程序关闭时持久化任务数据完成");
    }

    public synchronized void saveTasksToFile() {
        try {
            ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(Paths.get(FILE_PATH)));
            Map<String, Object> data = new HashMap();
            data.put("灵田", this.ltmap);
            data.put("发言统计", MESSAGE_NUMBER_MAP);
            oos.writeObject(data);
            oos.close();
        } catch (Throwable var5) {
            logger.error("任务数据保存失败：", var5);
        }

        logger.info("正在保存 {} 个灵田任务 {}个发言统计", this.ltmap.size(),MESSAGE_NUMBER_MAP.size());
    }

    private synchronized void loadTasksFromFile() {
        File dataFile = new File(FILE_PATH);
        if (dataFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(dataFile.toPath()))) {
                Map<String, Object> data = (Map)ois.readObject();

                // 安全初始化 map
                this.ltmap = data.containsKey("灵田") ?
                        (ConcurrentHashMap<String, RemindTime>)data.get("灵田") :
                        new ConcurrentHashMap<>();

                this.MESSAGE_NUMBER_MAP = data.containsKey("发言统计") ?
                        (ConcurrentHashMap<Long, MessageNumber>)data.get("发言统计") :
                        new ConcurrentHashMap<>();

            } catch (Exception e) {
                logger.error("任务数据加载失败，初始化空map", e);
                this.ltmap = new ConcurrentHashMap<>();
                this.MESSAGE_NUMBER_MAP = new ConcurrentHashMap<>();
            }
        } else {
            logger.warn("未找到序列化文件，初始化空map");
            this.ltmap = new ConcurrentHashMap<>();
            this.MESSAGE_NUMBER_MAP = new ConcurrentHashMap<>();
        }
    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 我要头衔(final Bot bot, final Group group, final Member member, MessageChain messageChain, String message, Integer messageId) {
        if (bot.getBotConfig().isEnableSelfTitle() && message.contains("我要头衔")) {
            final String specialTitle = message.substring(message.indexOf("我要头衔") + 4).trim();
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        bot.setGroupSpecialTitle(member.getUserId(), specialTitle, 0, group.getGroupId());
                    } catch (Exception var2) {
                    }

                }
            });
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 修改群昵称(final Bot bot, final Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isAtSelf = message.contains("" + bot.getBotId());
        if (group.getGroupId() == 682220759L && isAtSelf && message.contains("道友今天双修次数已经到达上限")) {
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        bot.setGroupCard(group.getGroupId(), bot.getBotId(), "A无偿双修(剩余0次)");
                    } catch (Exception var2) {
                    }

                }
            });
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void 统计群聊发言次数(final Bot bot, final Group group, Member member, MessageChain messageChain, String message, Integer messageId) {



        if(group!=null && group.getGroupId()>0){
//            MESSAGE_NUMBER_MAP.compute(bot.getBotId(), (k, v) -> {
//                if (v == null) {
//                    return new MessageNumber(1, System.currentTimeMillis());
//                }
//                return new MessageNumber(v.getNumber() + 1, System.currentTimeMillis());
//            });
            // 确保Map存在
            if (MESSAGE_NUMBER_MAP == null) {
                MESSAGE_NUMBER_MAP = new ConcurrentHashMap<>();
            }
            MessageNumber messageNumber = MESSAGE_NUMBER_MAP.get(bot.getBotId());
            if(messageNumber == null){
                messageNumber = new MessageNumber();
                messageNumber.setNumber(1);
                messageNumber.setTime(System.currentTimeMillis());
            }else{
                if(messageNumber.isCrossResetTime()){
                    messageNumber.setNumber(1);
                    messageNumber.setTime(System.currentTimeMillis());
                }else{
                    messageNumber.setNumber(messageNumber.getNumber()+1);
                    messageNumber.setTime(System.currentTimeMillis());
                }

            }
            if ((danCalculator!=null && danCalculator.config!=null && danCalculator.config.getAlchemyQQ() == bot.getBotId()) &&
                    (messageNumber.getNumber() == 10 || messageNumber.getNumber() % 100 == 0 ) && group.getGroupId() == bot.getBotConfig().getGroupId()) {
                bot.setGroupCard(group.getGroupId(), bot.getBotId(), bot.getBotName()+"(发言次数:"+messageNumber.getNumber()+")");
            }
            MESSAGE_NUMBER_MAP.put(bot.getBotId(), messageNumber);
        }
        message = message.trim();
        if (message.equals("发言统计")) {
            MessageNumber messageNumber = MESSAGE_NUMBER_MAP.get(bot.getBotId());
            if(messageNumber!=null){
                StringBuilder sb = new StringBuilder();
                sb.append("今日发言次数：").append(messageNumber.getNumber()).append("\n");
                sb.append("最新更新时间：").append(sdf.format(new Date(messageNumber.getTime())));
                group.sendMessage(new MessageChain().reply(messageId).text(sb.toString()));
            }

        }

    }

    @Scheduled(
            cron = "0 0 4 * * *"
    )
    public void 重置双修次数() throws Exception {
        BotFactory.getBots().values().forEach((bot) -> {
            if (bot.getBotId() != bot.getBotConfig().getMasterQQ()) {
                bot.setGroupCard(682220759L, bot.getBotId(), "A无偿双修");
            }

        });
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 秘境结算提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        if (bot.getBotConfig().isEnableGroupManager() && !remindGroupIdList.contains(group.getGroupId())) {
//            boolean isGroupQQ = false;
//            if (StringUtils.isNotBlank(bot.getBotConfig().getGroupQQ())) {
//                isGroupQQ = ("&" + bot.getBotConfig().getGroupQQ() + "&").contains("&" + group.getGroupId() + "&");
//            } else {
//                isGroupQQ = group.getGroupId() == 802082768L;
//            }
//
//            if (!isGroupQQ) {
//                return;
//            }

            if (message.contains("进行中的：") && message.contains("可结束") && message.contains("探索")) {
                this.extractInfo(message, "秘境", group);
            }else if (message.contains("进入秘境") && message.contains("探索需要花费")) {
                this.extractInfo(message, "秘境", group);
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 悬赏令结算提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        if (bot.getBotConfig().isEnableGroupManager() && !remindGroupIdList.contains(group.getGroupId())
                && message.contains("进行中的悬赏令") && message.contains("可结束")) {
//            boolean isGroupQQ = false;
//            if (StringUtils.isNotBlank(bot.getBotConfig().getGroupQQ())) {
//                isGroupQQ = ("&" + bot.getBotConfig().getGroupQQ() + "&").contains("&" + member.getGroupId() + "&");
//            } else {
//                isGroupQQ = group.getGroupId() == 802082768L;
//            }
//
//            if (!isGroupQQ) {
//                return;
//            }

            this.extractInfo(message, "悬赏", group);
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 新版悬赏令结算提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        if (bot.getBotConfig().isEnableGroupManager() && !remindGroupIdList.contains(group.getGroupId())
                && (message.contains("悬赏令进行中")  || message.contains("悬赏令接取成功")) && message.contains("预计")) {
            // 提取QQ号
            Pattern qqPattern = Pattern.compile("@(\\d+)");
            Matcher qqMatcher = qqPattern.matcher(message);
            String qq = qqMatcher.find() ? qqMatcher.group(1) : "未找到QQ号";

            // 提取预计时间
            Pattern timePattern = null;
            if(message.contains("预计时间")){
                timePattern = Pattern.compile("预计时间：(\\d+\\.?\\d*)分钟");
            }
            if(message.contains("预计剩余时间")){
                timePattern = Pattern.compile("预计剩余时间：(\\d+\\.?\\d*)分钟");
            }
            if(timePattern!=null){
                Matcher timeMatcher = timePattern.matcher(message);
                String time = timeMatcher.find() ? timeMatcher.group(1) : "未找到预计时间";
                addXslMap(qq,"悬赏",group,time);
            }

//            this.extractInfo(message, "悬赏", group);
        }

    }

    public void extractInfo(String input, String type, Group group) {
        String qqPattern = "@(\\d+)";
        String timePattern = "(\\d+\\.?\\d*)(?:\\(原\\d+\\.?\\d*\\))?(?:分钟|分钟后)";
        Pattern qqRegex = Pattern.compile(qqPattern);
        Pattern timeRegex = Pattern.compile(timePattern);
        Matcher qqMatcher = qqRegex.matcher(input);
        String qq = "";
        if (qqMatcher.find()) {
            qq = qqMatcher.group(1);
        } else {
            logger.warn("未找到QQ号");
        }

        Matcher timeMatcher = timeRegex.matcher(input);
        String time = "";
        if (timeMatcher.find()) {
            time = timeMatcher.group(1);
        } else {
            logger.warn("未找到时间");
        }

//        if (StringUtils.isNotBlank(qq) && StringUtils.isNotBlank(time)) {
//            RemindTime remindTime = new RemindTime();
//            remindTime.setQq(Long.parseLong(qq));
//            remindTime.setExpireTime((long)(Double.parseDouble(time) * 60.0 * 1000.0 + (double)System.currentTimeMillis()));
//            remindTime.setText(type);
//            remindTime.setGroupId(group.getGroupId());
//            this.mjXslmap.put(qq, remindTime);
//        }
        addXslMap(qq,type,group,time);

    }

    private void addXslMap(String qq,String type, Group group,String time){
        if (StringUtils.isNotBlank(qq) && StringUtils.isNotBlank(time)) {
            RemindTime remindTime = new RemindTime();
            remindTime.setQq(Long.parseLong(qq));
            remindTime.setExpireTime((long)(Double.parseDouble(time) * 60.0 * 1000.0 + (double)System.currentTimeMillis()));
            remindTime.setText(type);
            remindTime.setGroupId(group.getGroupId());
            this.mjXslmap.put(qq, remindTime);
        }
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 灵田领取提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        if (bot.getBotConfig().isEnableGroupManager() && message.contains("灵田还不能收取") && !remindGroupIdList.contains(group.getGroupId())) {
//            boolean isGroupQQ = false;
//            if (StringUtils.isNotBlank(bot.getBotConfig().getGroupQQ())) {
//                isGroupQQ = ("&" + bot.getBotConfig().getGroupQQ() + "&").contains("&" + member.getGroupId() + "&");
//            } else {
//                isGroupQQ = group.getGroupId() == 802082768L;
//            }
//
//            if (!isGroupQQ) {
//                return;
//            }

            this.handleLingTianMessage(message, group);
        }

    }

    private void handleLingTianMessage(String message, Group group) {
        String regex = "@(\\d{5,12}).*?(\\d+\\.\\d+)小时";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(message);
        String qqNumber = "";
        String time = "";
        if (matcher.find()) {
            qqNumber = matcher.group(1);
            time = matcher.group(2);
        }

        if (StringUtils.isNotBlank(qqNumber) && StringUtils.isNotBlank(time)) {
            RemindTime remindTime = new RemindTime();
            remindTime.setText("灵田");
            remindTime.setQq(Long.parseLong(qqNumber));
            remindTime.setExpireTime((long)(Double.parseDouble(time) * 60.0 * 60.0 * 1000.0 + (double)System.currentTimeMillis()));
            remindTime.setGroupId(group.getGroupId());
            this.ltmap.put(qqNumber, remindTime);
            group.sendMessage((new MessageChain()).at(qqNumber).text("灵田收取时间为：" + sdf.format(new Date(remindTime.getExpireTime()))));
        }

    }

    @Scheduled(
            fixedDelay = 30000L,
            initialDelay = 3000L
    )
    public void 结算() {
        this.checkAndNotify(this.mjXslmap, "悬赏", "秘境");
        this.checkAndNotify(this.ltmap, "灵田", "灵田");
    }

    private void checkAndNotify(Map<String, RemindTime> map, String taskType1, String taskType2) {
        if(botId != null){
            Iterator<Map.Entry<String, RemindTime>> iterator = map.entrySet().iterator();

            while(iterator.hasNext()) {
                Map.Entry<String, RemindTime> entry = (Map.Entry)iterator.next();
                RemindTime remindTime = (RemindTime)entry.getValue();
                if (remindTime.getExpireTime() > 0L && System.currentTimeMillis() >= remindTime.getExpireTime()) {
                    if(System.currentTimeMillis() - remindTime.getExpireTime() < 1000L * 60 * 30){
                        Bot bot = BotFactory.getBots().get(botId);
                        if (bot != null) {
                            switch (remindTime.getText()) {
                                case "悬赏":
                                    bot.getGroup(remindTime.getGroupId()).sendMessage((new MessageChain())
                                            .at(remindTime.getQq() + "").text(XSL_TEXT_LIST.get(new Random().nextInt(XSL_TEXT_LIST.size()))));
                                    break;
                                case "秘境":
                                    bot.getGroup(remindTime.getGroupId()).sendMessage((new MessageChain())
                                            .at(remindTime.getQq() + "").text(MJ_TEXT_LIST.get(new Random().nextInt(MJ_TEXT_LIST.size()))));
                                    break;
                                case "灵田":
                                    bot.getGroup(remindTime.getGroupId()).sendMessage((new MessageChain())
                                            .at(remindTime.getQq() + "").text(LT_TEXT_LIST.get(new Random().nextInt(LT_TEXT_LIST.size()))));
                                    break;
                            }

                        }
                    }

                    iterator.remove();

                }
            }
        }


    }
}
