//package top.sshh.qqbot.service;
//// Source code recreated from a .class file by IntelliJ IDEA
//// (powered by FernFlower decompiler)
////
//
//
//import com.zhuangxv.bot.annotation.GroupMessageHandler;
//import com.zhuangxv.bot.core.Bot;
//import com.zhuangxv.bot.core.Group;
//import com.zhuangxv.bot.core.Member;
//import com.zhuangxv.bot.core.component.BotFactory;
//import com.zhuangxv.bot.message.Message;
//import com.zhuangxv.bot.message.MessageChain;
//import com.zhuangxv.bot.message.support.AtMessage;
//import com.zhuangxv.bot.message.support.TextMessage;
//import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
//import org.apache.commons.lang3.StringUtils;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;
//import top.sshh.qqbot.data.ProductPrice;
//import top.sshh.qqbot.data.QQBotConfig;
//
//import javax.annotation.PostConstruct;
//import java.time.LocalDateTime;
//import java.util.*;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.ForkJoinPool;
//
//@Component
//public class XiaoYueService {
//    private static final Logger log = LoggerFactory.getLogger(XiaoYueService.class);
//    @Autowired
//    private ProductPriceResponse productPriceResponse;
//    private static final ForkJoinPool customPool = new ForkJoinPool(20);
//    public static final Map<Long, Map<String, ProductPrice>> AUTO_BUY_PRODUCT = new ConcurrentHashMap();
//    public static final String PRODUCT_NAME = "#明心问道果#离火梧桐芝#剑魄竹笋#尘磊岩麟果#天剑破虚#仙火焚天#千慄鬼噬#佛怒火莲#合欢魔功#";
//    public static final String SECRET_END_NAME = "在秘境最深处#道友在秘境#进入秘境#秘境内竟然#道友大战一番成功#道友大战一番不敌#仅为空手文案";
//    public static final String MODE_NAME = "邪修抢夺灵石#私自架设小型窝点#被追打催债#秘境内竟然#请道友下山购买#为宗门购买一些#速去仙境抢夺仙境之石#义字当头";
//    private static final String FILE_PATH = "./botConfig.json";
//    Map<Long, QQBotConfig> botConfigMap = new ConcurrentHashMap();
//    @Value("${yueGroupId:0}")
//    private Long yueGroupId;
//    private static final String botQQ = "3889282919";
//
//    public XiaoYueService() {
//    }
//
//    @PostConstruct
//    public void init() {
//
//    }
//
//    public static void proccessCultivation(Group group, QQBotConfig botConfig) {
////        botConfig.setBeiFamilyTaskStatus(0);
//        group.sendMessage((new MessageChain()).at(botQQ).text("闭关"));
//    }
//
//    @GroupMessageHandler(
//            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
//    )
//    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
//        message = message.trim();
//        if ("开始小月修炼".equals(message)) {
//            botConfig.setStartScheduled(true);
//            group.sendMessage((new MessageChain()).reply(messageId).text("收到"));
//        }
//
//        if ("停止小月修炼".equals(message)) {
//            botConfig.setStartScheduled(false);
//            group.sendMessage((new MessageChain()).reply(messageId).text("收到"));
//        }
//
//
//
//
//
//    }
//
//    @GroupMessageHandler(
//            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
//    )
//    public void 弟子执行小北命令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
//        boolean isControlQQ = false;
//        if (StringUtils.isNotBlank(bot.getBotConfig().getControlQQ())) {
//            isControlQQ = ("&" + bot.getBotConfig().getControlQQ() + "&").contains("&" + member.getUserId() + "&");
//        } else {
//            isControlQQ = bot.getBotConfig().getMasterQQ() == member.getUserId();
//        }
//
//        if (isControlQQ && message.startsWith("全部听令")) {
//            Iterator iterator = messageChain.iterator();
//
//            while(iterator.hasNext()) {
//                Message timeMessage = (Message)iterator.next();
//                if (!(timeMessage instanceof AtMessage)) {
//                    break;
//                }
//
//                iterator.remove();
//            }
//
//            message = ((TextMessage)messageChain.get(0)).getText().trim();
//            if (message.startsWith("全部听令")) {
//                message = message.substring(message.indexOf("全部听令") + 4);
//                messageChain.set(0, new TextMessage(message));
//                messageChain.add(0, new AtMessage(botQQ));
//                group.sendMessage(messageChain);
//            }
//        }
//
//    }
//
//
//
//    @Scheduled(
//            fixedDelay = 63000L,
//            initialDelay = 20000L
//    )
//    public void 定时修炼() {
//        BotFactory.getBots().values().forEach((bot) -> {
//
//            if(botConfigMap.get(bot.getBotId()) == null){
//                botConfigMap.put(bot.getBotId(),new QQBotConfig());
//            }
//            QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
//            if (botConfig!=null && botConfig.isStartScheduled() && botConfig.getLastSendTime() + 65000L < System.currentTimeMillis()) {
//                if(bot.getGroup(yueGroupId)!=null){
//                    bot.getGroup(yueGroupId).sendMessage((new MessageChain()).at(botQQ).text("修炼"));
//                }
//
//            }
//
//        });
//    }
//
//    @GroupMessageHandler
//    public void autoSend修炼(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
//        if (group.getGroupId() == yueGroupId && message.contains("" + bot.getBotId())) {
//            if ((message.contains("本次修炼增加") || message.contains("本次挖矿获取"))  && botConfig.isStartScheduled()) {
//
//                botConfig.setLastSendTime(System.currentTimeMillis());
//                group.sendMessage((new MessageChain()).at(botQQ).text("修炼"));
//            }
//
//            if(message.contains("本次修炼触及瓶颈")){
//                group.sendMessage((new MessageChain()).at(botQQ).text("直接突破"));
//            }
//
//
//        }
//
//    }
//
//    @GroupMessageHandler(
//            senderIds = {3889282919L}
//    )
//    public void 秘境(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
////        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
////        LocalDateTime now = LocalDateTime.now();
////        boolean isGroup = group.getGroupId() == Long.parseLong(botQQ);
////        boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
////        if (isGroup && isAtSelf) {
////            if (message.contains("参加过本次所有秘境")) {
////                proccessCultivation(group, botConfig);
////            }
////
////            if (message.contains("道友现在什么都没干")) {
////            }
////
////            String[] parts;
////            if (message.contains("进行中的：") && message.contains("可结束") && message.contains("探索")) {
////                if (message.contains("(原")) {
////                    parts = message.split("预计|\\(原");
////                } else {
////                    parts = message.split("预计|分钟");
////                }
////
////                System.out.println("秘境结算时间" + parts[1]);
////                botConfig.setMjBeiTime((long)(Double.parseDouble(parts[1]) * 60.0 * 1000.0 + (double)System.currentTimeMillis()));
////            }
////
////            if (message.contains("进入秘境") && message.contains("探索需要花费")) {
////                if (message.contains("(原")) {
////                    parts = message.split("花费时间：|\\(原");
////                } else {
////                    parts = message.split("花费时间：|分钟");
////                }
////
////                System.out.println("秘境结算时间" + parts[1]);
////                botConfig.setMjBeiTime((long)(Double.parseDouble(parts[1]) * 60.0 * 1000.0 + (double)System.currentTimeMillis()));
////            }
////        }
//
//    }
//
//    @GroupMessageHandler(
//            senderIds = {3889282919L}
//    )
//    public void 悬赏令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
//        boolean isGroup = group.getGroupId() == Long.parseLong(botQQ);
//        boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
//        if (botConfig!=null && isGroup && isAtSelf) {
////            if (message.contains("在做悬赏令呢") && message.contains("分身乏术")) {
////                group.sendMessage((new MessageChain()).at("3889282919").text("悬赏令结算"));
////            }
//
//            if (message.contains("悬赏令次数已然用尽") ) {
//                proccessCultivation(group, botConfig);
//            }
//
//
//
//        }
//
//    }
//
//    @GroupMessageHandler(
//            senderIds = {3889282919L}
//    )
//    public void 悬赏令接取(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//
//        boolean isGroup = group.getGroupId() == yueGroupId;
//       boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
//       boolean isXsl = message.contains("道友的悬赏令")||message.contains("道友的个人悬赏令");
//        if (isGroup && isAtSelf && isXsl) {
//            Iterator<TextMessage> textMessageIterator = messageChain.getMessageByType(TextMessage.class).iterator();
//
//            group.sendMessage((new MessageChain()).at(botQQ).text("悬赏令接取"+selectBestTask(message)));
//        }
//
//    }
//
//    public static int selectBestTask(String rewardText) {
//        List<Task> tasks = parseTasks(rewardText);
//        if (tasks.isEmpty()) {
//            return -1;
//        }
//
//        // 优先级物品列表（按顺序检查）
//        String[] priorityItems = {"九品药材", "八品药材", "七品药材", "天尊", "界主", "神变"};
//
//        // 1. 检查是否有优先物品
//        for (String item : priorityItems) {
//            for (Task task : tasks) {
//                if (task.getRewardItem().contains(item)) {
//                    return task.getNumber();
//                }
//            }
//        }
//
//        // 2. 否则选择修为最高的任务
//        int maxExp = -1;
//        int bestTask = -1;
//        for (Task task : tasks) {
//            if (task.getExp() > maxExp) {
//                maxExp = task.getExp();
//                bestTask = task.getNumber();
//            }
//        }
//        return bestTask;
//    }
//
//    private static List<Task> parseTasks(String rewardText) {
//        List<Task> tasks = new ArrayList<>();
//        String[] lines = rewardText.split("\n");
//        int currentTaskNumber = -1;
//        String currentTaskName = null;
//        int currentSuccessRate = -1;
//        int currentExp = -1;
//        String currentRewardItem = null;
//
//        for (String line : lines) {
//            line = line.trim();
//            if (line.isEmpty()) {
//                continue;
//            }
//
//            // 检测任务编号行（如 "1、寻找龙鳞果"）
//            if (line.matches("^\\d+[、.].+")) {
//                // 保存上一个任务（如果有）
//                if (currentTaskNumber != -1) {
//                    tasks.add(new Task(currentTaskNumber, currentTaskName, currentSuccessRate, currentExp, currentRewardItem));
//                }
//                // 解析新任务
//                String[] parts = line.split("[、.]", 2);
//                currentTaskNumber = Integer.parseInt(parts[0]);
//                currentTaskName = parts[1].trim();
//            }
//            // 解析成功率（如 "完成机率🎲100%"）
//            else if (line.startsWith("完成机率")) {
//                String percent = line.replaceAll("[^0-9]", "");
//                currentSuccessRate = Integer.parseInt(percent);
//            }
//            // 解析修为（如 "基础报酬💗1200修为"）
//            else if (line.startsWith("基础报酬")) {
//                String exp = line.replaceAll("[^0-9]", "");
//                currentExp = Integer.parseInt(exp);
//            }
//            // 解析奖励物品（如 "可能额外获得:🎁三品药材:紫猴花!"）
//            else if (line.startsWith("可能额外获得")) {
//                currentRewardItem = line.substring(line.indexOf("🎁") + 1, line.length() - 1);
//            }
//        }
//
//        // 添加最后一个任务
//        if (currentTaskNumber != -1) {
//            tasks.add(new Task(currentTaskNumber, currentTaskName, currentSuccessRate, currentExp, currentRewardItem));
//        }
//
//        return tasks;
//    }
//
//    static class Task {
//        private final int number;
//        private final String name;
//        private final int successRate;
//        private final int exp;
//        private final String rewardItem;
//
//        public Task(int number, String name, int successRate, int exp, String rewardItem) {
//            this.number = number;
//            this.name = name;
//            this.successRate = successRate;
//            this.exp = exp;
//            this.rewardItem = rewardItem;
//        }
//
//        public int getNumber() { return number; }
//        public int getExp() { return exp; }
//        public String getRewardItem() { return rewardItem; }
//    }
//
//    @GroupMessageHandler(
//            senderIds = {3889282919L}
//    )
//    public void 结算(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//        if(yueGroupId>0){
//            QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
//            boolean isGroup = group.getGroupId() == yueGroupId;
//            boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
//            if (isGroup && isAtSelf && message.contains("悬赏令结算") && message.contains("增加修为")) {
//                group.sendMessage((new MessageChain()).at("3889282919").text("悬赏令刷新"));
//            }
//        }
//
//
//    }
//
//    public static boolean containsSpecificTexts(String text, List<String> specificTexts) {
//        Iterator var2 = specificTexts.iterator();
//
//        while(var2.hasNext()) {
//            String specificText = (String)var2.next();
//            if (text.contains(specificText)) {
//                return true;
//            }
//        }
//
//        return false;
//    }
//
//    @Scheduled(
//            fixedDelay = 60000L,
//            initialDelay = 3000L
//    )
//    public void 结算() {
////        BotFactory.getBots().values().forEach((bot) -> {
////            QQBotConfig botConfig = bot.getBotConfig();
////            if (botConfig.getMjBeiTime() > 0L && botConfig.getMjBeiTime() < System.currentTimeMillis()) {
////                botConfig.setMjTime(-1L);
////                long groupId = botConfig.getGroupId();
////                if (botConfig.getTaskId() != 0L) {
////                    groupId = botConfig.getTaskId();
////                }
////
////                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889282919").text("秘境结算"));
////
////                try {
////                    Thread.sleep(5000L);
////                } catch (Exception var5) {
////                }
////
////                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889282919").text("探索秘境"));
////            }
////
////        });
////        BotFactory.getBots().values().forEach((bot) -> {
////            QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
////            if (botConfig!=null && botConfig.getXslTime() > 0L && botConfig.getXslTime() < System.currentTimeMillis()) {
////
////                bot.getGroup(yueGroupId).sendMessage((new MessageChain()).at("3889282919").text("悬赏令结算"));
////            }
////
////        });
//    }
//
//    @GroupMessageHandler(
//            senderIds = {3889282919L}
//    )
//    public void 灵田领取结果(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
//        if(botConfig !=null && yueGroupId > 0){
//            boolean isGroup = group.getGroupId() == yueGroupId;
//            boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
//            if (isGroup && isAtSelf) {
//                if (message.contains("灵田还不能收取")) {
//                    String[] parts = message.split("：|小时");
//                    if (parts.length < 2) {
//                        group.sendMessage((new MessageChain()).text("输入格式不正确，请确保格式为 '下次收取时间为：XX.XX小时"));
//                        return;
//                    }
//
//                    double hours = Double.parseDouble(parts[1].trim());
//                    botConfig.setLastExecuteTime((long)((double)System.currentTimeMillis() + hours * 60.0 * 60.0 * 1000.0));
//                    group.sendMessage((new MessageChain()).text("下次收取时间为：" + FamilyTask.sdf.format(new Date(botConfig.getLastExecuteTime()))));
//                } else if (message.contains("还没有洞天福地")) {
//                    System.out.println(LocalDateTime.now() + " " + group.getGroupName() + " 收到灵田领取结果,还没有洞天福地");
//                    botConfig.setLastExecuteTime(9223372036854175807L);
//                }
//            }
//        }
//
//
//    }
//
//    @Scheduled(
//            cron = "0 */1 * * * *"
//    )
//    public void 灵田领取() throws InterruptedException {
//        if(yueGroupId>0){
//            Iterator var1 = BotFactory.getBots().values().iterator();
//
//            while(var1.hasNext()) {
//                Bot bot = (Bot)var1.next();
//                QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
//                if (botConfig!=null && botConfig.getLastExecuteTime() + 60000L < System.currentTimeMillis()) {
//
//
//                    try {
//                        Group group = bot.getGroup(yueGroupId);
//                        if(group!=null && bot.getBotId()!=1366671213L){
//                            group.sendMessage((new MessageChain()).at("3889282919").text("灵田结算"));
//                        }
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                    }
//                }
//            }
//        }
//
//
//    }
//
////    @Scheduled(
////            cron = "0 1 4 * * *"
////    )
////    public void 定时悬赏令刷新() {
////        System.out.println("悬赏令定时任务执行啦！");
////        Iterator var1 = BotFactory.getBots().values().iterator();
////
////        while(var1.hasNext()) {
////            Bot bot = (Bot)var1.next();
////            QQBotConfig botConfig = bot.getBotConfig();
////            long groupId = botConfig.getGroupId();
////            if (botConfig.getTaskId() != 0L) {
////                groupId = botConfig.getTaskId();
////            }
////
////            Group group = bot.getGroup(groupId);
////
////            try {
////                group.sendMessage((new MessageChain()).at("3889282919").text("出关"));
////                Thread.sleep(5000L);
////                group.sendMessage((new MessageChain()).at("3889282919").text("悬赏令刷新"));
////                Thread.sleep(5000L);
////                group.sendMessage((new MessageChain()).at("3889282919").text("悬赏令"));
////            } catch (Exception var8) {
////            }
////        }
////
////    }
//
////    @Scheduled(
////            cron = "0 31 12 * * *"
////    )
////    public void 定时探索秘境() {
////        System.out.println("秘境定时任务执行啦！");
////        Iterator var1 = BotFactory.getBots().values().iterator();
////
////        while(var1.hasNext()) {
////            Bot bot = (Bot)var1.next();
////            QQBotConfig botConfig = bot.getBotConfig();
////            long groupId = botConfig.getGroupId();
////            if (botConfig.getTaskId() != 0L) {
////                groupId = botConfig.getTaskId();
////            }
////
////            Group group = bot.getGroup(groupId);
////
////            try {
////                group.sendMessage((new MessageChain()).at("3889282919").text("出关"));
////                Thread.sleep(5000L);
////                group.sendMessage((new MessageChain()).at("3889282919").text("探索秘境"));
////            } catch (Exception var8) {
////            }
////        }
////
////    }
//
////    @Scheduled(
////            cron = "0 1 0 * * *"
////    )
////    public void 定时宗门任务() {
////        System.out.println("宗门任务定时任务执行啦！");
////        Iterator var1 = BotFactory.getBots().values().iterator();
////
////        while(var1.hasNext()) {
////            Bot bot = (Bot)var1.next();
////            QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
////
////
////            Group group = bot.getGroup(yueGroupId);
////
////            try {
////                group.sendMessage((new MessageChain()).at("3889282919").text("宗门丹药领取"));
////                Thread.sleep(2000L);
////                group.sendMessage((new MessageChain()).text("开始小北自动宗门任务"));
////            } catch (Exception var8) {
////            }
////        }
////
////    }
//}
