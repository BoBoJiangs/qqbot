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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
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


@Component
public class PriceTask {
    @Autowired
    private ProductPriceResponse productPriceResponse;
    private static final ForkJoinPool customPool = new ForkJoinPool(20);
    public static  String targetDir = "./";
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
        if (message.equals("同步坊市价格")) {
            this.savePrice(group);
        }

    }

    private void readPrice() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(targetDir+"properties/坊市价格.txt"));

            try {
                StringBuilder jsonStr = new StringBuilder();

                String line;
                while((line = reader.readLine()) != null) {
                    jsonStr.append(line);
                }

                List<ProductPrice> personList = (List)JSON.parseObject(jsonStr.toString(), new TypeReference<List<ProductPrice>>() {
                }, new JSONReader.Feature[0]);
                if (this.productPriceResponse != null) {

                    this.productPriceResponse.saveAll(personList);
                    System.out.println("坊市价格读取成功！" + personList.size());
                } else {
                    System.out.println("********！");
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
        if (bot.getBotConfig().isEnableXslPriceQuery()) {
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

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 自动查悬赏令价格(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if ((group.getGroupId() != 730651710L || bot.getBotId() == 3860863656L) && bot.getBotConfig().isEnableXslPriceQuery() && message.contains("道友的个人悬赏令")) {
            Iterator var7 = messageChain.getMessageByType(TextMessage.class).iterator();

            while(true) {
                do {
                    if (!var7.hasNext()) {
                        return;
                    }

                    TextMessage textMessage = (TextMessage)var7.next();
                    message = textMessage.getText();
                } while(!message.contains("道友的个人悬赏令"));

                Pattern pattern = Pattern.compile("完成几率(\\d+),基础报酬(\\d+)修为.*?可能额外获得：[^:]+:(.*?)!");
                Matcher matcher = pattern.matcher(message);
                StringBuilder stringBuilder = new StringBuilder();
                int count = 0;
                int maxPriceIndex = 0;
                int maxPrice = 0;

                int maxCultivateIndex = 0;
                long maxCultivate = 0;


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
                        }
                        if (cultivation > maxCultivate) {
                            maxCultivate = cultivation;
                            maxCultivateIndex = count;
                        }
                        if (first != null) {
                            if (first.getPrice() > maxPrice) {
                                maxPrice = first.getPrice();
                                maxPriceIndex = count;
                            }
                            stringBuilder.append("\n\uD83C\uDF81悬赏令").append(count).append(" 奖励：").append(first.getName()).append(" 价格:").append(first.getPrice()).append("万")
                                    .append("(炼金:").append(ProductLowPrice.getLowPrice(first.getName())).append("万)");
                        }
                    }
                }
//                stringBuilder.append("\n\n最高修为:接取悬赏令" + maxCultivateIndex + "\n最高价格:接取悬赏令" + maxPriceIndex );
                stringBuilder.append("\n\n最高修为:悬赏令" + maxCultivateIndex +"(修为" + formatCultivation(maxCultivate)+")");
                stringBuilder.append("\n最高价格:悬赏令" + maxPriceIndex +"(价格" + maxPrice + "万)");
                if (stringBuilder.length() > 5) {
                    stringBuilder.insert(0, "悬赏令价格查询：");
                    group.sendMessage((new MessageChain()).text(stringBuilder.toString()));
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
        if (bot.getBotConfig().isEnableCheckPrice() && (message.contains("价格") || message.contains("上架") || message.contains("查询") || message.contains("坊市") || message.contains("炼金"))) {
            StringBuilder stringBuilder = new StringBuilder();
            long count = 0L;
            List<ReplyMessage> replyMessageList = messageChain.getMessageByType(ReplyMessage.class);
            String s;
            if (replyMessageList != null && !replyMessageList.isEmpty()) {
                ReplyMessage replyMessage = (ReplyMessage)replyMessageList.get(0);
                MessageChain replyMessageChain = replyMessage.getChain();
                if (replyMessageChain != null) {
                    List<TextMessage> textMessageList = replyMessageChain.getMessageByType(TextMessage.class);
                    if (textMessageList != null && !textMessageList.isEmpty()) {
                        TextMessage textMessage = (TextMessage)textMessageList.get(textMessageList.size() - 1);
                        s = textMessage.getText();
                        String[] lines = s.split("\n");

                        String line;
                        for(int i = 0; i < lines.length - 1; ++i) {
                            line = lines[i];
                            if (line.startsWith("名字：") || line.startsWith("上品") || line.startsWith("下品") || line.startsWith("极品") || line.startsWith("无上仙器") || line.endsWith("功法") || line.endsWith("神通")) {
                                String name = "";
                                if (line.startsWith("名字：")) {
                                    name = line.substring(3).trim();
                                } else if (!line.endsWith("功法") && !line.endsWith("神通")) {
                                    if (line.startsWith("上品") || line.startsWith("下品") || line.startsWith("极品") || line.startsWith("无上仙器")) {
                                        name = line.substring(4).trim();
                                    }
                                } else if (line.contains("辅修")) {
                                    name = line.substring(0, line.length() - 8).trim();
                                } else {
                                    name = line.substring(0, line.length() - 6).trim();
                                }

                                if (name.startsWith("法器")) {
                                    name = name.substring(2);
                                }

                                lines[i + 1] = lines[i + 1].replace("已装备", "");
                                int quantity = 1;
                                if (lines[i + 1].contains("拥有数量")) {
                                    Pattern pattern = Pattern.compile("\\d+");
                                    Matcher matcher = pattern.matcher(lines[i + 1]);
                                    if (matcher.find()) {
                                        String numberStr = matcher.group();
                                        quantity = Integer.parseInt(numberStr);
                                    }
                                }

                                name = name.replaceAll("\\s", "");
                                if (StringUtils.isNotBlank(name)) {
                                    if (message.contains("炼金")) {
                                        int price = ProductLowPrice.getLowPrice(name);
                                        if (price > 0) {
                                            count += (long)price * (long)quantity;
                                            stringBuilder.append("炼金 ").append(name).append(" ").append(quantity).append(" 总价:").append((long)price * (long)quantity).append("万");
                                            stringBuilder.append("\n");
                                        }
                                    } else {
                                        ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(name.replaceAll("\\s", ""));
                                        if (first != null) {
                                            count += ((long)first.getPrice() - 10L) * (long)quantity;
                                            if (quantity < 5) {
                                                for(int j = 0; j < quantity; ++j) {
                                                    stringBuilder.append("确认坊市上架 ").append(first.getName()).append(" ").append((long)first.getPrice() * 10000L);
                                                    stringBuilder.append("\n");
                                                }
                                            } else {
                                                stringBuilder.append("确认坊市上架 ").append(first.getName()).append(" ").append((long)first.getPrice() * 10000L);
                                                stringBuilder.append("\n");
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        line = "\n使用前请先@小小查看坊市药材\n自动生成坊市价格\n";
                        if (!message.contains("炼金")) {
                            stringBuilder.append(line);
                        } else {
                            stringBuilder.append("\n");
                        }

                        if (count > 10000L) {
                            stringBuilder.append("总价值：" + String.format("%.2f", (double)count / 10000.0) + " 亿");
                        } else {
                            stringBuilder.append("总价值：" + count + " 万");
                        }

                        if (count == 0L) {
                            return;
                        }

                        group.sendMessage((new MessageChain()).reply(messageId).text(stringBuilder.toString()));
                        return;
                    }
                }
            }

            message = message.replace("@" + bot.getBotId(), "").replace("查上架价格", "");
            String[] split = message.split("\n");
            String[] var27 = split;
            int var28 = split.length;

            for(int var15 = 0; var15 < var28; ++var15) {
                s = var27[var15].replaceAll("\\s", "");
                if (StringUtils.isNotBlank(s)) {
                    ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(s.trim());
                    if (first != null) {
                        count += (long)(first.getPrice() - 10);
                        stringBuilder.append("\n确认坊市上架 ").append(first.getName()).append(" ").append((long)(first.getPrice() - 10) * 10000L);
                    }
                }
            }

            if (count == 0L) {
                return;
            }

            group.sendMessage((new MessageChain()).reply(messageId).text(stringBuilder.toString()));
        }

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
                        Map<String, ProductPrice> productMap = (Map)TestService.AUTO_BUY_PRODUCT.computeIfAbsent(bot.getBotId(), (k) -> {
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
                        if (existingProduct != null && price <= (double)existingProduct.getPrice()) {
                            if (botConfig.isStop()) {
                                botConfig.setStop(false);
                                return;
                            }

                            if (group.getGroupId() == groupId) {
                                group.sendMessage((new MessageChain()).at("3889001741").text(" 坊市购买 " + code));
                            }
                        }

                        if (botConfig.isEnableAutoBuyLowPrice() && price < (double)ProductLowPrice.getLowPrice(itemName)) {
                            if (botConfig.isStop()) {
                                botConfig.setStop(false);
                                return;
                            }

                            if (group.getGroupId() == groupId) {
                                group.sendMessage((new MessageChain()).at("3889001741").text(" 坊市购买 " + code));
                            }
                        }
                    }
                }

            });
        }

    }
}
