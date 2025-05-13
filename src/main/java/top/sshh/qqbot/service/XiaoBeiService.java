//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.FriendMessageHandler;
import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Friend;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.Message;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.data.QQBotConfig;

@Component
public class XiaoBeiService {
    private static final Logger log = LoggerFactory.getLogger(XiaoBeiService.class);
    @Autowired
    private ProductPriceResponse productPriceResponse;
    private static final ForkJoinPool customPool = new ForkJoinPool(20);
    public static final Map<Long, Map<String, ProductPrice>> AUTO_BUY_PRODUCT = new ConcurrentHashMap();
    @Value("${xbGroupId:0}")
    private Long xbGroupId;
    private Map<Long, Map<String, Integer>> herbMap = new ConcurrentHashMap<>();
    Map<Long, QQBotConfig> botConfigMap = new ConcurrentHashMap();
    private static final String botQQ = "3889029313";

    public XiaoBeiService() {
    }

    public void proccessCultivation(Bot bot, QQBotConfig botConfig) {
        botConfig.setFamilyTaskStatus(0);
        botConfig.setXslTime(-1L);
        botConfig.setMjTime(-1L);
        sendBotMessage(bot, "闭关", true);
//        group.sendMessage((new MessageChain()).at(botQQ).text("闭关"));
    }



    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
        message = message.trim();

        if ("开始小北自动宗门任务".equals(message)) {
            botConfig.setFamilyTaskStatus(1);
        }

        if ("停止小北自动宗门任务".equals(message)) {
            botConfig.setFamilyTaskStatus(0);
        }

        if (message.startsWith("/")) {
            String number = message.substring(message.indexOf("/") + 1).trim();
            group.sendMessage((new MessageChain()).at("3889029313").text("查询世界BOSS " + number));
        }

        if ("小北自动药材上架".equals(message)) {
            botConfig.setCommand("小北自动药材上架");
            group.sendMessage((new MessageChain()).at("3889029313").text("药材"));
        }

        if ("停止执行".equals(message)) {
            botConfig.setStop(true);
            group.sendMessage((new MessageChain()).reply(messageId).text("停止执行成功"));
            botConfig.setCommand("");
        }

        if ("确认药材上架".equals(message)) {
            // 遍历外层Map
            for (Map.Entry<Long, Map<String, Integer>> outerEntry : herbMap.entrySet()) {
                Long outerKey = outerEntry.getKey();
                Map<String, Integer> innerMap = outerEntry.getValue();

                // 遍历内层Map
                for (Map.Entry<String, Integer> innerEntry : innerMap.entrySet()) {
                    String innerKey = innerEntry.getKey();
                    Integer value = innerEntry.getValue();
                    Bot bot1 = BotFactory.getBots().get(outerKey);
                    Group group1 = bot1.getGroup(xbGroupId);
                    if (bot1 != null && group1 != null) {
                        group1.sendMessage((new MessageChain())
                                .at("3889029313").text("坊市上架 " + innerKey + " " + 1 + " " + value));
                        Thread.sleep(3000L);
                    }
                }
            }
            herbMap.clear();
        }

        if (message.endsWith("小北药材上架")) {
            List<ReplyMessage> replyMessageList = messageChain.getMessageByType(ReplyMessage.class);
            if (replyMessageList != null && !replyMessageList.isEmpty()) {
                ReplyMessage replyMessage = (ReplyMessage) replyMessageList.get(0);
                MessageChain replyMessageChain = replyMessage.getChain();
                if (replyMessageChain != null) {
                    List<TextMessage> textMessageList = replyMessageChain.getMessageByType(TextMessage.class);
                    if (textMessageList != null && !textMessageList.isEmpty()) {
                        TextMessage textMessage = (TextMessage) textMessageList.get(textMessageList.size() - 1);
                        String herbsInfo = textMessage.getText();
                        String[] lines = herbsInfo.split("\n");
                        Map<String, Integer> herbs = extractHerbs(herbsInfo);

                        if (botConfig.getCommand().equals("小北自动药材上架")) {
                            herbMap.put(bot.getBotId(), herbs);
                        } else {
                            Iterator var16 = herbs.entrySet().iterator();

                            while (var16.hasNext()) {
                                Map.Entry<String, Integer> entry = (Map.Entry) var16.next();
                                if (botConfig.isStop()) {
                                    botConfig.setStop(false);
                                    break;
                                }

                                group.sendMessage((new MessageChain()).at("3889029313").text("坊市上架 " + (String) entry.getKey() + " " + 1 + " " + entry.getValue()));
                                Thread.sleep(3000L);

                            }
                        }

                    }
                }

                botConfig.setCommand("");
            }
        }

    }


    public void initBots() {
        if (!BotFactory.getBots().isEmpty() && botConfigMap.isEmpty()) {
            BotFactory.getBots().values().forEach((bot) -> {

                if (botConfigMap.get(bot.getBotId()) == null) {
                    botConfigMap.put(bot.getBotId(), new QQBotConfig());
                }

            });
            log.info("机器人数量：{}", BotFactory.getBots().size());
        }


    }

    private boolean isAtSelf(String message, Bot bot) {
        return message.contains("@" + bot.getBotId()) || message.contains("@" + bot.getBotName());
    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 弟子执行小北命令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isControlQQ = false;
        if (StringUtils.isNotBlank(bot.getBotConfig().getControlQQ())) {
            isControlQQ = ("&" + bot.getBotConfig().getControlQQ() + "&").contains("&" + member.getUserId() + "&");
        } else {
            isControlQQ = bot.getBotConfig().getMasterQQ() == member.getUserId();
        }

        if (isControlQQ && message.contains("#")) {
            Iterator iterator = messageChain.iterator();

            while (iterator.hasNext()) {
                Message timeMessage = (Message) iterator.next();
                if (!(timeMessage instanceof AtMessage)) {
                    break;
                }

                iterator.remove();
            }

            message = ((TextMessage) messageChain.get(0)).getText().trim();
            if (message.startsWith("#")) {
                message = message.substring(message.indexOf("#") + 1);
                messageChain.set(0, new TextMessage(message));
                messageChain.add(0, new AtMessage("3889029313"));
                group.sendMessage(messageChain);
            }
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 讨伐世界boss(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isControlQQ = bot.getBotConfig().getMasterQQ() == member.getUserId();

        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
        boolean isGroup = isXbGroup(group, botConfig);
        if (isGroup && isControlQQ && (message.contains("讨伐世界boss") || message.contains("讨伐世界BOSS")) && bot.getBotConfig().getMasterQQ() != bot.getBotId()) {
            Pattern pattern = Pattern.compile("讨伐世界boss(\\d+)");
            Matcher matcher = pattern.matcher(message);
            String text = "";
            if (matcher.find()) {
                text = "讨伐世界boss" + matcher.group(1);
            }

            if (StringUtils.isNotBlank(text)) {
                group.sendMessage((new MessageChain()).at("3889029313").text(text));
            }
        }

    }

    private boolean isXbGroup(Group group, QQBotConfig botConfig) {

        return group.getGroupId() == xbGroupId;
    }

    public static Map<String, Integer> extractHerbs(String input) {
        Map<String, Integer> result = new LinkedHashMap();
        Pattern pattern = Pattern.compile("(.+?)\\s+-.+数量:\\s+(\\d+)");
        Matcher matcher = pattern.matcher(input);

        for (int count = 0; matcher.find() && count < 10; ++count) {
            String herbName = matcher.group(1).trim();
            int quantity = Integer.parseInt(matcher.group(2).trim());
            result.put(herbName, quantity);
        }

        return result;
    }

    @GroupMessageHandler(
            senderIds = {3889029313L}
    )
    public void 小北药材上架(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isAtSelf = isAtSelf(message, bot);
        if (isAtSelf && message.contains("的药材背包")) {
            System.out.println(message);
            QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
            if (StringUtils.isNotBlank(botConfig.getCommand()) && botConfig.getCommand().equals("小北自动药材上架")) {
                group.sendMessage((new MessageChain()).reply(messageId).text("小北药材上架"));
            }
        }

    }

    @Scheduled(
            cron = "*/5 * * * * *"
    )
    public void 宗门任务接取刷新() throws InterruptedException {
        if (xbGroupId > 0) {
            initBots();
            BotFactory.getBots().values().forEach((bot) -> {
                QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
                Group group = bot.getGroup(xbGroupId);
                switch (botConfig.getFamilyTaskStatus()) {
                    case 0:
                        return;
                    case 1:

                        sendBotMessage(bot, "宗门任务接取", true);
                        return;
                    case 2:
                        sendBotMessage(bot, "宗门任务完成", true);
                        botConfig.setFamilyTaskStatus(1);
                        return;
                    case 3:
                        if (botConfig.getLastExecuteTime() + 10000L < System.currentTimeMillis()) {
                            sendBotMessage(bot, "宗门任务刷新", true);
                        }

                        return;
                    default:
                }
            });
        }

    }

    @GroupMessageHandler(
            senderIds = {3889029313L}
    )
    public void 宗门任务状态管理(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
        boolean isGroup = isXbGroup(group, botConfig);
        boolean isAtSelf = isAtSelf(message, bot);
        if (isGroup && isAtSelf) {
            sectMessage(bot, message);

        }

    }

    @FriendMessageHandler(
            senderIds = {3889029313L}
    )
    public void 宗门任务状态管理(Bot bot, Friend member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {

        sectMessage(bot, message);
    }

    private void sectMessage(Bot bot, String message) throws InterruptedException {
        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
        if (message.contains("今日无法再获取宗门任务")) {
            proccessCultivation(bot, botConfig);
        } else if (message.contains("请检查该道具是否在背包内") || message.contains("道友不满足使用条件")) {
            botConfig.setFamilyTaskStatus(0);
        } else if (message.contains("灵石带少了") && message.contains("出门做任务") && message.contains("不扣你任务次数")) {
            botConfig.setFamilyTaskStatus(0);
        } else if (message.contains("状态欠佳") && message.contains("出门做任务") && message.contains("不扣你任务次数")) {
            sendBotMessage(bot, "使用道源丹", true);
            botConfig.setFamilyTaskStatus(1);
        }
        if (message.contains("九转仙丹") && message.contains("请道友下山购买")) {
            botConfig.setLastExecuteTime(System.currentTimeMillis());
            botConfig.setFamilyTaskStatus(2);
        } else {
            if (botConfig.getFamilyTaskStatus() != 0) {
                if (message.contains("点击宗门任务接取")) {
                    botConfig.setFamilyTaskStatus(1);
                } else {
                    botConfig.setFamilyTaskStatus(3);
                    botConfig.setLastExecuteTime(System.currentTimeMillis());
                }

            }
        }
    }

    @GroupMessageHandler(
            senderIds = {3889029313L}
    )
    public void 秘境(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
        boolean isGroup = isXbGroup(group, botConfig);
        boolean isAtSelf = isAtSelf(message, bot);
        if (isGroup && isAtSelf) {
            mjMessage(bot, message);
        }

    }

    @FriendMessageHandler(
            senderIds = {3889029313L}
    )
    public void 秘境(Bot bot, Friend member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {

        mjMessage(bot, message);
    }

    private void mjMessage(Bot bot, String message) {
        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
        if (message.contains("参加过本次所有秘境")) {
            proccessCultivation(bot, botConfig);
        } else if (message.contains("进行中的：") && message.contains("可结束") && message.contains("探索")) {
            String[] parts;
            if (message.contains("(原")) {
                parts = message.split("预计|\\(原");
            } else {
                parts = message.split("预计|分钟");
            }

            System.out.println("秘境结算时间" + parts[1]);
            botConfig.setMjTime((long) (Double.parseDouble(parts[1]) * 60.0 * 1000.0 + (double) System.currentTimeMillis()));
        } else if (message.contains("进入秘境") && message.contains("探索需要花费")) {
            String[] parts;
            if (message.contains("(原")) {
                parts = message.split("花费时间：|\\(原");
            } else {
                parts = message.split("花费时间：|分钟");
            }

            System.out.println("秘境结算时间" + parts[1]);
            botConfig.setMjTime((long) (Double.parseDouble(parts[1]) * 60.0 * 1000.0 + (double) System.currentTimeMillis()));
        }

    }

    @GroupMessageHandler(
            senderIds = {3889029313L}
    )
    public void 悬赏令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
        boolean isGroup = isXbGroup(group, botConfig);
        boolean isAtSelf = isAtSelf(message, bot);
        if (isGroup && isAtSelf) {
            xslMessage(bot, message);
        }

    }

    @FriendMessageHandler(
            senderIds = {3889029313L}
    )
    public void 悬赏令(Bot bot, Friend member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {

        xslMessage(bot, message);
    }

    private void xslMessage(Bot bot, String message) {
        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
        if (message.contains("道友现在在做悬赏令呢")) {
            sendBotMessage(bot, "悬赏令结算", true);
        } else if (message.contains("没有查到你的悬赏令信息")) {
            sendBotMessage(bot, "悬赏令", true);
        } else if (message.contains("当前没有进行中的悬赏令任务")) {
            sendBotMessage(bot, "悬赏令刷新", true);
        } else if (message.contains("悬赏令刷新次数已用尽") || message.contains("道友的免费刷新次数已耗尽")) {
            botConfig.setXslTime(-1L);
            proccessCultivation(bot, botConfig);
        } else if (message.contains("进行中的悬赏令") && message.contains("可结束")) {
            String[] parts;
            if (message.contains("(原")) {
                parts = message.split("预计|\\(原");
            } else {
                parts = message.split("预计|分钟");
            }

            botConfig.setXslTime((long) (Math.ceil(Double.parseDouble(parts[1])) * 60.0 * 1000.0 + (double) System.currentTimeMillis()));
        } else if (message.contains("进行中的悬赏令") && message.contains("已结束")) {
            botConfig.setXslTime(-1L);
            sendBotMessage(bot, "悬赏令结算", true);
        } else if (message.contains("接取任务") && message.contains("成功")) {
            sendBotMessage(bot, "悬赏令结算", true);

        } else if (message.contains("发布悬赏令如下")) {
            sendBotMessage(bot, "悬赏令接取" + selectBestTask(message), true);
        }else if (message.contains("悬赏令结算") && message.contains("增加修为")) {
            botConfig.setXslTime(-1L);
            sendBotMessage(bot, "悬赏令刷新", true);
        }
    }

//    @GroupMessageHandler(
//            senderIds = {3889029313L}
//    )
//    public void 悬赏令接取(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
//        boolean isGroup = isXbGroup(group, botConfig);
//        boolean isAtSelf = isAtSelf(message, bot);
//        if (isGroup && isAtSelf && message.contains("发布悬赏令如下")) {
//            sendBotMessage(bot,"悬赏令接取" + selectBestTask(message),true);
//        }
//
//    }
//


    public static int selectBestTask(String rewardText) {
        List<Task> tasks = parseTasks(rewardText);
        if (tasks.isEmpty()) {
            return -1;
        }

        // 优先级物品列表（从上到下优先级递减）
        String[] priorityItems = {
                "佛怒火莲", "天剑破虚", "仙火焚天", "千慄鬼噬", "灭剑", "万剑", "宿命通", "明心问道果",
                "离火梧桐芝", "剑魄竹笋", "尘磊岩麟果", "风神诀", "合欢魔功"

        };

        // 1. 检查是否有优先物品
        for (String item : priorityItems) {
            for (Task task : tasks) {
                if (task.getRewardItem().contains(item)) {
                    return task.getNumber();
                }
            }
        }

        // 2. 否则选择修为最高的任务
        long maxExp = -1;
        int bestTask = -1;
        for (Task task : tasks) {
            if (task.getExp() > maxExp) {
                maxExp = task.getExp();
                bestTask = task.getNumber();
            }
        }
        return bestTask;
    }

    private static List<Task> parseTasks(String rewardText) {
        List<Task> tasks = new ArrayList<>();
        String[] lines = rewardText.split("\n");
        int currentTaskNumber = -1;
        String currentTaskName = null;
        long currentExp = -1;
        String currentRewardItem = null;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("☆") || line.startsWith(">")) {
                continue;
            }

            // 检测任务编号行（如 "1、寻找九叶芝, 完成机率 100%..."）
            if (line.matches("^\\d+、.+")) {
                // 保存上一个任务（如果有）
                if (currentTaskNumber != -1) {
                    tasks.add(new Task(currentTaskNumber, currentTaskName, currentExp, currentRewardItem));
                }
                // 解析新任务
                String[] parts = line.split("、", 2);
                currentTaskNumber = Integer.parseInt(parts[0]);
                String rest = parts[1];

                // 解析任务名称（如 "寻找九叶芝"）
                currentTaskName = rest.split(",")[0].trim();

                // 解析修为（如 "基础报酬 400 修为"）
                String expPart = rest.split("基础报酬")[1].split("修为")[0].trim();
                currentExp = Long.parseLong(expPart.replaceAll("[^0-9]", ""));

                // 解析奖励物品（如 "可能额外获得：一品药材:红绫草"）
                if (rest.contains("可能额外获得：")) {
                    currentRewardItem = rest.split("可能额外获得：")[1].trim();
                }
            }
        }

        // 添加最后一个任务
        if (currentTaskNumber != -1) {
            tasks.add(new Task(currentTaskNumber, currentTaskName, currentExp, currentRewardItem));
        }

        return tasks;
    }

    static class Task {
        private final int number;
        private final String name;
        private final long exp;
        private final String rewardItem;

        public Task(int number, String name, long exp, String rewardItem) {
            this.number = number;
            this.name = name;
            this.exp = exp;
            this.rewardItem = rewardItem;
        }

        public int getNumber() {
            return number;
        }

        public long getExp() {
            return exp;
        }

        public String getRewardItem() {
            return rewardItem;
        }
    }

    @GroupMessageHandler(
            senderIds = {3889029313L}
    )
//    public void 结算(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
//        boolean isGroup = isXbGroup(group, botConfig);
//        boolean isAtSelf = isAtSelf(message, bot);
//        if (isGroup && isAtSelf && message.contains("悬赏令结算") && message.contains("增加修为")) {
//            botConfig.setXslTime(-1L);
//            sendBotMessage(bot, "悬赏令刷新", true);
//        }
//
//    }
//
//    @FriendMessageHandler(
//            senderIds = {3889029313L}
//    )
//    public void 结算(Bot bot, Friend member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
//        if (message.contains("悬赏令结算") && message.contains("增加修为")) {
//            botConfig.setXslTime(-1L);
//            sendBotMessage(bot, "悬赏令刷新", true);
//        }
//
//    }


    @Scheduled(
            fixedDelay = 60000L,
            initialDelay = 3000L
    )
    public void 结算() {
        if (xbGroupId > 0) {
            BotFactory.getBots().values().forEach((bot) -> {
                QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
                if (botConfig != null && botConfig.getMjTime() > 0L && botConfig.getMjTime() < System.currentTimeMillis()) {
                    botConfig.setMjTime(-1L);

                    try {
                        sendBotMessage(bot, "秘境结算", true);
                        Thread.sleep(5000L);
                        sendBotMessage(bot, "探索秘境", true);
                    } catch (Exception e) {
                    }

                }

            });
            BotFactory.getBots().values().forEach((bot) -> {
                QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
                if (botConfig != null && botConfig.getXslTime() > 0L && botConfig.getXslTime() < System.currentTimeMillis()) {
                    sendBotMessage(bot, "悬赏令结算", true);
                }

            });
        }

    }

    @GroupMessageHandler(
            senderIds = {3889029313L}
    )
    public void 灵田领取结果(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//        QQBotConfig botConfig = bot.getBotConfig();
        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
        boolean isGroup = isXbGroup(group, botConfig);
        boolean isAtSelf = isAtSelf(message, bot);
        if (isGroup && isAtSelf) {
            lingTianReceive(bot, message);
        }

    }

    @FriendMessageHandler(
            senderIds = {3889029313L}
    )
    public void 灵田领取结果(Bot bot, Friend member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        lingTianReceive(bot, message);
    }

    private void lingTianReceive(Bot bot, String message) throws InterruptedException {
        QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
        if (message.contains("灵田还不能收取")) {
            String[] parts = message.split("：|小时");
            if (parts.length < 2) {
                log.info("输入格式不正确，请确保格式为 '下次收取时间为：XX.XX小时'");
                return;
            }

            double hours = Double.parseDouble(parts[1].trim());
            botConfig.setLastExecuteTime((long) ((double) System.currentTimeMillis() + hours * 60.0 * 60.0 * 1000.0));
            sendBotMessage(bot, "下次收取时间为：" + FamilyTask.sdf.format(new Date(botConfig.getLastExecuteTime())), false);
        } else if (message.contains("还没有洞天福地")) {
            botConfig.setLastExecuteTime(9223372036854175807L);
        }
    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 开启无偿双修(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (message.contains("双修")) {
            Iterator iterator = messageChain.iterator();

            while (iterator.hasNext()) {
                Message timeMessage = (Message) iterator.next();
                if (!(timeMessage instanceof AtMessage)) {
                    break;
                }

                iterator.remove();
            }

            if (messageChain.get(0) instanceof TextMessage) {
                message = ((TextMessage) messageChain.get(0)).getText().trim();
                if (message.startsWith("请双修")) {
                    Pattern textPattern = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
                    Matcher textMatcher = textPattern.matcher(message);
                    String textKeyword = "";
                    if (textMatcher.find()) {
                        textKeyword = textMatcher.group().substring(1);
                    }

                    Pattern numberPattern = Pattern.compile("\\d+");
                    Matcher numberMatcher = numberPattern.matcher(message);
                    int numberKeyword = 0;
                    if (numberMatcher.find()) {
                        numberKeyword = Integer.parseInt(numberMatcher.group());
                    }

                    messageChain.set(0, new TextMessage(textKeyword));
                    messageChain.add(0, new AtMessage(botQQ));
                    this.forSendMessage(bot, group, messageChain, numberKeyword, 3);
                }


            }
        }

    }

    private void forSendMessage(Bot bot, Group group, MessageChain messageChain, int count, int time) {
        for (int i = 0; i < count; ++i) {
            QQBotConfig botConfig = botConfigMap.get(bot.getBotId());;
            if (botConfig.isStop()) {
                botConfig.setStop(false);
                return;
            }

            try {
                group.sendMessage(messageChain);
                Thread.sleep((long) time * 1000L);
            } catch (Exception var9) {
            }
        }

    }

    @Scheduled(
            cron = "0 */1 * * * *"
    )
    public void 灵田领取() throws InterruptedException {
        if (xbGroupId > 0) {

            for (Bot bot : BotFactory.getBots().values()) {
                QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
                if (botConfig != null && botConfig.getLastExecuteTime() + 60000L < System.currentTimeMillis()) {
                    sendBotMessage(bot, "灵田结算", true);
                }
            }
        }


    }

    @Scheduled(
            cron = "0 1 4 * * *"
    )
    public void 定时悬赏令刷新() {
        System.out.println("悬赏令定时任务执行啦！");
        if (xbGroupId > 0) {
            Iterator var1 = BotFactory.getBots().values().iterator();

            while (var1.hasNext()) {
                Bot bot = (Bot) var1.next();

                try {
                    sendBotMessage(bot, "出关", true);
                    Thread.sleep(5000L);
                    sendBotMessage(bot, "悬赏令刷新", true);
                    Thread.sleep(5000L);
                    sendBotMessage(bot, "悬赏令", true);
                } catch (Exception ignored) {
                }
            }
        }


    }

    @Scheduled(
            cron = "0 31 12 * * *"
    )
    public void 定时探索秘境() {
        if (xbGroupId > 0) {
            log.info("秘境定时任务执行啦！");

            for (Bot bot : BotFactory.getBots().values()) {

                try {
                    sendBotMessage(bot, "出关", true);
                    Thread.sleep(5000L);
                    sendBotMessage(bot, "探索秘境", true);
                } catch (Exception ignored) {
                }
            }
        }


    }

    @Scheduled(
            cron = "0 1 0 * * *"
    )
    public void 定时宗门任务() {
        if (xbGroupId > 0) {
            log.info("宗门任务定时任务执行啦！");

            for (Bot bot : BotFactory.getBots().values()) {


                try {
                    sendBotMessage(bot, "宗门丹药领取", true);
                    Thread.sleep(2000L);
                    bot.getGroup(xbGroupId).sendMessage((new MessageChain()).text("开始小北自动宗门任务"));
                } catch (Exception ignored) {
                }
            }
        }


    }

    private void sendBotMessage(Bot bot, String message, boolean isAtBot) {
        try {
            QQBotConfig botConfig = botConfigMap.get(bot.getBotId());
            if (botConfig.isPrivateChat()) {
                Thread.sleep(2000L);
                bot.sendPrivateMessage(Long.parseLong(botQQ), (new MessageChain()).text(message));
            } else {
                if (isAtBot) {
                    Thread.sleep(2000L);
                    bot.getGroup(xbGroupId).sendMessage((new MessageChain()).at(botQQ).text(message));
                } else {
                    Thread.sleep(2000L);
                    bot.getGroup(xbGroupId).sendMessage((new MessageChain()).text(message));
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
