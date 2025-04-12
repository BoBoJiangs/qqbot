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
import com.zhuangxv.bot.message.support.TextMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.ProductPrice;

import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static top.sshh.qqbot.service.DanCalculator.targetDir;

@Component
public class AutoBuyHerbs {
    private static final Logger logger = LoggerFactory.getLogger(AutoBuyHerbs.class);
    private static final long SENDER_ID = 3889001741L;
    private static final String BUY_COMMAND = "坊市购买";
    private static final String MARKET_COMMAND = "查看坊市药材";
    public static final Map<Long, Map<String, ProductPrice>> AUTO_BUY_HERBS = new ConcurrentHashMap();
    private final ExecutorService customPool = Executors.newCachedThreadPool();
    private final List<ProductPrice> autoBuyList = new CopyOnWriteArrayList();
    private List<String> medicinalList = new ArrayList();
    public int page = 1;
    private Map<String, ProductPrice> herbPackMap = new ConcurrentHashMap();
    @Autowired
    public DanCalculator danCalculator;

    public AutoBuyHerbs() {
    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        message = message.trim();
        if (!message.contains("可用命令")) {
            switch (message) {
                case "开始采购药材":
                    this.page = 1;
                    this.herbPackMap.clear();
                    this.autoBuyList.clear();
                    botConfig.setStop(true);
                    group.sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
                    new Timer();
                    botConfig.setStartAutoBuyHerbs(true);
                    break;
                case "停止采购药材":
                    this.page = 1;
                    this.autoBuyList.clear();
                    this.herbPackMap.clear();
                    botConfig.setStartAutoBuyHerbs(false);
                    group.sendMessage((new MessageChain()).reply(messageId).text("停止采购"));
                    break;
                default:
                    this.handlePurchaseCommands(bot, group, message, messageId);
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 药材背包(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
        if (isGroup && message.contains("拥有数量") && message.contains("坊市数据") && botConfig.isStartAutoBuyHerbs()) {
            List<TextMessage> textMessages = messageChain.getMessageByType(TextMessage.class);
            boolean hasNextPage = false;
            TextMessage textMessage = null;
            if (textMessages.size() > 1) {
                textMessage = (TextMessage)textMessages.get(1);
            } else {
                textMessage = (TextMessage)textMessages.get(0);
            }

            if (textMessage != null) {
                String msg = textMessage.getText();
                if (message.contains("炼金") && message.contains("坊市数据")) {
                    String[] lines = msg.split("\n");
                    this.medicinalList.addAll(Arrays.asList(lines));
                    if (msg.contains("下一页")) {
                        hasNextPage = true;
                    }
                }

                if (hasNextPage) {
                    ++this.page;
                    group.sendMessage((new MessageChain()).at("3889001741").text("药材背包" + this.page));
                } else {
                    botConfig.setStop(false);
                    this.parseHerbList();
                }
            } else {
                System.out.println("message==" + message);
            }
        }

    }

    public void parseHerbList() throws Exception {
        String currentHerb = null;
        Iterator var2 = this.medicinalList.iterator();

        while(var2.hasNext()) {
            String line = (String)var2.next();
            line = line.trim();
            if (line.contains("名字：")) {
                currentHerb = line.replaceAll("名字：", "");
            } else if (currentHerb != null && line.contains("拥有数量:")) {
                int count = Integer.parseInt(line.split("拥有数量:|炼金")[1]);
                ProductPrice productPrice = new ProductPrice();
                productPrice.setName(currentHerb);
                productPrice.setHerbCount(count);
                this.herbPackMap.put(currentHerb, productPrice);
                currentHerb = null;
            }
        }

    }

    private void handlePurchaseCommands(Bot bot, Group group, String message, Integer messageId) {
        Map<String, ProductPrice> productMap = (Map)AUTO_BUY_HERBS.computeIfAbsent(bot.getBotId(), (k) -> {
            return new ConcurrentHashMap();
        });
        if (message.startsWith("取消采购药材")) {
            String productName = message.substring("取消采购药材".length()).trim();
            productMap.remove(productName);
            group.sendMessage((new MessageChain()).reply(messageId).text(productName + "取消成功"));
        } else if (message.startsWith("批量取消采购药材")) {
            productMap.clear();
            group.sendMessage((new MessageChain()).reply(messageId).text("批量取消成功"));
        } else if (message.startsWith("采购药材")) {
            this.addProductsToMap(bot, group, message, messageId, productMap);
        } else if (message.equals("查询采购药材")) {
            this.queryPurchasedProducts(group, messageId, productMap);
        }

    }

    private void addProductsToMap(Bot bot, Group group, String message, Integer messageId, Map<String, ProductPrice> productMap) {
        try {
            String[] lines = message.split("\n");

            for(int i = 0; i < lines.length; ++i) {
                String line = lines[i];
                String[] parts = line.split(" ");
                if (parts.length >= 2) {
                    ProductPrice productPrice = new ProductPrice();
                    productPrice.setName(parts[0].substring(4).trim());
                    productPrice.setPrice(Integer.parseInt(parts[1].trim()));
                    productPrice.setTime(LocalDateTime.now());
                    productPrice.setId((long)i);
                    productMap.put(productPrice.getName(), productPrice);
                }
            }

            this.updateMedicinePrices(productMap);
            group.sendMessage((new MessageChain()).text("添加成功,同步炼丹配方"));
        } catch (Exception var11) {
            logger.error("添加采购药材失败");
        }

    }

    public void updateMedicinePrices(Map<String, ProductPrice> purchases) throws IOException {
        List<String> lines = new ArrayList();
        Map<String, String> medicineMap = new LinkedHashMap();
        BufferedReader reader = new BufferedReader(new FileReader(targetDir+"properties/药材价格.txt"));

        String line;
        try {
            while((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                    String[] parts = line.split("\\s+", 2);
                    if (parts.length == 2) {
                        medicineMap.put(parts[1].trim(), parts[0].trim());
                    }
                }
            }
        } catch (Throwable var11) {
        }

        reader.close();
        Iterator var12 = purchases.entrySet().iterator();

        while(var12.hasNext()) {
            Map.Entry<String, ProductPrice> entry = (Map.Entry)var12.next();
            String medicineName = (String)entry.getKey();
            ProductPrice productPrice = (ProductPrice)entry.getValue();
            medicineMap.put(medicineName, productPrice.getPrice() + "");
        }

        BufferedWriter writer = new BufferedWriter(new FileWriter(targetDir+"properties/药材价格.txt"));

        try {
            Iterator var15 = medicineMap.entrySet().iterator();

            while(var15.hasNext()) {
                Map.Entry<String, String> entry = (Map.Entry)var15.next();
                writer.write((String)entry.getValue() + " " + (String)entry.getKey());
                writer.newLine();
            }
        } catch (Throwable var10) {
        }

        writer.close();
    }

    private void queryPurchasedProducts(Group group, Integer messageId, Map<String, ProductPrice> productMap) {
        StringBuilder result = new StringBuilder();
        Iterator var5 = productMap.values().iterator();

        while(var5.hasNext()) {
            ProductPrice value = (ProductPrice)var5.next();
            result.append("名称：").append(value.getName()).append(" 价格:").append(value.getPrice()).append("万\n");
        }

        if (result.length() > 0) {
            group.sendMessage((new MessageChain()).reply(messageId).text(result.toString()));
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 成功购买药材(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
        boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
        if (isGroup && isAtSelf && botConfig.isStartAutoBuyHerbs() && (message.contains("道友成功购买") || message.contains("坊市现在太繁忙了") || message.contains("没钱还来买东西") || message.contains("未查询") || message.contains("道友的上一条指令还没执行完"))) {
            if (message.contains("道友成功购买")) {
                ProductPrice price = (ProductPrice)this.herbPackMap.get(((ProductPrice)this.autoBuyList.get(0)).getName());
                price.setHerbCount(price.getHerbCount() + 1);
                this.herbPackMap.put(price.getName(), price);
            }

            if (!this.autoBuyList.isEmpty()) {
                this.autoBuyList.remove(0);
            }

            this.buyHerbs(group, bot.getBotConfig());
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 自动购买药材(Bot bot, Group group, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
        if (isGroup && message.contains("不鼓励不保障任何第三方交易行为") && !message.contains("下架")) {
            this.customPool.submit(() -> {
                this.processMarketMessage(bot, group, message);
            });
        }

    }

    private void processMarketMessage(Bot bot, Group group, String message) {
        String[] split = message.split("\n");
        String[] var5 = split;
        int var6 = split.length;

        for(int var7 = 0; var7 < var6; ++var7) {
            String s = var5[var7];
            if (s.startsWith("价格") && s.contains("mqqapi")) {
                BotConfig botConfig = bot.getBotConfig();
                if (botConfig.getTaskId() != 0L) {
                    botConfig.getTaskId();
                } else {
                    botConfig.getGroupId();
                }

                String[] split1 = s.split("\\[|\\]");
                String code = s.split("%E5%9D%8A%E5%B8%82%E8%B4%AD%E4%B9%B0|&")[1];
                double price = this.extractPrice(s);
                String itemName = this.extractItemName(split1[1].trim());
                Map<String, ProductPrice> productMap = (Map)AUTO_BUY_HERBS.computeIfAbsent(bot.getBotId(), (k) -> {
                    return new ConcurrentHashMap();
                });
                ProductPrice existingProduct = (ProductPrice)productMap.get(itemName);
                if (existingProduct != null && price <= (double)existingProduct.getPrice()) {
                    if (this.herbPackMap.get(itemName) == null) {
                        ProductPrice productPrice = new ProductPrice();
                        productPrice.setName(itemName);
                        productPrice.setHerbCount(0);
                        this.herbPackMap.put(itemName, productPrice);
                    }

                    if (((ProductPrice)this.herbPackMap.get(itemName)).getHerbCount() > danCalculator.config.getLimitHerbsCount()) {
                        if (price <= (double)existingProduct.getPrice() - (double)danCalculator.config.getAddPrice()) {
                            existingProduct.setCode(code);
                            this.autoBuyList.add(existingProduct);
                        }
                    } else {
                        existingProduct.setCode(code);
                        this.autoBuyList.add(existingProduct);
                    }
                }
            }
        }

        this.autoBuyList.sort(Comparator.comparingLong(ProductPrice::getId));
        this.buyHerbs(group, bot.getBotConfig());
    }

    private double extractPrice(String message) {
        String[] split;
        if (message.contains("万 [")) {
            split = message.split("价格:|万");
            return Double.parseDouble(split[1]);
        } else if (message.contains("亿 [")) {
            split = message.split("价格:|亿");
            return Double.parseDouble(split[1]) * 10000.0;
        } else {
            return Double.MAX_VALUE;
        }
    }

    private String extractItemName(String rawName) {
        StringBuilder result = new StringBuilder();
        char[] var3 = rawName.toCharArray();
        int var4 = var3.length;

        for(int var5 = 0; var5 < var4; ++var5) {
            char c = var3[var5];
            if (Character.toString(c).matches("[\\u4e00-\\u9fa5()（）]")) {
                result.append(c);
            }
        }

        return result.toString();
    }

    private void buyHerbs(Group group, BotConfig botConfig) {
        Iterator var3 = this.autoBuyList.iterator();

        while(var3.hasNext()) {
            ProductPrice productPrice = (ProductPrice)var3.next();

            try {
                if (botConfig.isStartAutoBuyHerbs()) {
                    group.sendMessage((new MessageChain()).at("3889001741").text("坊市购买 " + productPrice.getCode()));
                }
                break;
            } catch (Exception var6) {
                logger.error("发送购买消息失败");
                Thread.currentThread().interrupt();
            }
        }

    }

    @Scheduled(
            fixedDelay = 5000L,
            initialDelay = 30000L
    )
    public void 定时查询坊市() {
        BotFactory.getBots().values().forEach((bot) -> {
            BotConfig botConfig = bot.getBotConfig();
            if (!botConfig.isStop() && this.autoBuyList.isEmpty() && botConfig.isStartAutoBuyHerbs()) {
                long groupId = botConfig.getTaskId() != 0L ? botConfig.getTaskId() : botConfig.getGroupId();
                if (botConfig.getTaskStatusHerbs() == 8) {
                    botConfig.setTaskStatusHerbs(1);
                }

                if (botConfig.getTaskStatusHerbs() < 8) {
                    try {
                        bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("查看坊市药材" + botConfig.getTaskStatusHerbs()));
                        botConfig.setTaskStatusHerbs(botConfig.getTaskStatusHerbs() + 1);
                    } catch (Exception var6) {
                        logger.error("定时查询坊市失败");
                        Thread.currentThread().interrupt();
                    }
                }
            }

        });
    }
}
