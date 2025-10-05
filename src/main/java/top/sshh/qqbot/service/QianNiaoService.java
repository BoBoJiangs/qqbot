// //
// // Source code recreated from a .class file by IntelliJ IDEA
// // (powered by FernFlower decompiler)
// //

// package top.sshh.qqbot.service;

// import com.alibaba.fastjson2.JSON;
// import com.alibaba.fastjson2.JSONReader;
// import com.alibaba.fastjson2.TypeReference;
// import com.zhuangxv.bot.annotation.FriendMessageHandler;
// import com.zhuangxv.bot.annotation.GroupMessageHandler;
// import com.zhuangxv.bot.core.Bot;
// import com.zhuangxv.bot.core.Friend;
// import com.zhuangxv.bot.core.Group;
// import com.zhuangxv.bot.core.Member;
// import com.zhuangxv.bot.core.component.BotFactory;
// import com.zhuangxv.bot.message.Message;
// import com.zhuangxv.bot.message.MessageChain;
// import com.zhuangxv.bot.message.support.AtMessage;
// import com.zhuangxv.bot.message.support.ReplyMessage;
// import com.zhuangxv.bot.message.support.TextMessage;
// import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
// import java.nio.file.Files;
// import java.nio.file.LinkOption;
// import java.nio.file.OpenOption;
// import java.nio.file.Path;
// import java.nio.file.Paths;
// import java.util.ArrayList;
// import java.util.Iterator;
// import java.util.LinkedHashMap;
// import java.util.List;
// import java.util.Map;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.ForkJoinPool;
// import java.util.regex.Matcher;
// import java.util.regex.Pattern;
// import org.apache.commons.lang3.StringUtils;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.scheduling.annotation.Scheduled;
// import org.springframework.stereotype.Component;
// import top.sshh.qqbot.constant.Constant;
// import top.sshh.qqbot.data.ProductPrice;
// import top.sshh.qqbot.data.QQBotConfig;

// @Component
// public class QianNiaoService {
//     private static final Logger log = LoggerFactory.getLogger(XiaoBeiService.class);
//     @Autowired
//     private ProductPriceResponse productPriceResponse;
//     private static final ForkJoinPool customPool = new ForkJoinPool(20);
//     public static final Map<Long, Map<String, ProductPrice>> AUTO_BUY_PRODUCT = new ConcurrentHashMap();
//     @Value("${xbGroupId:0}")
//     private Long xbGroupId;
//     private Map<Long, Map<String, Integer>> herbMap = new ConcurrentHashMap();
//     Map<String, QQBotConfig> botConfigMap = new ConcurrentHashMap();
//     public static final String botQQ = "3889029313";
//     private static final String FILE_PATH = "qn_bot_config_map.json";
//     private long updateBotTime = 0L;
//     private Map<Long, EndlessState> endlessStateMap = new ConcurrentHashMap();

//     public QianNiaoService() {
//         this.loadOrCreateConfig();
//     }

    

//     @GroupMessageHandler(
//             ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
//     )
//     public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//         QQBotConfig botConfig = (QQBotConfig)this.botConfigMap.get(bot.getBotId() + "");
//         message = message.trim();
//         if (!StringUtils.isEmpty(message)) {
//             if (message.equals("小北命令") || message.equals("小北当前设置")) {
//                 group.sendMessage((new MessageChain()).reply(messageId).text(this.showReplyMessage(message, botConfig, bot)));
//             }

            

//             if ("开始小北自动宗门任务".equals(message)) {
//                 botConfig.setFamilyTaskStatus(1);
//             }

//             if ("停止小北自动宗门任务".equals(message)) {
//                 botConfig.setFamilyTaskStatus(0);
//             }

//             if ("启用小北私聊".equals(message)) {
//                 botConfig.setPrivateChat(true);
//                 group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
//             }

//             if ("关闭小北私聊".equals(message)) {
//                 botConfig.setPrivateChat(false);
//                 group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
//             }

//             if ("启用小北自动宗门任务".equals(message)) {
//                 botConfig.setEnableFamilyTask(true);
//                 group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
//             }

//             if ("关闭小北自动宗门任务".equals(message)) {
//                 botConfig.setEnableFamilyTask(false);
//                 group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
//             }

//             if ("开始小北自动悬赏".equals(message)) {
//                 botConfig.setEnableXsl(true);
//                 this.sendBotMessage(bot, "悬赏令", true);
//             }

//             if ("开始小北自动秘境".equals(message)) {
//                 botConfig.setEnableXsl(true);
//                 this.sendBotMessage(bot, "探索秘境", true);
//             }

//             if ("启用小北自动悬赏".equals(message)) {
//                 botConfig.setEnableXsl(true);
//                 group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
//             }

//             if ("关闭小北自动悬赏".equals(message)) {
//                 botConfig.setEnableXsl(false);
//                 group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
//             }

//             if ("启用小北自动秘境".equals(message)) {
//                 botConfig.setEnableMj(true);
//                 group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
//             }

//             if ("关闭小北自动秘境".equals(message)) {
//                 botConfig.setEnableMj(false);
//                 group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
//             }

//             if (message.startsWith("小北修炼模式")) {
//                 String number = message.substring(message.indexOf("小北修炼模式") + 6).trim();
//                 if (StringUtils.isNotBlank(number)) {
//                     int floor = Integer.parseInt(number);
//                     if (floor == 2) {
//                         this.sendBotMessage(bot, "灵石修炼50000000", true);
//                     }

//                     botConfig.setCultivateMode(floor);
//                 }
//             }

//             if (message.startsWith("/")) {
//                 String number = message.substring(message.indexOf("/") + 1).trim();
//                 group.sendMessage((new MessageChain()).at("3889029313").text("查询世界BOSS " + number));
//             }

//             if ("小北自动药材上架".equals(message)) {
//                 botConfig.setCommand("小北自动药材上架");
//                 group.sendMessage((new MessageChain()).at("3889029313").text("药材"));
//             }

//             if ("停止执行".equals(message) && botConfig != null) {
//                 botConfig.setStop(true);
//                 botConfig.setCommand("");
//             }

//             if ("确认药材上架".equals(message)) {
//                 for(Map.Entry<Long, Map<String, Integer>> outerEntry : this.herbMap.entrySet()) {
//                     Long outerKey = (Long)outerEntry.getKey();
//                     Map<String, Integer> innerMap = (Map)outerEntry.getValue();

//                     for(Map.Entry<String, Integer> innerEntry : innerMap.entrySet()) {
//                         String innerKey = (String)innerEntry.getKey();
//                         Integer value = (Integer)innerEntry.getValue();
//                         Bot bot1 = (Bot)BotFactory.getBots().get(outerKey);
//                         Group group1 = bot1.getGroup(this.xbGroupId);
//                         if (group1 != null) {
//                             group1.sendMessage((new MessageChain()).at("3889029313").text("坊市上架 " + innerKey + " " + 1 + " " + value));
//                             Thread.sleep(3000L);
//                         }
//                     }
//                 }

//                 this.herbMap.clear();
//             }

//             if (message.endsWith("小北药材上架")) {
//                 List<ReplyMessage> replyMessageList = messageChain.getMessageByType(ReplyMessage.class);
//                 if (replyMessageList != null && !replyMessageList.isEmpty()) {
//                     ReplyMessage replyMessage = (ReplyMessage)replyMessageList.get(0);
//                     MessageChain replyMessageChain = replyMessage.getChain();
//                     if (replyMessageChain != null) {
//                         List<TextMessage> textMessageList = replyMessageChain.getMessageByType(TextMessage.class);
//                         if (textMessageList != null && !textMessageList.isEmpty()) {
//                             TextMessage textMessage = (TextMessage)textMessageList.get(textMessageList.size() - 1);
//                             String herbsInfo = textMessage.getText();
//                             String[] lines = herbsInfo.split("\n");
//                             Map<String, Integer> herbs = extractHerbs(herbsInfo);
//                             if (botConfig.getCommand().equals("小北自动药材上架")) {
//                                 this.herbMap.put(bot.getBotId(), herbs);
//                             } else {
//                                 for(Map.Entry<String, Integer> entry : herbs.entrySet()) {
//                                     if (botConfig.isStop()) {
//                                         botConfig.setStop(false);
//                                         break;
//                                     }

//                                     group.sendMessage((new MessageChain()).at("3889029313").text("坊市上架 " + (String)entry.getKey() + " " + 1 + " " + entry.getValue()));
//                                     Thread.sleep(3000L);
//                                 }
//                             }
//                         }
//                     }

//                     botConfig.setCommand("");
//                 }
//             }

//             if (message.matches("开始自动挑战无尽\\d+")) {
//                 int floor = Integer.parseInt(message.replaceAll("\\D+", ""));
//                 EndlessState state = new EndlessState();
//                 state.status = 1;
//                 state.floor = floor;
//                 state.retryCount = 0;
//                 this.endlessStateMap.put(bot.getBotId(), state);
//                 this.sendBotMessage(bot, "无尽镜像挑战" + floor, true);
//             }
//         }

//     }

//     @Scheduled(
//             fixedDelay = 3600000L,
//             initialDelay = 60000L
//     )
//     public void autoSaveTasks() {
//         if (this.xbGroupId > 0L) {
//             this.saveTasksToFile();
//         }

//     }

//     public synchronized void saveTasksToFile() {
//         try {
//             this.saveMapToFile();
//         } catch (Exception var2) {
//             log.info("小北Bot配置保存失败：", var2);
//         }

//         log.info("正在同步 {} 个小北Bot配置", this.botConfigMap.size());
//     }

//     public void loadOrCreateConfig() {
//         Path configFile = Paths.get(Constant.targetDir + "xb_bot_config_map.json");

//         try {
//             if (Files.exists(configFile, new LinkOption[0])) {
//                 this.botConfigMap = this.loadMapFromFile();
//             }
//         } catch (Exception var3) {
//             log.info("配置文件操作失败{}", var3.getMessage());
//         }

//     }

//     public void saveMapToFile() {
//         try {
//             String jsonStr = JSON.toJSONString(this.botConfigMap);
//             Files.write(Paths.get("xb_bot_config_map.json"), jsonStr.getBytes(), new OpenOption[0]);
//         } catch (Exception var2) {
//             var2.printStackTrace();
//         }

//     }

//     public Map<String, QQBotConfig> loadMapFromFile() {
//         try {
//             Path path = Paths.get("xb_bot_config_map.json");
//             if (Files.exists(path, new LinkOption[0])) {
//                 String jsonStr = new String(Files.readAllBytes(path));
//                 return (Map)JSON.parseObject(jsonStr, new TypeReference<ConcurrentHashMap<String, QQBotConfig>>() {
//                 }, new JSONReader.Feature[0]);
//             }
//         } catch (Exception var3) {
//             var3.printStackTrace();
//         }

//         return new ConcurrentHashMap();
//     }

//     private String showReplyMessage(String message, QQBotConfig botConfig, Bot bot) {
//         if (botConfig == null) {
//             return "bot正在启动，请稍后";
//         } else {
//             StringBuilder sb = new StringBuilder();
//             if (message.equals("小北命令")) {
//                 sb.append("－－－－－功能设置－－－－－\n");
//                 sb.append("开始/停止小北自动宗门任务\n");
//                 sb.append("开始小北自动/悬赏/秘境\n");
//                 sb.append("启用/关闭小北私聊\n");
//                 sb.append("小北自动药材上架\n");
//                 sb.append("启用/关闭小北自动宗门任务\n");
//                 sb.append("启用/关闭小北自动悬赏\n");
//                 sb.append("启用/关闭小北自动秘境\n");
//                 sb.append("开始自动挑战无尽X\n");
//                 sb.append("小北回血丹设置[1/2]  // 1=道源丹, 2=天命血凝丹\n");
//                 return sb.toString();
//             } else {
//                 if (message.equals("小北当前设置")) {
//                     sb.append("－－－－－当前设置－－－－－\n");
//                     sb.append(Constant.padRight("小北私聊", 11) + ": " + (botConfig.isPrivateChat() ? "启用" : "关闭") + "\n");
//                     sb.append(Constant.padRight("小北自动宗门任务", 11) + ": " + (botConfig.isEnableFamilyTask() ? "启用" : "关闭") + "\n");
//                     sb.append(Constant.padRight("小北自动宗门悬赏", 11) + ": " + (botConfig.isEnableXsl() ? "启用" : "关闭") + "\n");
//                     sb.append(Constant.padRight("小北自动宗门秘境", 11) + ": " + (botConfig.isEnableMj() ? "启用" : "关闭") + "\n");
//                     sb.append(Constant.padRight("回血丹类型", 11) + ": " + (botConfig.getRecoveryPillType() == 1 ? "道源丹" : "天命血凝丹") + "\n");
//                 }

//                 return sb.toString();
//             }
//         }
//     }

//     @GroupMessageHandler(
//             ignoreItself = IgnoreItselfEnum.NOT_IGNORE
//     )
//     public void 弟子执行小北命令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
//         boolean isControlQQ = false;
//         if (StringUtils.isNotBlank(bot.getBotConfig().getControlQQ())) {
//             isControlQQ = ("&" + bot.getBotConfig().getControlQQ() + "&").contains("&" + member.getUserId() + "&");
//         } else {
//             isControlQQ = bot.getBotConfig().getMasterQQ() == member.getUserId();
//         }

//         if (isControlQQ && message.contains("#")) {
//             Iterator iterator = messageChain.iterator();

//             while(iterator.hasNext()) {
//                 Message timeMessage = (Message)iterator.next();
//                 if (!(timeMessage instanceof AtMessage)) {
//                     break;
//                 }

//                 iterator.remove();
//             }

//             message = ((TextMessage)messageChain.get(0)).getText().trim();
//             if (message.startsWith("#")) {
//                 message = message.substring(message.indexOf("#") + 1);
//                 messageChain.set(0, new TextMessage(message));
//                 messageChain.add(0, new AtMessage("3889029313"));
//                 group.sendMessage(messageChain);
//             }
//         }

//     }

//     @FriendMessageHandler(
//             senderIds = {3889029313L}
//     )
//     public void 灵石修炼(Bot bot, Friend member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//         QQBotConfig botConfig = (QQBotConfig)this.botConfigMap.get(bot.getBotId() + "");
//         if (botConfig != null && botConfig.getCultivateMode() == 2) {
//             if (message.contains("突破神火圆满境成功")) {
//                 botConfig.setCultivateMode(1);
//             } else if (message.contains("形意不成，回去练练再来吧")) {
//                 this.sendBotMessage(bot, "灵石修炼50000000", true);
//             } else if (!message.contains("突破失败") && !message.contains("为道友护道成功")) {
//                 if (message.contains("恭喜道友") && message.contains("突破") && message.contains("成功")) {
//                     this.sendBotMessage(bot, "直接突破", true);
//                 } else if (message.contains("修炼结束") && message.contains("修炼到达上限")) {
//                     this.sendBotMessage(bot, "直接突破", true);
//                 } else if (message.contains("修炼结束") && message.contains("修炼到达上限")) {
//                     this.sendBotMessage(bot, "直接突破", true);
//                 }
//             } else {
//                 this.sendBotMessage(bot, "直接突破", true);
//             }
//         }

//     }

//     @GroupMessageHandler(
//             ignoreItself = IgnoreItselfEnum.NOT_IGNORE
//     )
//     public void 讨伐世界boss(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
//         boolean isControlQQ = bot.getBotConfig().getMasterQQ() == member.getUserId();
//         if(botConfigMap!=null){
//             QQBotConfig botConfig = (QQBotConfig)this.botConfigMap.get(bot.getBotId() + "");
//             boolean isGroup = this.isXbGroup(group, botConfig);
//             if (isGroup && isControlQQ && (message.contains("讨伐世界boss") || message.contains("讨伐世界BOSS")) && bot.getBotConfig().getMasterQQ() != bot.getBotId()) {
//                 Pattern pattern = Pattern.compile("讨伐世界boss(\\d+)");
//                 Matcher matcher = pattern.matcher(message);
//                 String text = "";
//                 if (matcher.find()) {
//                     text = "讨伐世界boss" + matcher.group(1);
//                 }

//                 if (StringUtils.isNotBlank(text)) {
//                     group.sendMessage((new MessageChain()).at("3889029313").text(text));
//                 }
//             }
//         }


//     }

//     private boolean isXbGroup(Group group, QQBotConfig botConfig) {
//         return group.getGroupId() == this.xbGroupId;
//     }

//     private boolean isAtSelf(String message, Bot bot, Group group) {
//         String botName = bot.getBotName();
//         String cardName = group.getMember(bot.getBotId()).getCard();
//         if (StringUtils.isNotBlank(cardName)) {
//             botName = cardName;
//         }

//         return message.contains(botName) || message.contains("@"+bot.getBotId());
//     }

//     public static Map<String, Integer> extractHerbs(String input) {
//         Map<String, Integer> result = new LinkedHashMap();
//         Pattern pattern = Pattern.compile("(.+?)\\s+-.+数量:\\s+(\\d+)");
//         Matcher matcher = pattern.matcher(input);

//         for(int count = 0; matcher.find() && count < 10; ++count) {
//             String herbName = matcher.group(1).trim();
//             int quantity = Integer.parseInt(matcher.group(2).trim());
//             result.put(herbName, quantity);
//         }

//         return result;
//     }

    

    



    

    
// }
