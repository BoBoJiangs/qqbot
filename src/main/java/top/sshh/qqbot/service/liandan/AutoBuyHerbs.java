package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Buttons;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.MessageNumber;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.GroupManager;
import top.sshh.qqbot.service.ProductPriceResponse;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static top.sshh.qqbot.constant.Constant.targetDir;

@Component
public class AutoBuyHerbs {
    private static final Logger logger = LoggerFactory.getLogger(AutoBuyHerbs.class);
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final long SENDER_ID = 3889001741L;
    private static final String BUY_COMMAND = "坊市购买";
    private static final String MARKET_COMMAND = "查看坊市药材";

    /** 保持原有的按 botId 隔离的采购价格表 */
    public static final Map<Long, Map<String, ProductPrice>> AUTO_BUY_HERBS = new ConcurrentHashMap<>();

    private final ExecutorService customPool = Executors.newCachedThreadPool();

    @Autowired
    private ProductPriceResponse productPriceResponse;

    // 将原来的共享字段改为按 botId 隔离
    private final Map<Long, CopyOnWriteArrayList<ProductPrice>> autoBuyListMap = new ConcurrentHashMap<>();
    private final Map<Long, List<String>> medicinalListMap = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, ProductPrice>> herbPackMapMap = new ConcurrentHashMap<>();
    private final Map<Long, Integer> pageMap = new ConcurrentHashMap<>();
    private final Map<Long, Integer> noQueriedCountMap = new ConcurrentHashMap<>();
    private final Map<Long, List<Integer>> makeDrugIndexListMap = new ConcurrentHashMap<>();
    private final Map<Long, Integer> drugIndexMap = new ConcurrentHashMap<>();

    @Autowired
    public DanCalculator danCalculator;
    @Autowired
    public GroupManager groupManager;

    public AutoBuyHerbs() {
    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        if (StringUtils.isEmpty(message) || !botConfig.isEnableAlchemy()) {
            return;
        }
        message = message.trim();
        long botId = bot.getBotId();

        if (!message.contains("可用命令")) {
            switch (message) {
                case "丹药炼金完成":
                    if(botConfig.isStartAuto()){
                        resetPram(bot, botConfig);
                        botConfig.setStop(true);
                        botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());
                        group.sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
                        botConfig.setAutoBuyHerbsMode(1);
                        botConfig.setStartAuto(false);
                    }
                    break;
                case "开始采购药材":
                    resetPram(bot, botConfig);
                    botConfig.setStop(true);
                    botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());
                    botConfig.setAutoBuyHerbsMode(1);
                    botConfig.setStartAuto(false);
                    group.sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
                    break;
                case "一键采购药材":
                    resetPram(bot, botConfig);
                    botConfig.setStop(true);
                    botConfig.setAutoBuyHerbsMode(2);
                    botConfig.setStartAuto(false);
                    group.sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
                    break;
                case "停止采购药材":
                    resetPram(bot, botConfig);
                    botConfig.setAutoBuyHerbsMode(0);
                    group.sendMessage((new MessageChain()).reply(messageId).text("停止采购"));
                    break;

                default:
                    this.handlePurchaseCommands(bot, group, message, messageId);
            }
        }

        if (message.startsWith("刷新指定药材坊市")) {
            String[] indexs = message.substring(message.indexOf("刷新指定药材坊市") + 8).trim().split("&");
            List<Integer> list = makeDrugIndexListMap.computeIfAbsent(botId, k -> new ArrayList<>());
            for (String s : indexs) {
                try{
                    list.add(Integer.parseInt(s));
                }catch (NumberFormatException ignore){}
            }
            group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
        }

        if ("取消刷新指定药材坊市".startsWith(message)) {
            makeDrugIndexListMap.put(botId, new ArrayList<>());
            group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
        }

    }

    private void resetPram(Bot bot, BotConfig botConfig) {
        long botId = bot.getBotId();
        pageMap.put(botId, 1);
        noQueriedCountMap.put(botId, 0);
        drugIndexMap.put(botId, 0);
        herbPackMapMap.put(botId, new ConcurrentHashMap<>());
        autoBuyListMap.put(botId, new CopyOnWriteArrayList<>());
        medicinalListMap.put(botId, new ArrayList<>());
        makeDrugIndexListMap.putIfAbsent(botId, new ArrayList<>());
        botConfig.setTaskStatusHerbs(1);
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 药材背包(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        long botId = bot.getBotId();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
        if (isGroup && (message.contains("上一页") || message.contains("下一页") || message.contains("药材背包")) && botConfig.getAutoBuyHerbsMode()!=0) {
            List<TextMessage> textMessages = messageChain.getMessageByType(TextMessage.class);
            boolean hasNextPage = false;
            TextMessage textMessage = null;
            if (textMessages.size() > 1) {
                textMessage = (TextMessage)textMessages.get(textMessages.size()-1);
            } else if(!textMessages.isEmpty()) {
                textMessage = (TextMessage)textMessages.get(0);
            }

            if (textMessage != null) {
                String msg = textMessage.getText();
                if (message.contains("炼金") && message.contains("坊市数据")) {
                    List<String> list = medicinalListMap.computeIfAbsent(botId, k -> new ArrayList<>());
                    String[] lines = msg.split("\n");
                    list.addAll(Arrays.asList(lines));
                    if (msg.contains("下一页")) {
                        hasNextPage = true;
                    }
                }

                if (hasNextPage) {
                    int nextPage = pageMap.getOrDefault(botId, 1) + 1;
                    pageMap.put(botId, nextPage);
                    group.sendMessage((new MessageChain()).at("3889001741").text("药材背包" + nextPage));
                } else {
                    botConfig.setStop(false);
                    this.parseHerbList(bot);
                    this.refreshHerbsIndex(bot);
                }
            }
        }

    }

    public void parseHerbList(Bot bot) throws Exception {
        long botId = bot.getBotId();
        List<String> medicinalList = medicinalListMap.getOrDefault(botId, Collections.emptyList());
        String currentHerb = null;

        for (String line : medicinalList) {
            line = line.trim();
            if (line.contains("名字：")) {
                currentHerb = line.replaceAll("名字：", "");
            } else if (currentHerb != null && line.contains("拥有数量:")) {
                try{
                    int count = Integer.parseInt(line.split("拥有数量:|炼金")[1]);
                    ProductPrice productPrice = new ProductPrice();
                    productPrice.setName(currentHerb);
                    productPrice.setHerbCount(count);
                    herbPackMapMap.computeIfAbsent(botId, k -> new ConcurrentHashMap<>()).put(currentHerb, productPrice);
                }catch (Exception ignore){}
                currentHerb = null;
            }
        }

    }

    private void handlePurchaseCommands(Bot bot, Group group, String message, Integer messageId) {
        long botId = bot.getBotId();
        Map<String, ProductPrice> productMap = AUTO_BUY_HERBS.computeIfAbsent(botId, (k) -> new ConcurrentHashMap<>());
        if (message.startsWith("取消采购药材")) {
            String productName = message.substring("取消采购药材".length()).trim();
            productMap.remove(productName);
            group.sendMessage((new MessageChain()).reply(messageId).text(productName + "取消成功"));

        } else if (message.startsWith("批量取消采购药材")) {
            productMap.clear();
            try {
                updateMedicinePrices(new ArrayList<>(),botId);
            } catch (Exception e) {
                e.printStackTrace();
            }
            group.sendMessage((new MessageChain()).reply(messageId).text("批量取消成功"));
        } else if (message.startsWith("采购药材")) {
            this.addProductsToMap(bot, group, message, messageId, productMap);
        } else if (message.equals("查询采购药材")) {
            this.queryPurchasedProducts(group, messageId, productMap);
        }else if (message.startsWith("批量修改性平价格")) {
            String price = message.substring("批量修改性平价格".length()).trim();
            if(StringUtils.isNumeric(price)){
                updateXingPing(price,group);
            }else{
                group.sendMessage((new MessageChain()).text("请输入正确的价格"));
            }
        }

    }

    private void updateXingPing(String price,Group group) {
        try {
            // 读取药材文件内容
            List<String> herbs = Files.readAllLines(Paths.get(targetDir, "properties", "性平.txt"));

            // 遍历每种药材并输出采购指令
            StringBuilder stringBuilder = new StringBuilder();
            for (String herb : herbs) {
                if (!herb.trim().isEmpty()) {
                    stringBuilder.append("采购药材" + herb.trim() +" "+price);
                    stringBuilder.append("\n");
                }
            }
            group.sendMessage(new MessageChain().text(stringBuilder.toString()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addProductsToMap(Bot bot, Group group, String message, Integer messageId, Map<String, ProductPrice> productMap) {
        long botId = bot.getBotId();
        try {
            String[] lines = message.split("\n");
            List<ProductPrice> priceList = new ArrayList<>();
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
                    priceList.add(productPrice);
                }
            }

            this.updateMedicinePrices(priceList,botId);
            group.sendMessage((new MessageChain()).text("添加成功,开始同步炼丹配方"));
//            if(!AutoAlchemyTask.matchingLock.tryLock()){
//                group.sendMessage((new MessageChain()).text("添加成功,开始同步炼丹配方"));
//            }else{
//                group.sendMessage((new MessageChain()).text("正在匹配丹方，请稍后操作！"));
//            }

        } catch (Exception e) {
            e.printStackTrace();
            logger.error("添加采购药材失败");
        }

    }

    public void updateMedicinePrices(List<ProductPrice> purchases,Long botId) throws IOException {
        Path filePath = Paths.get(targetDir, botId+"", "药材价格.txt");

        // 如果purchases为空，清空文件
        if (purchases == null || purchases.isEmpty()) {
            try (BufferedWriter writer = Files.newBufferedWriter(filePath,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE)) {
                // 空操作，打开文件时TRUNCATE_EXISTING选项会自动清空文件
            }
            return;
        }

        Map<String, String> medicineMap = new LinkedHashMap<>();

        // 读取现有文件内容
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    String[] parts = line.split("\\s+", 2);
                    if (parts.length == 2) {
                        medicineMap.put(parts[1].trim(), parts[0].trim());
                    }
                }
            }
        } catch (IOException e) {
            // 文件可能不存在，继续执行将创建新文件
        }

        // 更新价格
        for (ProductPrice productPrice : purchases) {
            if (productPrice != null) {
                medicineMap.put(productPrice.getName(), String.valueOf(productPrice.getPrice()));
            }
        }

        // 写回文件
        try (BufferedWriter writer = Files.newBufferedWriter(filePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Map.Entry<String, String> entry : medicineMap.entrySet()) {
                writer.write(entry.getValue() + " " + entry.getKey());
                writer.newLine();
            }
        }
    }

    private void queryPurchasedProducts(Group group, Integer messageId, Map<String, ProductPrice> productMap) {
//        StringBuilder result = new StringBuilder();
//        Iterator var5 = productMap.values().iterator();
//
//        while(var5.hasNext()) {
//            ProductPrice value = (ProductPrice)var5.next();
//            ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(value.getName().trim());
//            result.append(value.getName()).append(" ").append(value.getPrice()).append("万 坊市:").append(first.getPrice()).append("万\n");
//        }
//
//        if (result.length() > 0) {
//            group.sendMessage((new MessageChain()).reply(messageId).text(result.toString()));
//        }
        StringBuilder result = new StringBuilder();

        // 按价格从高到低排序
        List<ProductPrice> sortedProducts = productMap.values().stream()
                .sorted((p1, p2) -> Double.compare(p2.getPrice(), p1.getPrice())) // 降序排序
                .collect(Collectors.toList());

        for (ProductPrice value : sortedProducts) {
            ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(value.getName().trim());
            result.append(value.getName())
                    .append(" ")
                    .append(value.getPrice())
                    .append("万 坊市:")
                    .append(first!=null?first.getPrice():0)
                    .append("万\n");
        }

        if (result.length() > 0) {
            group.sendMessage((new MessageChain()).reply(messageId).text(result.toString()));
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 验证码判断(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (message.contains("https") && message.contains("qqbot")  && message.contains("" + bot.getBotId())) {
            BotConfig botConfig = bot.getBotConfig();
            boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
            //出验证码跳过本页购买
//            if(botConfig.getAutoBuyHerbsMode()!=0 && isGroup){
//                autoBuyListMap.computeIfAbsent(bot.getBotId(), k -> new CopyOnWriteArrayList<>()).clear();
//
//            }

        }
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 成功购买药材(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        long botId = bot.getBotId();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId();
        boolean isAtSelf = isAtSelf(group,bot);
        if (isGroup && isAtSelf && botConfig.getAutoBuyHerbsMode()!=0 && (message.contains("道友成功购买") || message.contains("卖家正在进行其他操作") || message.contains("今天已经很努力了") ||
                message.contains("坊市现在太繁忙了")||message.contains("验证码不正确") || message.contains("没钱还来买东西")  || message.contains("未查询") || message.contains("道友的上一条指令还没执行完"))) {
            botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());

            if (message.contains("道友成功购买")) {
                CopyOnWriteArrayList<ProductPrice> autoBuyList = autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>());
                if(!autoBuyList.isEmpty()){
                    ProductPrice price = herbPackMapMap.computeIfAbsent(botId, k -> new ConcurrentHashMap<>()).get(autoBuyList.get(0).getName());
                    if(price!=null){
                        price.setHerbCount(price.getHerbCount() + 1);
                        herbPackMapMap.get(botId).put(price.getName(), price);
                    }
                }else{
                    String[] parts = message.split("成功购买|，消耗");
                    if(parts.length >= 2){
                        String herbName = parts[1].trim();
                        ProductPrice price = herbPackMapMap.computeIfAbsent(botId, k -> new ConcurrentHashMap<>()).get(herbName);
                        if(price!=null){
                            price.setHerbCount(price.getHerbCount() + 1);
                            herbPackMapMap.get(botId).put(price.getName(), price);
                        }

                    }
                }

            }

            if(message.contains("今天已经很努力了") ){
                botConfig.setStartAuto(false);
                botConfig.setAutoBuyHerbsMode(0);
            }

            if(message.contains("没钱还来买东西")){
                Config config = danCalculator.getConfig(bot.getBotId());
                if(config.isFinishAutoBuyHerb()){
                    group.sendMessage((new MessageChain()).text("开始自动炼丹"));
                }else{
                    botConfig.setStartAuto(false);
                    botConfig.setAutoBuyHerbsMode(0);
                }

            }

            if(message.contains("未查询")){
                int cnt = noQueriedCountMap.getOrDefault(botId,0)+1;
                noQueriedCountMap.put(botId,cnt);
                if(cnt >= 3){
                    autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>()).clear();
                    noQueriedCountMap.put(botId,0);
                }
            }

            CopyOnWriteArrayList<ProductPrice> autoBuyList = autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>());
            if (!autoBuyList.isEmpty()) {
                autoBuyList.remove(0);
            }
            if(autoBuyList.isEmpty()){
                refreshHerbsIndex(bot);
            }else{
                this.buyHerbs(group, bot);
            }

        }

    }

    private boolean isAtSelf(Group group,Bot bot){
        return group.getGroupId() == bot.getBotConfig().getGroupId();
    }



    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 自动购买药材(Bot bot, Group group, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        long botId = bot.getBotId();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
        if (isGroup && message.contains("不鼓励不保障任何第三方交易行为") && !message.contains("下架") && botConfig.getAutoBuyHerbsMode()!=0) {
            botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());
            this.customPool.submit(() -> {
                this.processMarketMessage(bot, group, message);
            });
        }

    }

    private void processMarketMessage(Bot bot, Group group, String message) {
        long botId = bot.getBotId();
        String[] split = message.split("\n");
        String[] var5 = split;
        int var6 = split.length;
        Config config = danCalculator.getConfig(bot.getBotId());
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
                Map<String, ProductPrice> productMap = AUTO_BUY_HERBS.computeIfAbsent(bot.getBotId(), (k) -> {
                    return new ConcurrentHashMap<>();
                });
                ProductPrice existingProduct = productMap.get(itemName);
                if (existingProduct != null && price <= (double)existingProduct.getPrice()) {
                    if (herbPackMapMap.get(botId) == null) {
                        ProductPrice productPrice = new ProductPrice();
                        productPrice.setName(itemName);
                        productPrice.setHerbCount(0);
                        herbPackMapMap.computeIfAbsent(botId, k -> new ConcurrentHashMap<>()).put(itemName, productPrice);
                    }

                    ProductPrice packPrice = herbPackMapMap.get(botId).get(itemName);
                    if (packPrice != null && packPrice.getHerbCount() > config.getLimitHerbsCount()) {
                        if (price <= (double)existingProduct.getPrice() - (double)config.getAddPrice()) {
                            existingProduct.setCode(code);
                            existingProduct.setPriceDiff((int) (existingProduct.getPrice() - price));
                            autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>()).add(existingProduct);
                        }
                    } else {
                        existingProduct.setCode(code);
                        existingProduct.setPriceDiff((int) (existingProduct.getPrice() - price));
                        autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>()).add(existingProduct);
                    }
                }
            }
        }

        CopyOnWriteArrayList<ProductPrice> autoBuyList = autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>());
        autoBuyList.sort(Comparator.comparingLong(ProductPrice::getId));
        autoBuyList.sort(Comparator.comparingLong(ProductPrice::getPriceDiff).reversed());
        if(!autoBuyList.isEmpty()){
            this.buyHerbs(group, bot);
        }else{
            this.refreshHerbsIndex(bot);
        }

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

    private void buyHerbs(Group group, Bot bot) {
        BotConfig botConfig = bot.getBotConfig();
        long botId = bot.getBotId();
        CopyOnWriteArrayList<ProductPrice> autoBuyList = autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>());
        for (ProductPrice productPrice : autoBuyList) {

            try {
                if (botConfig.getAutoBuyHerbsMode()!=0) {
                    group.sendMessage((new MessageChain()).at("3889001741").text("坊市购买 " + productPrice.getCode()));
                }
                break;
            } catch (Exception var6) {
                logger.error("发送购买消息失败");
                Thread.currentThread().interrupt();
            }
        }

    }

    @Scheduled(fixedDelay = 5000L, initialDelay = 30000L)
    public void 定时查询坊市() {
        BotFactory.getBots().values().forEach((bot) -> {
            BotConfig botConfig = bot.getBotConfig();
            long botId = bot.getBotId();
            if(botConfig.getAutoBuyHerbsMode() != 0 && !botConfig.isStop() &&
                    System.currentTimeMillis() - botConfig.getAutoTaskRefreshTime() > 10000L){
                autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>()).clear();
                botConfig.setStop(false);
                this.refreshHerbsIndex(bot);
            }


        });
    }

    private void refreshHerbsIndex(Bot bot) {
        BotConfig botConfig = bot.getBotConfig();
        long botId = bot.getBotId();
        if (botConfig.getAutoBuyHerbsMode() != 0) {
            long groupId = botConfig.getTaskId() != 0L ? botConfig.getTaskId() : botConfig.getGroupId();
            List<Integer> makeDrugIndexList = makeDrugIndexListMap.computeIfAbsent(botId, k -> new ArrayList<>());
            if(!makeDrugIndexList.isEmpty()){
                int drugIndex = drugIndexMap.getOrDefault(botId,0);
                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("查看坊市药材" + makeDrugIndexList.get(drugIndex)));
                if(drugIndex == makeDrugIndexList.size() - 1){
                    drugIndexMap.put(botId,0);
                }else{
                    drugIndexMap.put(botId,drugIndex + 1);
                }
            }else{
                if (botConfig.getTaskStatusHerbs() >= 9) {
                    botConfig.setTaskStatusHerbs(1);
                }

                if (botConfig.getTaskStatusHerbs() < 9) {
                    try {
                        bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("查看坊市药材" + botConfig.getTaskStatusHerbs()));
                        botConfig.setTaskStatusHerbs(botConfig.getTaskStatusHerbs() + 1);
                        noQueriedCountMap.put(botId,0);
                    } catch (Exception var6) {
                        logger.error("定时查询坊市失败");
                        Thread.currentThread().interrupt();
                    }
                }
            }

        }
    }
}
