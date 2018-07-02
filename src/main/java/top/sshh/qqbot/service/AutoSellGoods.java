package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import top.sshh.qqbot.constant.Constant;
import top.sshh.qqbot.data.ProductLowPrice;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.HerbBackpackParser;
import top.sshh.qqbot.service.utils.Utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AutoSellGoods {
    private Map<Long, List<ProductPrice>> herbPackMap = new ConcurrentHashMap();
    private Map<Long, List<ProductPrice>> equipPackMap = new ConcurrentHashMap();
    private Map<Long, List<ProductPrice>> pillPackMap = new ConcurrentHashMap();
    @Autowired
    private ProductPriceResponse productPriceResponse;
    @Value("${xxGroupId:0}")
    private Long xxGroupId;
    @Autowired
    private GroupManager groupManager;

    public AutoSellGoods() {

    }

    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.ONLY_ITSELF)
    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message,
            Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        long groupId = botConfig.getGroupId();
        message = message.trim();
        if (StringUtils.isEmpty(message)) {
            return;
        }
        if ("批量上架药材".equals(message)) {
            botConfig.setCommand("批量上架药材");
            botConfig.setPage(1);
            herbPackMap.clear();
            Utils.sendGroupMessage(bot, groupId, (new MessageChain()).at("3889001741").text("药材背包"));
        }
        if ("批量炼金装备".equals(message)) {
            botConfig.setCommand("批量炼金装备");
            botConfig.setPage(1);
            equipPackMap.clear();
            Utils.sendGroupMessage(bot, groupId, (new MessageChain()).at("3889001741").text("我的背包"));
        }
        if ("批量炼金丹药".equals(message)) {
            botConfig.setCommand("批量炼金丹药");
            botConfig.setPage(1);
            pillPackMap.clear();
            Utils.sendGroupMessage(bot, groupId, (new MessageChain()).at("3889001741").text("丹药背包"));
        }
        if ("停止执行".equals(message)) {
            botConfig.setCommand("");
            botConfig.setPage(1);
            herbPackMap.clear();
            equipPackMap.clear();
            pillPackMap.clear();
        }
    }

    @GroupMessageHandler(senderIds = { 3889001741L })
    public void 成功上架药材(Bot bot, Group group, Member member, MessageChain messageChain, String message,
            Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isAtSelf = Utils.isAtSelf(bot, group, message, xxGroupId);
        boolean isSellResult = message.contains("上架价格过高") || message.contains("上架价格过低")
                || message.contains("物品成功上架坊市")
                || message.contains("道友的上一条指令还没执行完") || message.contains("操作失败")
                || message.contains("物品数量不足");
        boolean isBatchSell = "批量上架药材".equals(botConfig.getCommand());
        if (isAtSelf && isSellResult && isBatchSell) {
            List<ProductPrice> autoBuyList = herbPackMap.get(bot.getBotId());
            if (autoBuyList != null && !autoBuyList.isEmpty()) {
                ProductPrice current = autoBuyList.get(0);
                if (message.contains("上架价格过高") || message.contains("上架价格过低")) {
                    String priceMessage = message.contains("上架价格过高") ? "上架价格过高" : "上架价格过低";
                    group.sendMessage(new MessageChain().text("药材：" + current.getName() + priceMessage + "，已跳过"));
                }
                autoBuyList.remove(0);
            }
            if (autoBuyList != null && !autoBuyList.isEmpty()) {
                this.buyHerbs(autoBuyList, group, bot.getBotConfig());
            } else {
                finishHerbSelling(group, botConfig);
            }

        }

    }

    @GroupMessageHandler(senderIds = { 3889001741L })
    public void 成功炼金装备(Bot bot, Group group, Member member, MessageChain messageChain, String message,
            Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        if (Utils.isAtSelf(bot, group, message, xxGroupId)
                && (message.contains("炼金成功") || message.contains("道友的上一条指令还没执行完")
                        || message.contains("操作失败") || message.contains("物品数量不足"))
                && "批量炼金装备".equals(botConfig.getCommand())) {
            List<ProductPrice> dataList = equipPackMap.get(bot.getBotId());
            if (dataList != null && !dataList.isEmpty()) {
                dataList.remove(0);
            }
            if (dataList != null && dataList.isEmpty()) {
                Utils.getRemindGroup(bot, xxGroupId).sendMessage(new MessageChain().text("装备炼金完成"));
                botConfig.setCommand("");
            } else {
                this.alchemyEquip(dataList, group, botConfig);
            }

        }

    }

    @GroupMessageHandler(senderIds = { 3889001741L })
    public void 成功炼金丹药(Bot bot, Group group, Member member, MessageChain messageChain, String message,
            Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        if (Utils.isAtSelf(bot, group, message, xxGroupId)
                && (message.contains("炼金成功") || message.contains("道友的上一条指令还没执行完")
                        || message.contains("操作失败") || message.contains("物品数量不足"))
                && "批量炼金丹药".equals(botConfig.getCommand())) {
            List<ProductPrice> dataList = pillPackMap.get(bot.getBotId());
            if (dataList != null && !dataList.isEmpty()) {
                dataList.remove(0);
            }
            if (dataList != null && dataList.isEmpty()) {
                Utils.getRemindGroup(bot, xxGroupId).sendMessage(new MessageChain().text("丹药炼金完成"));
                botConfig.setCommand("");
            } else {
                this.alchemyPill(dataList, group, botConfig);
            }

        }

    }

    @GroupMessageHandler(senderIds = { 3889001741L })
    public void 药材背包(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId)
            throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        boolean isAtSelf = Utils.isAtSelf(bot, group, message, xxGroupId);
        boolean isBackpackMessage = message.contains("上一页") || message.contains("下一页")
                || message.contains("药材背包");
        boolean isBatchSell = "批量上架药材".equals(botConfig.getCommand());
        List<TextMessage> textMessages = messageChain.getMessageByType(TextMessage.class);
        if (isAtSelf && isBackpackMessage && isBatchSell) {
            if (textMessages == null || textMessages.isEmpty()) {
                return;
            }
            boolean hasNextPage = false;
            TextMessage textMessage = null;
            if (textMessages.size() > 1) {
                textMessage = (TextMessage) textMessages.get(textMessages.size() - 1);
            } else {
                textMessage = (TextMessage) textMessages.get(0);
            }

            if (textMessage != null) {
                String msg = StringUtils.defaultString(textMessage.getText());
                // 新版消息正文可能只在 TextMessage 中，不能只检查注入的 message 参数。
                boolean isHerbContent = msg.contains("炼金") && msg.contains("坊市数据");
                if (isHerbContent) {
                    String[] lines = msg.split("\n");
                    this.parseHerbList(Arrays.asList(lines), bot);
                    if (msg.contains("下一页")) {
                        hasNextPage = true;
                    }
                }

                if (hasNextPage) {
                    botConfig.setPage(botConfig.getPage() + 1);
                    Utils.sendGroupMessage(group.getBot(), group.getGroupId(), (new MessageChain()).at("3889001741").text("药材背包" + botConfig.getPage()));
                } else {
                    buyHerbs(this.herbPackMap.get(bot.getBotId()), group, botConfig);

                }
            }
        }

    }

    @GroupMessageHandler(senderIds = { 3889001741L })
    public void 装备背包(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId)
            throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        if (Utils.isAtSelf(bot, group, message, xxGroupId) && (message.contains("上一页") || message.contains("下一页")
                || (message.contains("的背包") && message.contains("持有灵石"))) && "批量炼金装备".equals(botConfig.getCommand())) {
            List<TextMessage> textMessages = messageChain.getMessageByType(TextMessage.class);
            boolean hasNextPage = false;
            TextMessage textMessage = null;
            if (textMessages.size() > 1) {
                textMessage = (TextMessage) textMessages.get(textMessages.size() - 1);
            } else {
                textMessage = (TextMessage) textMessages.get(0);
            }

            if (textMessage != null) {
                String msg = textMessage.getText();
                if (message.contains("炼金") && message.contains("坊市数据")) {
                    String[] lines = msg.split("\n");
                    this.parseEquipList(lines, bot);
                    if (msg.contains("下一页")) {
                        hasNextPage = true;
                    }
                }

                if (hasNextPage) {
                    botConfig.setPage(botConfig.getPage() + 1);
                    Utils.sendGroupMessage(group.getBot(), group.getGroupId(), (new MessageChain()).at("3889001741").text("我的背包" + botConfig.getPage()));
                } else {
                    alchemyEquip(this.equipPackMap.get(bot.getBotId()), group, botConfig);

                }
            }
        }

    }

    @GroupMessageHandler(senderIds = { 3889001741L })
    public void 丹药背包(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId)
            throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        if (Utils.isAtSelf(bot, group, message, xxGroupId)
                && (message.contains("上一页") || message.contains("下一页") || message.contains("丹药背包"))
                && "批量炼金丹药".equals(botConfig.getCommand())) {
            List<TextMessage> textMessages = messageChain.getMessageByType(TextMessage.class);
            boolean hasNextPage = false;
            TextMessage textMessage = null;
            if (textMessages.size() > 1) {
                textMessage = (TextMessage) textMessages.get(textMessages.size() - 1);
            } else {
                textMessage = (TextMessage) textMessages.get(0);
            }

            if (textMessage != null) {
                String msg = textMessage.getText();
                if (message.contains("炼金")) {
                    String[] lines = msg.split("\n");
                    this.parsePillList(Arrays.asList(lines), bot);
                    if (msg.contains("下一页")) {
                        hasNextPage = true;
                    }
                }

                if (hasNextPage) {
                    botConfig.setPage(botConfig.getPage() + 1);
                    Utils.sendGroupMessage(group.getBot(), group.getGroupId(), (new MessageChain()).at("3889001741").text("丹药背包" + botConfig.getPage()));
                } else {
                    if (this.pillPackMap.get(bot.getBotId()) != null) {
                        alchemyPill(this.pillPackMap.get(bot.getBotId()), group, botConfig);
                    }

                }
            }
        }

    }

    public void parseEquipList(String[] lines, Bot bot) throws Exception {

        List<ProductPrice> productPrices = this.equipPackMap.get(bot.getBotId());
        if (productPrices == null) {
            productPrices = new ArrayList<>();
        }
        for (int i = 0; i < lines.length - 1; ++i) {

            String line = lines[i];
            if (line.contains("极品") || line.contains("无上") || line.contains("辅修")) {
                continue;
            }
            // SnowLuma 下装备名为 markdown 链接 [上品法器xxx](mqqapi://...)，剥离链接恢复原始格式
            line = Utils.stripMarkdownLink(line.trim());
            String name = "";
            if (!line.endsWith("功法") && !line.endsWith("神通")) {
                if (line.startsWith("上品") || line.startsWith("下品") || line.startsWith("极品")
                        || line.startsWith("无上仙器")) {
                    name = line.substring(4).trim();
                }
            } else if (line.contains("辅修")) {
                name = line.substring(0, line.length() - 8).trim();
            } else if (!line.startsWith("极品神通")) {
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
            if (!this.groupManager.isAlchemyExcluded(bot.getBotId(), name)) {
                if (!StringUtils.isBlank(name)) {
                    ProductPrice productPrice = new ProductPrice();
                    productPrice.setName(name);
                    productPrice.setHerbCount(quantity);
                    productPrices.add(productPrice);
                    this.equipPackMap.put(bot.getBotId(), productPrices);
                }
            }

        }

    }

    public void parseHerbList(List<String> medicinalList, Bot bot) throws Exception {
        String currentHerb = null;
        Iterator var2 = medicinalList.iterator();

        while (var2.hasNext()) {
            String line = (String) var2.next();
            line = line.trim();
            HerbBackpackParser.Entry inlineEntry = HerbBackpackParser.parseInlineEntry(line);
            if (inlineEntry != null) {
                herbsCountLimit10(bot, inlineEntry.getCount(), inlineEntry.getName());
                currentHerb = null;
                continue;
            }

            if (line.contains("名字：")) {
                // SnowLuma 下药名为 markdown 链接 [名字](mqqapi://...)，剥离链接保留药名
                currentHerb = Utils.stripMarkdownLink(line.replaceAll("名字\\s*[:：]", ""))
                        .replaceAll("\\s+", "");
            } else if (currentHerb != null && line.contains("拥有数量")) {
                int count = Utils.parseHerbCount(line);
                if (count >= 0) {
                    herbsCountLimit10(bot, count, currentHerb);
                }
                currentHerb = null;
            }
        }

    }

    public void parsePillList(List<String> pillList, Bot bot) throws Exception {
        String currentPill = null;
        Iterator var2 = pillList.iterator();

        while (var2.hasNext()) {
            String line = (String) var2.next();
            line = line.trim();
            HerbBackpackParser.Entry inlineEntry = HerbBackpackParser.parseInlineEntry(line);
            if (inlineEntry != null) {
                pillsCountLimit(bot, inlineEntry.getCount(), inlineEntry.getName());
                currentPill = null;
                continue;
            }

            if (line.contains("名字：") || line.contains("名字:")) {
                // SnowLuma 下药名为 markdown 链接 [名字](mqqapi://...)，剥离链接保留药名
                currentPill = Utils.stripMarkdownLink(line.replaceAll("名字\\s*[:：]", ""))
                        .replaceAll("\\s+", "");
            } else if (currentPill != null && line.contains("拥有数量")) {
                int count = Utils.parseHerbCount(line);
                if (count >= 0) {
                    pillsCountLimit(bot, count, currentPill);
                }
                currentPill = null;
            }
        }

    }

    private void pillsCountLimit(Bot bot, int quantity, String currentPill) {
        boolean b = !"渡厄丹,寒铁铸心炉,陨铁炉,雕花紫铜炉".contains(currentPill);
        boolean isMakeDan = !Constant.MAKE_DAN_SET.contains(currentPill);
        if (b && isMakeDan
                && !this.groupManager.isAlchemyExcluded(bot.getBotId(), currentPill)) {
            List<ProductPrice> productPrices = this.pillPackMap.get(bot.getBotId());
            if (productPrices == null) {
                productPrices = new ArrayList<>();
            }
            ProductPrice productPrice = new ProductPrice();
            productPrice.setName(currentPill);
            productPrice.setHerbCount(quantity);
            productPrices.add(productPrice);
            this.pillPackMap.put(bot.getBotId(), productPrices);
        }

    }

    private void herbsCountLimit10(Bot bot, int quantity, String currentHerb) {

        if (!this.groupManager.isSellExcluded(bot.getBotId(), currentHerb)) {
            int remaining = quantity;
            while (remaining > 0) {
                int batchSize = Math.min(10, remaining); // 本次上架数量，最多10个
                List<ProductPrice> productPrices = this.herbPackMap.get(bot.getBotId());
                if (productPrices == null) {
                    productPrices = new ArrayList<>();
                }
                ProductPrice productPrice = new ProductPrice();
                productPrice.setName(currentHerb);
                productPrice.setHerbCount(batchSize);
                productPrices.add(productPrice);
                this.herbPackMap.put(bot.getBotId(), productPrices);
                remaining -= batchSize;

            }
        }

    }

    private void buyHerbs(List<ProductPrice> autoBuyList, Group group, BotConfig botConfig) {
        if (autoBuyList == null || autoBuyList.isEmpty()) {
            finishHerbSelling(group, botConfig);
            return;
        }

        while (!autoBuyList.isEmpty()) {
            ProductPrice productPrice = autoBuyList.get(0);
            try {
                if (StringUtils.isEmpty(botConfig.getCommand())) {
                    return;
                }
                ProductPrice first = this.productPriceResponse
                        .getFirstByNameOrderByTimeDesc(productPrice.getName().trim());
                if (first == null) {
                    group.sendMessage(new MessageChain().text("药材：" + productPrice.getName()
                            + "未找到坊市价格，已跳过"));
                    autoBuyList.remove(0);
                    continue;
                }

                if ((double) first.getPrice() < (double) ProductLowPrice.getLowPrice(productPrice.getName())
                        * 1.1) {
                    Utils.sendGroupMessage(group.getBot(), group.getGroupId(), (new MessageChain()).at("3889001741")
                            .text("炼金 " + first.getName() + " " + productPrice.getHerbCount()));
                    group.sendMessage((new MessageChain()).text("物品：" + first.getName() + "市场价：" + first.getPrice()
                            + "万，炼金：" + ProductLowPrice.getLowPrice(first.getName()) + "万，直接炼金处理。"));
                    autoBuyList.remove(0);
                    continue;
                }

                long delayMs = ThreadLocalRandom.current().nextLong(1000L, 2001L);
                Thread.sleep(delayMs);
                Utils.sendGroupMessage(group.getBot(), group.getGroupId(), (new MessageChain()).at("3889001741")
                        .text("确认坊市上架 " + first.getName() + " " + (first.getPrice() - 10) * 10000 + " "
                                + productPrice.getHerbCount()));
                return;
            } catch (Exception var6) {
                return;
            }
        }
        finishHerbSelling(group, botConfig);
    }

    private void finishHerbSelling(Group group, BotConfig botConfig) {
        Utils.getRemindGroup(group.getBot(), xxGroupId).sendMessage(new MessageChain().text("药材上架完成"));
        botConfig.setCommand("");
    }

    private void alchemyEquip(List<ProductPrice> autoBuyList, Group group, BotConfig botConfig) {
        Iterator var3 = autoBuyList.iterator();

        while (var3.hasNext()) {
            ProductPrice productPrice = (ProductPrice) var3.next();

            try {
                if (StringUtils.isEmpty(botConfig.getCommand())) {
                    break;
                }
                Utils.sendGroupMessage(group.getBot(), group.getGroupId(), (new MessageChain()).at("3889001741")
                        .text("炼金 " + productPrice.getName() + " " + productPrice.getHerbCount()));
                break;
            } catch (Exception var6) {
                Thread.currentThread().interrupt();
            }
        }

    }

    private void alchemyPill(List<ProductPrice> autoBuyList, Group group, BotConfig botConfig) {
        Iterator var3 = autoBuyList.iterator();

        while (var3.hasNext()) {
            ProductPrice productPrice = (ProductPrice) var3.next();

            try {
                if (StringUtils.isEmpty(botConfig.getCommand())) {
                    break;
                }
                Utils.sendGroupMessage(group.getBot(), group.getGroupId(), (new MessageChain()).at("3889001741")
                        .text("炼金 " + productPrice.getName() + " " + productPrice.getHerbCount()));
                break;
            } catch (Exception var6) {
                Thread.currentThread().interrupt();
            }
        }

    }

}
