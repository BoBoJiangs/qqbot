package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Buttons;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import top.sshh.qqbot.service.utils.Utils;
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
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static top.sshh.qqbot.constant.Constant.targetDir;

@Component
public class AutoBuyHerbs {
    private static final Logger logger = LoggerFactory.getLogger(AutoBuyHerbs.class);
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final long SENDER_ID = 3889001741L;
    private static final String BUY_COMMAND = "坊市购买";
    private static final String MARKET_COMMAND = "查看坊市药材";
    private static final String REPEAT_BUY_COMMAND = "重复采购药材";
    private static final String REPEAT_BUY_CONFIG_FILE = "重复采购药材.txt";

    /** 保持原有的按 botId 隔离的采购价格表 */
    public static final Map<Long, Map<String, ProductPrice>> AUTO_BUY_HERBS = new ConcurrentHashMap<>();

    /** 按 botId 隔离的重复采购药材名称。 */
    private final Map<Long, Set<String>> repeatBuyHerbMap = new ConcurrentHashMap<>();
    /** 按 botId 隔离的重复采购价格，不与 AUTO_BUY_HERBS 中的普通采购价格共享。 */
    private final Map<Long, Map<String, Integer>> repeatBuyPriceMap = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> repeatBuyConfigLoadedMap = new ConcurrentHashMap<>();

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
    
    // 按 botId 隔离：智能调整药材价格模式
    private final Map<Long, Boolean> smartAdjustModeMap = new ConcurrentHashMap<>();

    // 按 botId 隔离：坊市刷新节流（单位：毫秒）
//    private final Map<Long, Long> nextMarketRefreshAtMsMap = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> marketRefreshScheduledFlagMap = new ConcurrentHashMap<>();

    // 按 botId 隔离：购买成功后下一件药材的延迟任务，避免重复发送购买命令
    private final Map<Long, AtomicBoolean> purchaseDelayScheduledFlagMap = new ConcurrentHashMap<>();

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
                    if (botConfig.getCultivationMode() == 1) {
                        botConfig.setStartScheduled(false);
                    }
                    group.sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
                    break;
                case "停止采购药材":
                    resetPram(bot, botConfig);
                    botConfig.setAutoBuyHerbsMode(0);
                    group.sendMessage((new MessageChain()).reply(messageId).text("停止采购"));
                    break;
                
                case "分析背包药材":
                    resetPram(bot, botConfig);
                    smartAdjustModeMap.put(botId, true);
                    medicinalListMap.put(botId, new ArrayList<>());
                    pageMap.put(botId, 1);
                   
                    botConfig.setAutoBuyHerbsMode(0);
                    group.sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
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
        loadRepeatBuyConfig(botId);
        pageMap.put(botId, 1);
        noQueriedCountMap.put(botId, 0);
        drugIndexMap.put(botId, 0);
        herbPackMapMap.put(botId, new ConcurrentHashMap<>());
        autoBuyListMap.put(botId, new CopyOnWriteArrayList<>());
        medicinalListMap.put(botId, new ArrayList<>());
        makeDrugIndexListMap.putIfAbsent(botId, new ArrayList<>());
        smartAdjustModeMap.put(botId, false);
        botConfig.setTaskStatusHerbs(1);
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 药材背包(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        long botId = bot.getBotId();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
        boolean isSmartAdjustMode = smartAdjustModeMap.getOrDefault(botId, false);
        
        if (isGroup && (message.contains("上一页") || message.contains("下一页") || message.contains("药材背包")) && (botConfig.getAutoBuyHerbsMode()!=0 || isSmartAdjustMode)) {
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
                    if (isSmartAdjustMode) {
                        botConfig.setStop(false);
                        this.parseHerbList(bot);
                        this.analyzeHerbCount(botId, group);
                        smartAdjustModeMap.put(botId, false);
                    } else {
                        botConfig.setStop(false);
                        this.parseHerbList(bot);
                        this.refreshHerbsIndex(bot);
                    }
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
        Set<String> repeatBuyHerbs = getRepeatBuyHerbs(botId);
        Map<String, Integer> repeatBuyPrices = getRepeatBuyPrices(botId);
        if (message.startsWith("批量取消重复采购药材")) {
            repeatBuyHerbs.clear();
            repeatBuyPrices.clear();
            saveRepeatBuyConfig(botId);
            group.sendMessage((new MessageChain()).reply(messageId).text("批量取消重复采购成功"));

        } else if (message.startsWith("取消重复采购药材")) {
            String productName = message.substring("取消重复采购药材".length()).trim();
            repeatBuyHerbs.remove(productName);
            repeatBuyPrices.remove(productName);
            saveRepeatBuyConfig(botId);
            group.sendMessage((new MessageChain()).reply(messageId).text(productName + "已取消重复采购"));

        } else if (message.startsWith("取消采购药材")) {
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
        } else if (message.startsWith(REPEAT_BUY_COMMAND)) {
            this.addProductsToMap(bot, group, message, messageId, productMap, true);
        } else if (message.startsWith("采购药材")) {
            this.addProductsToMap(bot, group, message, messageId, productMap, false);
        } else if (message.equals("查询重复采购药材")) {
            this.queryRepeatPurchaseProducts(group, messageId, productMap, botId);
        } else if (message.equals("查询采购药材")) {
            this.queryPurchasedProducts(group, messageId, productMap, botId);
        }else if (message.startsWith("批量修改性平价格")) {
            String price = message.substring("批量修改性平价格".length()).trim();
            if(StringUtils.isNumeric(price)){
                updateXingPing(price,group);
            }else{
                group.sendMessage((new MessageChain()).text("请输入正确的价格"));
            }
        }

    }

    private Set<String> getRepeatBuyHerbs(long botId) {
        loadRepeatBuyConfig(botId);
        return repeatBuyHerbMap.computeIfAbsent(botId, k -> ConcurrentHashMap.newKeySet());
    }

    private Map<String, Integer> getRepeatBuyPrices(long botId) {
        loadRepeatBuyConfig(botId);
        return repeatBuyPriceMap.computeIfAbsent(botId, k -> new ConcurrentHashMap<>());
    }

    private void loadRepeatBuyConfig(long botId) {
        AtomicBoolean loaded = repeatBuyConfigLoadedMap.computeIfAbsent(botId, k -> new AtomicBoolean(false));
        if (!loaded.compareAndSet(false, true)) {
            return;
        }

        Set<String> repeatBuyHerbs = repeatBuyHerbMap.computeIfAbsent(botId, k -> ConcurrentHashMap.newKeySet());
        Map<String, Integer> repeatBuyPrices = repeatBuyPriceMap.computeIfAbsent(
                botId, k -> new ConcurrentHashMap<>());
        Path filePath = getRepeatBuyConfigPath(botId);
        if (!Files.exists(filePath)) {
            return;
        }

        try {
            for (String line : Files.readAllLines(filePath, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length == 2) {
                    try {
                        repeatBuyPrices.put(parts[1].trim(), Integer.parseInt(parts[0].trim()));
                        repeatBuyHerbs.add(parts[1].trim());
                    } catch (NumberFormatException e) {
                        logger.warn("忽略无效的重复采购价格配置 botId={} line={}", botId, line);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("读取重复采购药材配置失败 botId={}", botId, e);
        }
    }

    private void saveRepeatBuyConfig(long botId) {
        Set<String> repeatBuyHerbs = repeatBuyHerbMap.computeIfAbsent(botId, k -> ConcurrentHashMap.newKeySet());
        Map<String, Integer> repeatBuyPrices = repeatBuyPriceMap.computeIfAbsent(
                botId, k -> new ConcurrentHashMap<>());
        Path filePath = getRepeatBuyConfigPath(botId);
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> sortedHerbs = new ArrayList<>(repeatBuyHerbs);
            sortedHerbs.removeIf(herbName -> !repeatBuyPrices.containsKey(herbName));
            Collections.sort(sortedHerbs);
            List<String> lines = sortedHerbs.stream()
                    .map(herbName -> repeatBuyPrices.get(herbName) + " " + herbName)
                    .collect(Collectors.toList());
            Files.write(filePath, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            logger.error("保存重复采购药材配置失败 botId={}", botId, e);
        }
    }

    private Path getRepeatBuyConfigPath(long botId) {
        return Paths.get(targetDir, String.valueOf(botId), REPEAT_BUY_CONFIG_FILE);
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

    private void addProductsToMap(Bot bot, Group group, String message, Integer messageId,
                                   Map<String, ProductPrice> productMap, boolean repeatMode) {
        long botId = bot.getBotId();
        String commandPrefix = repeatMode ? REPEAT_BUY_COMMAND : "采购药材";
        try {
            String[] lines = message.split("\\r?\\n");
            List<ProductPrice> priceList = new ArrayList<>();
            Set<String> repeatBuyHerbs = getRepeatBuyHerbs(botId);
            Map<String, Integer> repeatBuyPrices = getRepeatBuyPrices(botId);
            int parsedCount = 0;
            for(int i = 0; i < lines.length; ++i) {
                String line = lines[i].trim();
                if (!line.startsWith(commandPrefix)) {
                    continue;
                }
                String[] parts = line.substring(commandPrefix.length()).trim().split("\\s+");
                if (parts.length >= 2) {
                    ProductPrice productPrice = new ProductPrice();
                    productPrice.setName(parts[0].trim());
                    productPrice.setPrice(Integer.parseInt(parts[1].trim()));
                    productPrice.setTime(LocalDateTime.now());
                    productPrice.setId((long)i);
                    if (repeatMode) {
                        repeatBuyHerbs.add(productPrice.getName());
                        repeatBuyPrices.put(productPrice.getName(), productPrice.getPrice());
                    } else {
                        productMap.put(productPrice.getName(), productPrice);
                        priceList.add(productPrice);
                    }
                    parsedCount++;
                }
            }

            if (parsedCount == 0) {
                group.sendMessage((new MessageChain()).reply(messageId)
                        .text("格式错误，请使用：" + commandPrefix + "药材名 价格"));
                return;
            }

            if (repeatMode) {
                saveRepeatBuyConfig(botId);
                group.sendMessage((new MessageChain()).text("重复采购设置成功"));
                return;
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

    private void queryPurchasedProducts(Group group, Integer messageId, Map<String, ProductPrice> productMap,
                                        long botId) {
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
        StringBuilder belowMarketResult = new StringBuilder();
        Map<String, Integer> repeatBuyPrices = getRepeatBuyPrices(botId);

        // 按价格从高到低排序
        List<ProductPrice> sortedProducts = productMap.values().stream()
                .sorted((p1, p2) -> Double.compare(p2.getPrice(), p1.getPrice())) // 降序排序
                .collect(Collectors.toList());

        for (ProductPrice value : sortedProducts) {
            ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(value.getName().trim());
            result.append(value.getName())
                    .append(repeatBuyPrices.containsKey(value.getName().trim()) ? " [重复采购]" : "")
                    .append(" ")
                    .append(value.getPrice())
                    .append("万 坊市:")
                    .append(first!=null?first.getPrice():0)
                    .append("万\n");
            
            // 分析低于坊市价格的药材
            if (first != null && value.getPrice() < first.getPrice()) {
                belowMarketResult.append(value.getName())
                        .append(" ")
                        .append(value.getPrice())
                        .append("万 坊市:")
                        .append(first.getPrice())
                        .append("万\n");
            }
        }

        if (result.length() > 0) {
            group.sendMessage((new MessageChain()).reply(messageId).text(result.toString()));
        }
        
        // 发送低于坊市价格的药材信息
        if (belowMarketResult.length() > 0) {
            StringBuilder belowMarketMessage = new StringBuilder();
            belowMarketMessage.append("以下药材低于坊市价格，建议根据需求调整\n");
            belowMarketMessage.append(belowMarketResult.toString());
            group.sendMessage(new MessageChain().text(belowMarketMessage.toString().trim()));
        }

    }

    private void queryRepeatPurchaseProducts(Group group, Integer messageId,
                                             Map<String, ProductPrice> productMap, long botId) {
        Map<String, Integer> repeatBuyPrices = getRepeatBuyPrices(botId);
        if (repeatBuyPrices.isEmpty()) {
            group.sendMessage((new MessageChain()).reply(messageId).text("当前没有设置重复采购药材"));
            return;
        }

        List<String> sortedHerbs = new ArrayList<>(repeatBuyPrices.keySet());
        Collections.sort(sortedHerbs);
        StringBuilder result = new StringBuilder("重复采购药材：\n");
        for (String herbName : sortedHerbs) {
            result.append(herbName)
                    .append(" ").append(repeatBuyPrices.get(herbName)).append("万");
            ProductPrice normalPrice = productMap.get(herbName);
            if (normalPrice != null) {
                result.append("（普通采购 ").append(normalPrice.getPrice()).append("万）");
            }
            result.append("\n");
        }
        group.sendMessage((new MessageChain()).reply(messageId).text(result.toString().trim()));
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
        boolean isGroup = isAutoBuyGroup(group, botConfig);
        if (isGroup && botConfig.getAutoBuyHerbsMode() != 0 && message.contains("今天已经很努力了")) {
            logger.info("检测到购买过于频繁，停止自动购买药材 botId={}", botId);
            stopAutoBuyHerbs(bot, botConfig);
            return;
        }
        if (isGroup && botConfig.getAutoBuyHerbsMode()!=0 && (message.contains("道友成功购买") || message.contains("卖家正在进行其他操作")  ||
                message.contains("坊市现在太繁忙了")||message.contains("验证码不正确") || message.contains("没钱还来买东西")  || message.contains("未查询") || message.contains("道友的上一条指令还没执行完"))) {
            botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());
            CopyOnWriteArrayList<ProductPrice> autoBuyList = autoBuyListMap.computeIfAbsent(
                    botId, k -> new CopyOnWriteArrayList<>());
            ProductPrice currentProduct = autoBuyList.isEmpty() ? null : autoBuyList.get(0);
            boolean repeatPurchase = isRepeatPurchase(botId, currentProduct);
            boolean itemNotFound = message.contains("未查询到该物品");

            if (message.contains("道友成功购买")) {
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

            

            if(message.contains("没钱还来买东西")){
                Config config = danCalculator.getConfig(bot.getBotId());
                if(config != null && config.isFinishAutoBuyHerb()){
                    group.sendMessage((new MessageChain()).text("开始自动炼丹"));
                }else{
                    botConfig.setStartAuto(false);
                    botConfig.setAutoBuyHerbsMode(0);
                }
                // 没钱是全局状态，不能在重复采购模式下继续重试当前购买码。
                return;
            }

            if(message.contains("未查询")){
                if (repeatPurchase) {
                    noQueriedCountMap.put(botId,0);
                } else {
                    int cnt = noQueriedCountMap.getOrDefault(botId,0)+1;
                    noQueriedCountMap.put(botId,cnt);
                    if(cnt >= 3){
                        autoBuyList.clear();
                        noQueriedCountMap.put(botId,0);
                    }
                }
            }

            if (!autoBuyList.isEmpty()) {
                if (repeatPurchase && itemNotFound) {
                    removeRepeatPurchaseCandidates(autoBuyList, currentProduct);
                } else if (!repeatPurchase) {
                    // 普通采购保持原逻辑：收到一次购买结果后移除当前队首。
                    autoBuyList.remove(0);
                }
            }
            if(autoBuyList.isEmpty()){
                Config config = danCalculator.getConfig(bot.getBotId());
                 this.refreshHerbsIndexByInterval(bot, config);
                // refreshHerbsIndex(bot);
            }else{
                if (repeatPurchase && !itemNotFound) {
                    // 重复采购成功或遇到临时错误，都继续使用当前购买码。
                    this.buyNextHerbAfterDelay(group, bot);
                } else if (message.contains("道友成功购买")) {
                    this.buyNextHerbAfterDelay(group, bot);
                } else {
                    this.buyHerbs(group, bot);
                }
            }

        }

    }

    private void removeRepeatPurchaseCandidates(CopyOnWriteArrayList<ProductPrice> autoBuyList,
                                                 ProductPrice currentProduct) {
        if (currentProduct == null || currentProduct.getName() == null) {
            if (!autoBuyList.isEmpty()) {
                autoBuyList.remove(0);
            }
            return;
        }
        String herbName = currentProduct.getName();
        autoBuyList.removeIf(product -> herbName.equals(product.getName()));
    }

    private boolean isAutoBuyGroup(Group group, BotConfig botConfig){
        return group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
    }

    private void stopAutoBuyHerbs(Bot bot, BotConfig botConfig) {
        long botId = bot.getBotId();
        botConfig.setStartAuto(false);
        botConfig.setAutoBuyHerbsMode(0);
        autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>()).clear();
        noQueriedCountMap.put(botId, 0);
        AtomicBoolean scheduledFlag = marketRefreshScheduledFlagMap.get(botId);
        if (scheduledFlag != null) {
            scheduledFlag.set(false);
        }
        AtomicBoolean purchaseScheduledFlag = purchaseDelayScheduledFlagMap.get(botId);
        if (purchaseScheduledFlag != null) {
            purchaseScheduledFlag.set(false);
        }
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
        BotConfig botConfig = bot.getBotConfig();
        if (botConfig.getAutoBuyHerbsMode() == 0) {
            return;
        }
        String[] split = message.split("\n");
        String[] var5 = split;
        int var6 = split.length;
        Config config = danCalculator.getConfig(bot.getBotId());
        for(int var7 = 0; var7 < var6; ++var7) {
            if (botConfig.getAutoBuyHerbsMode() == 0) {
                autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>()).clear();
                return;
            }
            String s = var5[var7];
            if (s.startsWith("价格") && s.contains("mqqapi")) {
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
                ProductPrice normalPurchaseRule = productMap.get(itemName);
                ProductPrice repeatPurchaseRule = getRepeatPurchaseRule(botId, itemName, normalPurchaseRule);
                ProductPrice purchaseRule = repeatPurchaseRule != null ? repeatPurchaseRule : normalPurchaseRule;
                if (isMarketPriceAllowed(price, purchaseRule)) {
                    if (canPurchaseHerb(botId, purchaseRule, itemName, price, config)) {
                        ProductPrice candidate = createPurchaseCandidate(purchaseRule, code, price);
                        enqueuePurchaseCandidate(botId, candidate);
                    }
                }
            }
        }

        CopyOnWriteArrayList<ProductPrice> autoBuyList = autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>());
        sortPurchaseCandidates(botId, autoBuyList);
        if (botConfig.getAutoBuyHerbsMode() == 0) {
            autoBuyList.clear();
            return;
        }
        if(!autoBuyList.isEmpty()){
            this.buyHerbs(group, bot);
        }else{
            this.refreshHerbsIndexByInterval(bot, config);
        }

    }

    private boolean isMarketPriceAllowed(double marketPrice, ProductPrice purchaseRule) {
        return purchaseRule != null && marketPrice <= (double) purchaseRule.getPrice();
    }

    private ProductPrice getRepeatPurchaseRule(long botId, String herbName, ProductPrice normalRule) {
        Integer repeatPrice = getRepeatBuyPrices(botId).get(herbName);
        if (repeatPrice == null) {
            return null;
        }

        ProductPrice repeatRule = new ProductPrice();
        repeatRule.setName(herbName);
        repeatRule.setPrice(repeatPrice);
        if (normalRule != null) {
            repeatRule.setId(normalRule.getId());
            repeatRule.setTime(normalRule.getTime());
        }
        return repeatRule;
    }

    private void sortPurchaseCandidates(long botId, CopyOnWriteArrayList<ProductPrice> autoBuyList) {
        autoBuyList.sort(Comparator
                // 重复采购药材优先于普通采购药材。
                .comparing((ProductPrice product) -> !isRepeatPurchase(botId, product))
                // 同一模式下，优先购买优惠差价更大的条目。
                .thenComparing(Comparator.comparingLong(ProductPrice::getPriceDiff).reversed())
                // 差价相同时，保持原有的药材配置顺序。
                .thenComparingLong(product -> product.getId() == null ? Long.MAX_VALUE : product.getId()));
    }

    private boolean canPurchaseHerb(long botId, ProductPrice purchaseRule, String herbName,
                                    double marketPrice, Config config) {
        Map<String, ProductPrice> herbPackMap = herbPackMapMap.computeIfAbsent(
                botId, k -> new ConcurrentHashMap<>());
        ProductPrice packPrice = herbPackMap.computeIfAbsent(herbName, name -> {
            ProductPrice productPrice = new ProductPrice();
            productPrice.setName(name);
            productPrice.setHerbCount(0);
            return productPrice;
        });

        if (packPrice.getHerbCount() <= (config == null ? Integer.MAX_VALUE : config.getLimitHerbsCount())) {
            return true;
        }

        int addPrice = config == null ? 0 : config.getAddPrice();
        return marketPrice <= (double) purchaseRule.getPrice() - addPrice;
    }

    private ProductPrice createPurchaseCandidate(ProductPrice purchaseRule, String code, double marketPrice) {
        ProductPrice candidate = new ProductPrice();
        candidate.setId(purchaseRule.getId());
        candidate.setName(purchaseRule.getName());
        candidate.setPrice(purchaseRule.getPrice());
        candidate.setTime(purchaseRule.getTime());
        candidate.setCode(code);
        candidate.setPriceDiff((int) (purchaseRule.getPrice() - marketPrice));
        return candidate;
    }

    private void enqueuePurchaseCandidate(long botId, ProductPrice candidate) {
        CopyOnWriteArrayList<ProductPrice> autoBuyList = autoBuyListMap.computeIfAbsent(
                botId, k -> new CopyOnWriteArrayList<>());
        if (isRepeatPurchase(botId, candidate)
                && autoBuyList.stream().anyMatch(item -> candidate.getName().equals(item.getName()))) {
            // 重复采购必须保留当前购买码，避免同一药材的其他坊市条目覆盖队首购买码。
            return;
        }
        autoBuyList.add(candidate);
    }

    private boolean isRepeatPurchase(long botId, ProductPrice productPrice) {
        return productPrice != null
                && productPrice.getName() != null
                && getRepeatBuyPrices(botId).containsKey(productPrice.getName());
    }

    private void refreshHerbsIndexByInterval(Bot bot, Config config) {
        long botId = bot.getBotId();
        if (bot.getBotConfig().getAutoBuyHerbsMode() == 0) {
            return;
        }
        long maxDelayMs = Math.max(config == null ? 0 : config.getRandomDelay(), 0) * 1000L;
        if (maxDelayMs <= 0) {
            refreshHerbsIndex(bot);
            return;
        }

        AtomicBoolean scheduledFlag = marketRefreshScheduledFlagMap.computeIfAbsent(botId, k -> new AtomicBoolean(false));
        if (!scheduledFlag.compareAndSet(false, true)) {
            return;
        }

        customPool.submit(() -> {
            try {
                long delayMs = ThreadLocalRandom.current().nextLong(maxDelayMs + 1);
                // logger.info("等待查看坊市药材，botId={}，随机延迟{}毫秒", botId, delayMs);
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                if (bot.getBotConfig().getAutoBuyHerbsMode() == 0) {
                    return;
                }
                refreshHerbsIndex(bot);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                scheduledFlag.set(false);
            }
        });
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

    private void buyNextHerbAfterDelay(Group group, Bot bot) {
        BotConfig botConfig = bot.getBotConfig();
        long botId = bot.getBotId();
        Config config = danCalculator.getConfig(botId);
        long maxDelayMs = Math.max(config == null ? 0 : config.getRandomDelay(), 0) * 1000L;

        if (maxDelayMs <= 0) {
            this.buyHerbs(group, bot);
            return;
        }

        AtomicBoolean scheduledFlag = purchaseDelayScheduledFlagMap.computeIfAbsent(botId, k -> new AtomicBoolean(false));
        if (!scheduledFlag.compareAndSet(false, true)) {
            return;
        }

        long delayMs = ThreadLocalRandom.current().nextLong(maxDelayMs + 1);
        // logger.info("药材购买成功，botId={}，将在{}毫秒后购买下一件药材", botId, delayMs);
        customPool.submit(() -> {
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                if (botConfig.getAutoBuyHerbsMode() != 0
                        && !autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>()).isEmpty()) {
                    this.buyHerbs(group, bot);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                scheduledFlag.set(false);
            }
        });
    }

    @Scheduled(fixedDelay = 5000L, initialDelay = 30000L)
    public void 定时查询坊市() {
        BotFactory.getBots().values().forEach((bot) -> {
            BotConfig botConfig = bot.getBotConfig();
            long botId = bot.getBotId();
            if (botConfig.getAutoBuyHerbsMode() != 0 && !botConfig.isStop()) {
                Config config = danCalculator.getConfig(botId);
                long maxDelayMs = Math.max(config == null ? 0 : config.getRandomDelay(), 0) * 1000L;
                long thresholdMs = Math.max(10000L, maxDelayMs + 2000L);

                if (System.currentTimeMillis() - botConfig.getAutoTaskRefreshTime() > thresholdMs) {
                    autoBuyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>()).clear();
                    botConfig.setStop(false);
                    botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());
                    this.refreshHerbsIndexByInterval(bot, config);
                }
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
                Utils.sendGroupMessage(bot, groupId, (new MessageChain()).at("3889001741").text("查看坊市药材" + makeDrugIndexList.get(drugIndex)));
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
                        Utils.sendGroupMessage(bot, groupId, (new MessageChain()).at("3889001741").text("查看坊市药材" + botConfig.getTaskStatusHerbs()));
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
    
    // -------------------- 智能调整药材价格功能 --------------------
    private void analyzeHerbCount(Long botId, Group group) {
        Config config = danCalculator.getConfig(botId);
        if (config == null) {
            group.sendMessage(new MessageChain().text("配置信息获取失败，无法进行分析"));
            return;
        }

        Map<String, ProductPrice> herbPackMap = herbPackMapMap.getOrDefault(botId, new ConcurrentHashMap<>());
        Map<String, ProductPrice> runtimePurchaseMap = AUTO_BUY_HERBS.getOrDefault(botId, Collections.emptyMap());
        try {
            HerbBacklogAnalyzer analyzer = new HerbBacklogAnalyzer(Paths.get(targetDir));
            String message = analyzer.analyze(botId, config, herbPackMap, runtimePurchaseMap, herbName -> {
                ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(herbName.trim());
                return first == null ? null : first.getPrice();
            });
            group.sendMessage(new MessageChain().text(message));
        } catch (Exception e) {
            logger.error("分析背包药材失败 botId={}", botId, e);
            group.sendMessage(new MessageChain().text("分析背包药材失败：" + e.getMessage()));
        }

        medicinalListMap.put(botId, new ArrayList<>());
    }
}
