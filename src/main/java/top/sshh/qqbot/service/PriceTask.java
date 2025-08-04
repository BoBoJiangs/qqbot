//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.TypeReference;
import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.BountyInfo;
import top.sshh.qqbot.data.GuessIdiom;
import top.sshh.qqbot.data.ProductLowPrice;
import top.sshh.qqbot.data.ProductPrice;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static top.sshh.qqbot.service.GroupManager.remindGroupIdList;


@Component
public class PriceTask {
    private static final Logger logger = LoggerFactory.getLogger(PriceTask.class);
    @Autowired
    private ProductPriceResponse productPriceResponse;
    private static final ForkJoinPool customPool = new ForkJoinPool(20);
    public static  String targetDir = "./";
    @Autowired
    private GroupManager groupManager;
    public PriceTask() {

    }

    @PostConstruct
    public void init() {
        this.readPrice();
    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        message = message.trim();
        if (message.equals("同步坊市价格") || message.equals("同步数据") ||message.equals("同步坊市数据")) {
            this.savePrice(group);
        }

    }

    public void readPrice() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(targetDir+"properties/坊市价格.txt"));

            try {
                StringBuilder jsonStr = new StringBuilder();

                String line;
                while((line = reader.readLine()) != null) {
                    jsonStr.append(line);
                }

                List<ProductPrice> personList = JSON.parseObject(jsonStr.toString(), new TypeReference<List<ProductPrice>>() {
                }, new JSONReader.Feature[0]);
                if (this.productPriceResponse != null) {

                    this.productPriceResponse.saveAll(personList);
                    logger.info("坊市价格读取成功！{}", personList.size());
                }
            } catch (Throwable var5) {
            }

            reader.close();
        } catch (Exception e) {
            System.err.println("读取配置文件失败: " + e.getMessage());
        }

    }

    public void savePrice(Group group) {
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(targetDir+"properties/坊市价格.txt"), StandardCharsets.UTF_8));

            try {
                List<ProductPrice> personList = (List)this.productPriceResponse.findAll();
                if (!personList.isEmpty()) {
                    String jsonStr = JSON.toJSONString(personList);
                    writer.write(jsonStr);
                    writer.flush();
                    if (group != null) {
                        group.sendMessage((new MessageChain()).text("同步坊市价格成功"));
                    }

                    System.out.println("同步坊市价格成功！");
                }
            } catch (Throwable var5) {
            }

            writer.close();
        } catch (Exception var6) {
            System.err.println("同步坊市价格失败: ");
        }

    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 查询行情(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (bot.getBotConfig().isEnableCheckMarket() && message.contains("查行情")) {
            String name = message.substring(message.indexOf("查行情") + 3).trim();

            try {
                MessageChain messages = new MessageChain();
                messages.image("http://qqbot.2dc2fea0.erapp.run/price/image/" + name);
                group.sendMessage(messages);
            } catch (Exception var9) {
            }
        }

    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 查悬赏令价格(Bot bot, Group group, Member member, MessageChain messageChain, Integer messageId) {
        if (bot.getBotConfig().isEnableXslPriceQuery() && !remindGroupIdList.contains(group.getGroupId())) {
            if(!groupManager.isRemindGroup(bot,group)){
                return;
            }
            List<ReplyMessage> replyMessageList = messageChain.getMessageByType(ReplyMessage.class);
            if (replyMessageList != null && !replyMessageList.isEmpty()) {
                ReplyMessage replyMessage = (ReplyMessage)replyMessageList.get(0);
                MessageChain replyMessageChain = replyMessage.getChain();
                if (replyMessageChain != null) {
                    List<TextMessage> textMessageList = replyMessageChain.getMessageByType(TextMessage.class);
                    if (textMessageList != null && !textMessageList.isEmpty()) {
                        TextMessage textMessage = (TextMessage)textMessageList.get(textMessageList.size() - 1);
                        String message = textMessage.getText();
                        if (message.contains("道友的个人悬赏令")) {
                            Pattern pattern = Pattern.compile("可能额外获得：(.*?)!");
                            Matcher matcher = pattern.matcher(message);
                            StringBuilder stringBuilder = new StringBuilder();
                            int count = 0;

                            while(matcher.find()) {
                                String name = matcher.group(1).replaceAll("\\s", "");
                                int colonIndex = name.indexOf(58);
                                if (colonIndex >= 0) {
                                    name = name.substring(colonIndex + 1).trim();
                                }

                                if (StringUtils.isNotBlank(name)) {
                                    ++count;
                                    ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(name.trim());
                                    if (first != null) {
                                        stringBuilder.append("\n悬赏令").append(count).append(" 奖励：").append(first.getName()).append(" 价格:").append(first.getPrice()).append("万").append("(炼金:").append(ProductLowPrice.getLowPrice(first.getName())).append("万)");
                                    }
                                }
                            }

                            if (stringBuilder.length() > 5) {
                                stringBuilder.insert(0, "悬赏令价格查询：");
                                group.sendMessage((new MessageChain()).reply(messageId).text(stringBuilder.toString()));
                            }
                        }
                    }
                }
            }
        }

    }

//    @GroupMessageHandler(
//            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
//    )
//    public void 自动查悬赏令价格(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
//        if (!remindGroupIdList.contains(group.getGroupId()) && bot.getBotConfig().isEnableXslPriceQuery() && message.contains("道友的个人悬赏令")) {
//            Iterator var7 = messageChain.getMessageByType(TextMessage.class).iterator();
//
//            while(true) {
//                do {
//                    if (!var7.hasNext()) {
//                        return;
//                    }
//
//                    TextMessage textMessage = (TextMessage)var7.next();
//                    message = textMessage.getText();
//                } while(!message.contains("道友的个人悬赏令"));
//
//                Pattern pattern = Pattern.compile("完成几率(\\d+),基础报酬(\\d+)修为.*?可能额外获得：[^:]+:(.*?)!");
//                Matcher matcher = pattern.matcher(message);
//                StringBuilder stringBuilder = new StringBuilder();
//                int count = 0;
//                int maxPriceIndex = 0;
//                int maxPrice = 0;
//
//                int maxCultivateIndex = 0;
//                long maxCultivate = 0;
//
//
//                while(matcher.find()) {
//                    int completionRate = Integer.parseInt(matcher.group(1));
//                    long cultivation = Long.parseLong(matcher.group(2));
//                    String name = matcher.group(3).replaceAll("\\s", "");
//                    int colonIndex = name.indexOf(58);
//                    if (colonIndex >= 0) {
//                        name = name.substring(colonIndex + 1).trim();
//                    }
//
//                    if (StringUtils.isNotBlank(name)) {
//                        ++count;
//                        ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(name.trim());
//                        if(completionRate == 100){
//                            cultivation = cultivation * 2;
//                        }
//                        if (cultivation > maxCultivate) {
//                            maxCultivate = cultivation;
//                            maxCultivateIndex = count;
//                        }
//                        if (first != null) {
//                            if (first.getPrice() > maxPrice) {
//                                maxPrice = first.getPrice();
//                                maxPriceIndex = count;
//                            }
//                            stringBuilder.append("\n\uD83C\uDF81悬赏令").append(count).append(" 奖励：").append(first.getName()).append(" 价格:").append(first.getPrice()).append("万")
//                                    .append("(炼金:").append(ProductLowPrice.getLowPrice(first.getName())).append("万)");
//                        }
//                    }
//                }
//                stringBuilder.append("\n\n最高修为:悬赏令" + maxCultivateIndex +"(修为" + formatCultivation(maxCultivate)+")");
//                stringBuilder.append("\n最高价格:悬赏令" + maxPriceIndex +"(价格" + maxPrice + "万)");
//                if (stringBuilder.length() > 5) {
//                    stringBuilder.insert(0, "悬赏令价格查询：");
//                    group.sendMessage((new MessageChain()).text(stringBuilder.toString()));
//                }
//            }
//        }
//    }
//
//
//    @GroupMessageHandler(
//            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
//    )
//    public void 新版查悬赏令价格(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
//        if (!remindGroupIdList.contains(group.getGroupId()) && bot.getBotConfig().isEnableXslPriceQuery()
//                && message.contains("天机悬赏令") && !message.contains("今日悬赏令刷新次数已用尽")) {
//            Iterator var7 = messageChain.getMessageByType(TextMessage.class).iterator();
//
//            while(true) {
//                do {
//                    if (!var7.hasNext()) {
//                        return;
//                    }
//
//                    TextMessage textMessage = (TextMessage)var7.next();
//                    message = textMessage.getText();
//                } while(!message.contains("天机悬赏令"));
//
//                Pattern pattern = Pattern.compile(
//                        "成功率：(\\d+)%.*?" +
//                                "基础奖励(\\d+)修为.*?" +
//                                "额外机缘：[^「]+「([^」]+)」",
//                        Pattern.DOTALL
//                );
//                Matcher matcher = pattern.matcher(message);
//                StringBuilder stringBuilder = new StringBuilder();
//                int count = 0;
//                int maxPriceIndex = 0;
//                int maxPrice = 0;
//
//                int maxCultivateIndex = 0;
//                long maxCultivate = 0;
//
//
//                while(matcher.find()) {
//                    int completionRate = Integer.parseInt(matcher.group(1));
//                    long cultivation = Long.parseLong(matcher.group(2));
//                    String name = matcher.group(3).replaceAll("\\s", "");
//                    int colonIndex = name.indexOf(58);
//                    if (colonIndex >= 0) {
//                        name = name.substring(colonIndex + 1).trim();
//                    }
//
//                    if (StringUtils.isNotBlank(name)) {
//                        ++count;
//                        ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(name.trim());
//                        if(completionRate == 100){
//                            cultivation = cultivation * 2;
//                        }
//                        if (cultivation > maxCultivate) {
//                            maxCultivate = cultivation;
//                            maxCultivateIndex = count;
//                        }
//                        if (first != null) {
//                            if (first.getPrice() > maxPrice) {
//                                maxPrice = first.getPrice();
//                                maxPriceIndex = count;
//                            }
//                            stringBuilder.append("\n\uD83C\uDF81悬赏令").append(count).append(" 奖励：").append(first.getName()).append(" 价格:").append(first.getPrice()).append("万")
//                                    .append("(炼金:").append(ProductLowPrice.getLowPrice(first.getName())).append("万)");
//                        }
//                    }
//                }
////                stringBuilder.append("\n\n最高修为:接取悬赏令" + maxCultivateIndex + "\n最高价格:接取悬赏令" + maxPriceIndex );
//                stringBuilder.append("\n\n最高修为:悬赏令" + maxCultivateIndex +"(修为" + formatCultivation(maxCultivate)+")");
//                stringBuilder.append("\n最高价格:悬赏令" + maxPriceIndex +"(价格" + maxPrice + "万)");
//                if (stringBuilder.length() > 5) {
//                    stringBuilder.insert(0, "悬赏令价格查询：");
//                    group.sendMessage((new MessageChain()).text(stringBuilder.toString()));
//                }
//            }
//        }
//    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 查悬赏令价格(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (!remindGroupIdList.contains(group.getGroupId()) && bot.getBotConfig().isEnableXslPriceQuery()) {
            boolean isPersonal = message.contains("道友的个人悬赏令");
            boolean isNewVersion = message.contains("天机悬赏令") && !message.contains("今日悬赏令刷新次数已用尽");

            if (!isPersonal && !isNewVersion) {
                return;
            }

            // 定义需要特殊提醒的功法列表
            Set<String> specialSkills = new HashSet<>(Arrays.asList(
                    "五指拳心剑", "袖里乾坤", "真龙九变", "灭剑血胧",
                    "万剑归宗", "华光猎影", "千慄鬼噬"
            ));

            for (TextMessage textMessage : messageChain.getMessageByType(TextMessage.class)) {
                message = textMessage.getText();
                if ((isPersonal && message.contains("道友的个人悬赏令")) ||
                        (isNewVersion && message.contains("天机悬赏令"))) {

                    Pattern pattern = isPersonal ?
                            Pattern.compile("完成几率(\\d+),基础报酬(\\d+)修为.*?可能额外获得：[^:]+:(.*?)!") :
                            Pattern.compile("成功率：(\\d+)%.*?基础奖励(\\d+)修为.*?额外机缘：[^「]+「([^」]+)」", Pattern.DOTALL);

                    Matcher matcher = pattern.matcher(message);
                    StringBuilder stringBuilder = new StringBuilder();
                    int count = 0;
                    int maxPriceIndex = 0;
                    int maxPrice = 0;
                    int maxCultivateIndex = 0;
                    long maxCultivate = 0;
                    boolean hasNon100Rate = false;
                    List<String> specialSkillMessages = new ArrayList<>();

                    // 新增变量用于推荐逻辑
                    int recommendIndex = 0;

                    List<BountyInfo> bountyInfos = new ArrayList<>();

                    while(matcher.find()) {
                        int completionRate = Integer.parseInt(matcher.group(1));
                        long cultivation = Long.parseLong(matcher.group(2));
                        String name = matcher.group(3).replaceAll("\\s", "");
                        int colonIndex = name.indexOf(58);
                        if (colonIndex >= 0) {
                            name = name.substring(colonIndex + 1).trim();
                        }

                        if (StringUtils.isNotBlank(name)) {
                            ++count;
                            ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(name.trim());



                            if(completionRate == 100){
                                cultivation = cultivation * 2;
                            } else {
                                hasNon100Rate = true;
                            }

                            // 存储悬赏信息用于推荐
                            bountyInfos.add(new BountyInfo(count, completionRate, cultivation, first != null ? first.getPrice() : 0));

                            if (cultivation > maxCultivate) {
                                maxCultivate = cultivation;
                                maxCultivateIndex = count;
                            }

                            if (first != null) {
                                if (first.getPrice() > maxPrice) {
                                    maxPrice = first.getPrice();
                                    maxPriceIndex = count;
                                }
                                stringBuilder.append("\n\uD83C\uDF81悬赏令").append(count).append(" 奖励：").append(first.getName()).append(" 价格:").append(formatCultivation(first.getPrice()*10000L))
                                        .append("(炼金:").append(ProductLowPrice.getLowPrice(first.getName())).append("万)");
                            }

                            // 检查是否是特殊功法
                            if (specialSkills.contains(name)) {
                                specialSkillMessages.add("恭喜道友获得：" + name + "（悬赏令" + count + "）！！！");
                            }
                        }
                    }

                    // 如果有非100%完成率的悬赏令，计算推荐接取
                    if (hasNon100Rate) {
                        // 按完成率降序、价格降序、修为降序排序
                        bountyInfos.sort((a, b) -> {
                            if (b.completionRate != a.completionRate) {
                                return b.completionRate - a.completionRate;
                            } else if (b.price >= 800 || a.price >= 800) {
                                return b.price - a.price;
                            } else {
                                return Long.compare(b.cultivation, a.cultivation);
                            }
                        });

                        recommendIndex = bountyInfos.get(0).index;
                    }

                    // 如果有特殊功法，优先发送提醒
                    if (!specialSkillMessages.isEmpty()) {
                        stringBuilder.append("\n");
                        for (String skillMsg : specialSkillMessages) {
                            stringBuilder.append("\n").append(skillMsg);
                        }
                    } else {
                        stringBuilder.append("\n\n最高修为:悬赏令").append(maxCultivateIndex)
                                .append("(修为").append(formatCultivation(maxCultivate)).append(")");
                        stringBuilder.append("\n最高价格:悬赏令").append(maxPriceIndex)
                                .append("(价格").append(formatCultivation(maxPrice*10000L)).append(")");

                        // 添加推荐信息
                        if (hasNon100Rate) {
                            stringBuilder.append("\n\n推荐接取:悬赏令").append(recommendIndex)
                                    .append("(完成率最高");
                            BountyInfo recommendInfo = bountyInfos.get(0);
//                            if (recommendInfo.price >= 800) {
//                                stringBuilder.append("，包含高价物品");
//                            } else {
//                                stringBuilder.append("，修为较高");
//                            }
                            stringBuilder.append(")");
                        }
                    }

                    if (stringBuilder.length() > 5) {
                        stringBuilder.insert(0, "悬赏令价格查询：");
                        group.sendMessage((new MessageChain()).text(stringBuilder.toString()));
                    }
                }
            }
        }
    }



    private String formatCultivation(long reward) {
        return reward >= 100000000L ? String.format("%.2f亿", (double)reward / 1.0E8) : reward / 10000L + "万";
    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 查上架价格(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (!bot.getBotConfig().isEnableCheckPrice() || !containsAnyKeywords(message)) {
            return;
        }

        try {
            PriceCalculationResult result = new PriceCalculationResult();

            // 处理回复消息的情况
            if (hasReplyMessage(messageChain)) {
                processReplyMessage(messageChain, message, result);
            } else {
                // 处理直接查询的情况
                processDirectQuery(message, result,bot);
            }

            // 如果计算结果有效，发送消息
            if (result.count > 0L) {
                sendResultMessage(group, messageId, message, result);
            }
        } catch (Exception e) {
            logger.error("处理上架价格查询时出错", e);
        }
    }

    // 辅助方法类
    private class PriceCalculationResult {
        long count = 0L;
        long totalFee = 0L;
        StringBuilder stringBuilder = new StringBuilder();
    }

    // 关键词检查
    private boolean containsAnyKeywords(String message) {
        String[] keywords = {"价格", "上架", "查询", "坊市", "炼金"};
        return Arrays.stream(keywords).anyMatch(message::contains);
    }

    // 检查是否有回复消息
    private boolean hasReplyMessage(MessageChain messageChain) {
        List<ReplyMessage> replyMessageList = messageChain.getMessageByType(ReplyMessage.class);
        return replyMessageList != null && !replyMessageList.isEmpty();
    }

    // 处理回复消息
    private void processReplyMessage(MessageChain messageChain, String message, PriceCalculationResult result) {
        ReplyMessage replyMessage = messageChain.getMessageByType(ReplyMessage.class).get(0);
        MessageChain replyMessageChain = replyMessage.getChain();

        if (replyMessageChain != null) {
            List<TextMessage> textMessageList = replyMessageChain.getMessageByType(TextMessage.class);
            if (textMessageList != null && !textMessageList.isEmpty()) {
                processTextMessage(textMessageList.get(textMessageList.size() - 1).getText(), message, result);
            }
        }
    }

    // 处理文本消息内容
    private void processTextMessage(String text, String originalMessage, PriceCalculationResult result) {
        String[] lines = text.split("\n");

        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i];
            if (isItemLine(line)) {
                String name = extractItemName(line);
                if (StringUtils.isNotBlank(name)) {
                    int quantity = extractQuantity(lines[i + 1]);
                    processItem(name, quantity, originalMessage, result);
                }
            }
        }

        addSummaryInfo(originalMessage, result);
    }

    // 判断是否是物品行
    private boolean isItemLine(String line) {
        return line.startsWith("名字：") || line.startsWith("上品") || line.startsWith("下品")
                || line.startsWith("极品") || line.startsWith("无上仙器")
                || line.endsWith("功法") || line.endsWith("神通");
    }

    // 提取物品名称
    private String extractItemName(String line) {
        String name = "";
        if (line.startsWith("名字：")) {
            name = line.substring(3).trim();
        } else if (line.startsWith("上品") || line.startsWith("下品")
                || line.startsWith("极品") || line.startsWith("无上仙器")) {
            name = line.substring(4).trim();
        } else if (line.endsWith("功法") || line.endsWith("神通")) {
            name = line.contains("辅修") ?
                    line.substring(0, line.length() - 8).trim() :
                    line.substring(0, line.length() - 6).trim();
        }

        if (name.startsWith("法器")) {
            name = name.substring(2);
        }

        return name.replaceAll("\\s", "");
    }

    // 提取数量
    private int extractQuantity(String line) {
        line = line.replace("已装备", "");
        if (line.contains("拥有数量")) {
            Matcher matcher = Pattern.compile("\\d+").matcher(line);
            return matcher.find() ? Integer.parseInt(matcher.group()) : 1;
        }
        return 1;
    }

    // 处理单个物品
    private void processItem(String name, int quantity, String originalMessage, PriceCalculationResult result) {
        if (originalMessage.contains("炼金")) {
            processRefiningItem(name, quantity, result);
        } else {
            processMarketItem(name, quantity, result);
        }
    }

    // 处理炼金物品
    private void processRefiningItem(String name, int quantity, PriceCalculationResult result) {
        int price = ProductLowPrice.getLowPrice(name);
        if (price > 0) {
            long totalPrice = (long) price * quantity;
            result.count += totalPrice;
            result.stringBuilder.append("炼金 ")
                    .append(name).append(" ")
                    .append(quantity).append(" 总价:")
                    .append(totalPrice).append("万\n");
        }
    }

    // 处理市场物品
    private void processMarketItem(String name, int quantity, PriceCalculationResult result) {
        ProductPrice product = productPriceResponse.getFirstByNameOrderByTimeDesc(name);
        if (product != null) {
            int adjustedPrice = calculateAdjustedPrice(product.getPrice());
            double feeRate = calculateFeeRate(adjustedPrice);
            long itemFee = (long) (adjustedPrice * feeRate * quantity);

            result.totalFee += itemFee;
            adjustedPrice = applyPriceAdjustments(adjustedPrice);
            result.count += (long) adjustedPrice * quantity;

            generateMarketListingCommands(result.stringBuilder, product.getName(), adjustedPrice, quantity);
        }
    }

    // 计算调整后价格
    private int calculateAdjustedPrice(int originalPrice) {
        return originalPrice - 10; // 基础调整
    }

    // 计算手续费率
    private double calculateFeeRate(int price) {
        if (price <= 500) return 0.05;
        if (price <= 1000) return 0.1;
        if (price <= 1500) return 0.15;
        if (price <= 2000) return 0.2;
        return 0.3;
    }

    // 应用价格调整规则
    private int applyPriceAdjustments(int price) {
        if (price < 60) return 60;

        double discountedPrice = price;
        if (price > 500 && price <= 1000) {
            discountedPrice = price * 0.9;
            if (discountedPrice <= 475.0) return 500;
        } else if (price > 1000 && price <= 1500) {
            discountedPrice = price * 0.85;
            if (discountedPrice <= 900.0) return 1000;
        } else if (price > 1500 && price <= 2000) {
            discountedPrice = price * 0.8;
            if (discountedPrice <= 1600.0) return 2000;
        }

        return price;
    }

    // 生成市场上市命令
    private void generateMarketListingCommands(StringBuilder sb, String name, int price, int quantity) {
        if (quantity > 10 && quantity < 50) {
            int batches = quantity / 10;
            int remainder = quantity % 10;

            for (int j = 0; j < batches; j++) {
                sb.append("确认坊市上架 ")
                        .append(name).append(" ")
                        .append((long) price * 10000L).append(" ")
                        .append(10).append("\n");
            }

            if (remainder > 0) {
                sb.append("确认坊市上架 ")
                        .append(name).append(" ")
                        .append((long) price * 10000L).append(" ")
                        .append(remainder).append("\n");
            }
        } else {
            sb.append("确认坊市上架 ")
                    .append(name).append(" ")
                    .append((long) price * 10000L).append(" ")
                    .append(quantity).append("\n");
        }
    }

    // 添加摘要信息
    private void addSummaryInfo(String originalMessage, PriceCalculationResult result) {
        if (!originalMessage.contains("炼金")) {
            result.stringBuilder.append("\n使用前请先@小小查看坊市药材\n自动生成坊市价格-10w\n已为您适配最优上架价格\n");
        } else {
            result.stringBuilder.append("\n");
        }
    }

    // 处理直接查询
    private void processDirectQuery(String message, PriceCalculationResult result,Bot bot) {
        String cleanMessage = message.replace("@" + bot.getBotId(), "").replace("查上架价格", "");
        Arrays.stream(cleanMessage.split("\n"))
                .map(line -> line.replaceAll("\\s", ""))
                .filter(StringUtils::isNotBlank)
                .forEach(line -> processSingleQueryItem(line.trim(), result));

        if (result.count > 0L) {
            addSummaryInfo(message, result);
        }
    }

    // 处理单个查询物品
    private void processSingleQueryItem(String itemName, PriceCalculationResult result) {
        ProductPrice product = productPriceResponse.getFirstByNameOrderByTimeDesc(itemName);
        if (product != null) {
            int adjustedPrice = calculateAdjustedPrice(product.getPrice());
            double feeRate = calculateFeeRate(adjustedPrice);

            result.totalFee += (long) (adjustedPrice * feeRate);
            adjustedPrice = applyPriceAdjustments(adjustedPrice);
            result.count += adjustedPrice;

            result.stringBuilder.append("\n确认坊市上架 ")
                    .append(product.getName()).append(" ")
                    .append((long) adjustedPrice * 10000L);
        }
    }

    // 发送结果消息
    private void sendResultMessage(Group group, Integer messageId, String originalMessage, PriceCalculationResult result) {
        String totalValueStr = formatAmount(result.count, "总价值");

        if (originalMessage.contains("炼金")) {
            result.stringBuilder.append("\n").append(totalValueStr);
        } else {
            String feeStr = formatAmount(result.totalFee, "手续费");
            String netAmountStr = formatAmount(result.count - result.totalFee, "实际到手");
            result.stringBuilder.append("\n").append(totalValueStr)
                    .append("\n").append(feeStr)
                    .append("\n").append(netAmountStr);
        }

        group.sendMessage(new MessageChain().reply(messageId).text(result.stringBuilder.toString()));
    }

    // 格式化金额显示
    private String formatAmount(long amount, String prefix) {
        return amount > 10000L ?
                String.format("%s：%.2f 亿", prefix, amount / 10000.0) :
                String.format("%s：%d 万", prefix, amount);
    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 猜成语(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (bot.getBotConfig().isEnableGuessTheIdiom() && group.getGroupId() != 665980114L && message.contains("看表情猜成语")) {
            Iterator var7 = messageChain.getMessageByType(TextMessage.class).iterator();

            while(var7.hasNext()) {
                TextMessage textMessage = (TextMessage)var7.next();
                message = textMessage.getText();
                if (message.contains("题目：")) {
                    String emoji = message.substring(message.indexOf("题目：") + 3).trim();
                    String idiom = GuessIdiom.getIdiom(emoji);
                    if (StringUtils.isNotBlank(idiom)) {
                        group.sendMessage((new MessageChain()).text(idiom));
                    }
                }
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 妖塔猜成语(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (message.contains("" + bot.getBotId()) && message.contains("看表情猜成语")) {
            Iterator var7 = messageChain.getMessageByType(TextMessage.class).iterator();

            while(var7.hasNext()) {
                TextMessage textMessage = (TextMessage)var7.next();
                message = textMessage.getText();
                if (message.contains("题目：")) {
                    String emoji = message.substring(message.indexOf("题目：") + 3).trim();
                    String idiom = GuessIdiom.getIdiom(emoji);
                    if (StringUtils.isNotBlank(idiom)) {
                        group.sendMessage((new MessageChain()).at("3889001741").text("猜成语" + idiom));
                    }
                }
            }
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 猜灯谜(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (message.contains("谜面")) {
            String idiom = GuessIdiom.getRiddle(message);
            if (StringUtils.isNotBlank(idiom)) {
                if (bot.getBotConfig().isEnableGuessTheIdiom()) {
                    group.sendMessage((new MessageChain()).text(idiom));
                }

                if (message.contains("" + bot.getBotId())) {
                    group.sendMessage((new MessageChain()).at("3889001741").text("灯谜答案" + idiom));
                }
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 保存商品价格(Bot bot, Group group, String message, Integer messageId) throws InterruptedException {
        if (message.contains("不鼓励不保障任何第三方交易行为") && !message.contains("下架")) {
            String[] split = message.split("\n");
            LocalDateTime now = LocalDateTime.now();
            customPool.submit(() -> {
                String[] var5 = split;
                int var6 = split.length;

                for(int var7 = 0; var7 < var6; ++var7) {
                    String s = var5[var7];
                    if (s.startsWith("价格") && s.contains("mqqapi")) {
                        BotConfig botConfig = bot.getBotConfig();
                        long groupId = botConfig.getGroupId();
                        if (botConfig.getTaskId() != 0L) {
                            groupId = botConfig.getTaskId();
                        }

                        String[] split1 = s.split("\\[|\\]");
                        String code = s.split("%E5%9D%8A%E5%B8%82%E8%B4%AD%E4%B9%B0|&")[1];
                        double price = Double.MAX_VALUE;
                        String itemName = split1[1].trim();
                        StringBuilder result = new StringBuilder();
                        char[] var17 = itemName.toCharArray();
                        int var18 = var17.length;

                        for(int var19 = 0; var19 < var18; ++var19) {
                            char c = var17[var19];
                            if (Character.toString(c).matches("[\\u4e00-\\u9fa5()（）]")) {
                                result.append(c);
                            }
                        }

                        itemName = result.toString();
                        String[] split2;
                        if (s.contains("万 [")) {
                            split2 = s.split("价格:|万");
                            price = Double.parseDouble(split2[1]);
                        } else if (s.contains("亿 [")) {
                            split2 = s.split("价格:|亿");
                            price = Double.parseDouble(split2[1]) * 10000.0;
                        }

                        ProductPrice productPrice = new ProductPrice();
                        productPrice.setName(itemName);
                        productPrice.setPrice((int)price);
                        productPrice.setCode(code);
                        productPrice.setTime(now);
                        Map<String, ProductPrice> productMap = (Map)this.groupManager.autoBuyProductMap.computeIfAbsent(bot.getBotId()+"", (k) -> {
                            return new ConcurrentHashMap();
                        });
                        ProductPrice existingProduct = (ProductPrice)productMap.get(itemName);
                        ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(productPrice.getName());
                        if (first != null) {
                            if ((double)first.getPrice() != price) {
                                first.setPrice((int)price);
                                first.setCode(code);
                                first.setTime(LocalDateTime.now());
                            }
                        } else {
                            first = productPrice;
                        }

                        this.productPriceResponse.save(first);
//                        if (existingProduct != null && price <= (double)existingProduct.getPrice()) {
//                            if (botConfig.isStop()) {
//                                botConfig.setStop(false);
//                                return;
//                            }
//
//                            if (group.getGroupId() == groupId) {
//                                group.sendMessage((new MessageChain()).at("3889001741").text(" 坊市购买 " + code));
//                            }
//
//                        }

//                        if (botConfig.isEnableAutoBuyLowPrice() && price < (double)ProductLowPrice.getLowPrice(itemName)) {
//                            if (botConfig.isStop()) {
//                                botConfig.setStop(false);
//                                return;
//                            }
//                            group.sendMessage((new MessageChain()).at("3889001741").text(" 坊市购买 " + code));
//                        }
                    }
                }

            });
        }

    }
}
