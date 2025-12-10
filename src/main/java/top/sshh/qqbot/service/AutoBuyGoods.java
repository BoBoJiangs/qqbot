package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.ProductLowPrice;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.Utils;

import java.util.*;
import java.util.concurrent.*;

import static top.sshh.qqbot.service.utils.Utils.isAtSelf;

@Component
public class AutoBuyGoods {
    private static final Logger logger = LoggerFactory.getLogger(AutoBuyGoods.class);
    private final ExecutorService customPool = Executors.newCachedThreadPool();

    /** 每个 botId 对应的捡漏队列 */
    private final Map<Long, List<ProductPrice>> autoBuyMap = new ConcurrentHashMap<>();
    /** 每个 botId 对应的顺序循环命令队列 */
    private final Map<Long, Queue<String>> botCommandQueue = new ConcurrentHashMap<>();
    private final Map<Long,Long> lastMarketTimeMap = new ConcurrentHashMap<>();
    private final Map<Long,Long> lastBuyTimeMap = new ConcurrentHashMap<>();

    @Autowired
    public GroupManager groupManager;

    @Value("${xxGroupId:0}")
    private Long xxGroupId;

    public AutoBuyGoods() {}

    /** 处理用户命令：开始捡漏 / 停止捡漏 */
    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.ONLY_ITSELF)
    public void enableScheduled(Bot bot, Group group, Member member,
                                MessageChain messageChain, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        message = message.trim();

        if (message.startsWith("开始捡漏")) {
            try {
                String[] parts = message.split(" ");
                int frequency = Integer.parseInt(parts[1].split("频率")[1]);
                List<String> actions = Arrays.asList(parts[2].split("&"));

                botConfig.setFrequency(frequency);
                botConfig.setEnableAutoBuyLowPrice(true);
                botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());

                // 初始化循环队列
                botCommandQueue.put(bot.getBotId(), new LinkedList<>(actions));
                autoBuyMap.put(bot.getBotId(), new CopyOnWriteArrayList<>());
                sendNextCommand(bot, botConfig);

                if (botConfig.getCultivationMode() == 1) {
                    botConfig.setStartScheduled(false);
                }
            } catch (Exception e) {
                group.sendMessage(new MessageChain().text("格式不正确\n命令格式: 开始捡漏 频率1 装备1&技能5"));
            }
        }

        if ("停止捡漏".equals(message)) {
            autoBuyMap.put(bot.getBotId(), new CopyOnWriteArrayList<>());
            botCommandQueue.remove(bot.getBotId());
            botConfig.setEnableAutoBuyLowPrice(false);
            group.sendMessage(new MessageChain().reply(messageId).text("停止捡漏成功"));
        }
    }

    /** 发送循环队列中的下一条命令 */
    private void sendNextCommand(Bot bot, BotConfig botConfig) {
        Queue<String> queue = botCommandQueue.get(bot.getBotId());
        if (queue != null && !queue.isEmpty()) {
            String nextCmd = queue.poll();   // 取队头
            bot.getGroup(botConfig.getGroupId())
                    .sendMessage(new MessageChain().at("3889001741").text("坊市查看" + nextCmd));
            queue.offer(nextCmd);  // 放回队尾，实现循环
        }
    }

    /** 购买物品成功/失败处理 */
    @GroupMessageHandler(senderIds = {3889001741L})
    public void 成功购买物品(Bot bot, Group group, Member member,
                             MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId();
        boolean isAtSelf = isAtSelf(bot, group, message, xxGroupId);

        if (autoBuyMap.get(bot.getBotId())!=null && isGroup && isAtSelf && (message.contains("道友成功购买")
                || message.contains("卖家正在进行其他操作")
                || message.contains("今天已经很努力了")
                || message.contains("坊市现在太繁忙了")
                || message.contains("没钱还来买东西")
                ||message.contains("验证码不正确")
                || message.contains("未查询"))) {

            botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());

            // 购买成功
            if (message.contains("道友成功购买")) {
                try {
                    if(autoBuyMap.get(bot.getBotId())!=null && !autoBuyMap.get(bot.getBotId()).isEmpty()){
                        
                        ProductPrice productPrice = autoBuyMap.get(bot.getBotId()).get(0);
                        changeBuyIndex(productPrice,bot);
                        Random random = new Random();
                        String[] successMsgs = getSuccessMsgs(productPrice);
                        sendBuyMessage(bot, random, successMsgs);
                        
                    }

                } catch (ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }

            // 未查询到该物品
            if (message.contains("未查询到该物品") ) {

                try {
                    if(autoBuyMap.get(bot.getBotId())!=null && !autoBuyMap.get(bot.getBotId()).isEmpty()){
                        Utils.forwardMessage(bot, this.xxGroupId, messageChain);
                        ProductPrice productPrice = autoBuyMap.get(bot.getBotId()).get(0);
                        changeBuyIndex(productPrice,bot);
                        Random random = new Random();
                        String[] failMsgs = getFailMsgs(productPrice);
                        sendBuyMessage(bot, random, failMsgs);

                    }
                } catch (ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }

            // 今天已经很努力了
            if (message.contains("今天已经很努力了")) {
                botConfig.setEnableAutoBuyLowPrice(false);
                botCommandQueue.remove(bot.getBotId());
                if (botConfig.getCultivationMode() == 1) {
                    botConfig.setStartScheduled(true);
                }
            }

            // 移除已处理的物品
            if (autoBuyMap.get(bot.getBotId())!=null && !autoBuyMap.get(bot.getBotId()).isEmpty()) {
                autoBuyMap.get(bot.getBotId()).remove(0);
            }

            // 如果队列为空或已处理完，循环发送下一条命令
            if (autoBuyMap.get(bot.getBotId())!=null && autoBuyMap.get(bot.getBotId()).isEmpty()) {
                sendNextCommand(bot, botConfig);
            } else {
                this.buyHerbs(group, bot);
            }
        }
    }

    private void changeBuyIndex(ProductPrice productPrice,Bot bot){
        if(productPrice.getName().contains("无罪")){
            botCommandQueue.put(bot.getBotId(), new LinkedList<>(Arrays.asList(  "装备2")));
        }
        if(productPrice.getName().contains("原罪") || productPrice.getName().contains("东皇") ||
         productPrice.getName().contains("天罪")){
            botCommandQueue.put(bot.getBotId(), new LinkedList<>(Arrays.asList(  "装备1")));
        }
        if(productPrice.getName().contains("日月双华")){
            botCommandQueue.put(bot.getBotId(), new LinkedList<>(Arrays.asList(  "技能4")));
        }
        if(productPrice.getName().contains("灭剑")){
            botCommandQueue.put(bot.getBotId(), new LinkedList<>(Arrays.asList(  "技能5")));
        }
        if(productPrice.getName().contains("阴阳")){
            botCommandQueue.put(bot.getBotId(), new LinkedList<>(Arrays.asList(  "技能7")));
        }
    }

    private void sendBuyMessage(Bot bot, Random random, String[] successMsgs) throws InterruptedException, ExecutionException {
        String successMsg = successMsgs[random.nextInt(successMsgs.length)];
        if (bot.isFriend(bot.getBotConfig().getMasterQQ())) {
            bot.sendPrivateMessage(bot.getBotConfig().getMasterQQ(),
                    (new MessageChain()).text(successMsg));
        }else{
            if(xxGroupId>0){
                bot.sendGroupMessage(xxGroupId, new MessageChain().text(successMsg));
            }

        }
    }

    private String[] getFailMsgs(ProductPrice productPrice) {
        return new String[]{
                "啊哦！" + productPrice.getName() + "(" + productPrice.getBuyPrice() + "万)一眨眼被隔壁老王顺走了！",
                "糟糕！你正准备拿"+productPrice.getBuyPrice() + "万的" + productPrice.getName() + "时，突然被妖风刮跑，别人拣到了…",
                "道友刚伸手，就见一只仓鼠叼走了价值" + productPrice.getBuyPrice() + "万的" + productPrice.getName() + "！",
                "真惨！"+productPrice.getBuyPrice() + "万的" + productPrice.getName() + "刚出炉，你还没来得及喊666就被别人抢光了～",
                "嘿嘿，别哭，" + productPrice.getName() + "(" + productPrice.getBuyPrice() + "万)已经跟别人私奔了～"
        };
    }

    private String[] getSuccessMsgs(ProductPrice productPrice) {
        return new String[]{
                "咦？系统出Bug了，居然给你掉出来一个" + productPrice.getBuyPrice() + "万的" + productPrice.getName() + "！",
                "道友运气爆棚！" + productPrice.getBuyPrice() + "万的" + productPrice.getName() + "轻轻松松到手～",
                "叮！快递小哥敲门：您点的" + productPrice.getName() + "(" + productPrice.getBuyPrice() + "万)已到账！",
                "神秘商人笑眯眯地塞给你一个" + productPrice.getName() + "，顺便还要了你" + productPrice.getBuyPrice() + "万灵石～",
                "恭喜！你刚刚踩到香蕉皮摔倒，顺手捡起一个" + productPrice.getBuyPrice() + "万的" + productPrice.getName() + "。"
        };
    }

    /** 自动购买药材 */
    @GroupMessageHandler(senderIds = {3889001741L})
    public void 自动购买药材(Bot bot, Group group, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        boolean isAtSelf = Utils.isAtSelf(bot,message);
//        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();

        if ((message.contains("不鼓励不保障任何第三方交易行为"))
                && !message.contains("下架") ) {
            this.customPool.submit(() -> {
                botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());
                this.processMarketMessage(bot, group, message,isAtSelf);
            });

        }
    }

    private void getMarketIndex(Bot bot, Group group, String message) {
        BotConfig botConfig = bot.getBotConfig();
        String name =  ProductLowPrice.getProduceIndex(message);
        if(!StringUtils.isEmpty(name)){
            bot.getGroup(botConfig.getGroupId())
                    .sendMessage(new MessageChain().at("3889001741").text("坊市查看"+name));
        }
//        if(message.startsWith("原罪") || message.startsWith("东皇") || message.startsWith("天罪")){
//            bot.getGroup(botConfig.getGroupId())
//                    .sendMessage(new MessageChain().at("3889001741").text("坊市查看装备"));
//        } else if (message.startsWith("无罪") ) {
//            bot.getGroup(botConfig.getGroupId())
//                    .sendMessage(new MessageChain().at("3889001741").text("坊市查看装备2"));
//        }else if (message.startsWith("五指拳心剑")) {
//            bot.getGroup(botConfig.getGroupId())
//                    .sendMessage(new MessageChain().at("3889001741").text("坊市查看技能"));
//        }else if (message.startsWith("坐忘论") ) {
//            bot.getGroup(botConfig.getGroupId())
//                    .sendMessage(new MessageChain().at("3889001741").text("坊市查看技能2"));
//        }else if (message.startsWith("太虚") ) {
//            bot.getGroup(botConfig.getGroupId())
//                    .sendMessage(new MessageChain().at("3889001741").text("坊市查看技能3"));
//        }else if (message.startsWith("袖里") ) {
//            bot.getGroup(botConfig.getGroupId())
//                    .sendMessage(new MessageChain().at("3889001741").text("坊市查看技能8"));
//        }else if (message.startsWith("真龙") ) {
//            bot.getGroup(botConfig.getGroupId())
//                    .sendMessage(new MessageChain().at("3889001741").text("坊市查看技能7"));
//        }else if (message.startsWith("日月") || message.startsWith("无暇")) {
//            bot.getGroup(botConfig.getGroupId())
//                    .sendMessage(new MessageChain().at("3889001741").text("坊市查看技能5"));
//        }else if(message.startsWith("剑芦")){
//            bot.getGroup(botConfig.getGroupId())
//                    .sendMessage(new MessageChain().at("3889001741").text("坊市查看药材2"));
//        }
    }

    private void processMarketMessage(Bot bot, Group group, String message,boolean isAtSelf) {
        String[] split = message.split("\n");
        for (String s : split) {
            if (s.startsWith("价格") && s.contains("mqqapi")) {

                String[] split1 = s.split("\\[|\\]");
                String code = s.split("%E5%9D%8A%E5%B8%82%E8%B4%AD%E4%B9%B0|&")[1];
                double price = this.extractPrice(s);
                String itemName = this.extractItemName(split1[1].trim());

                Map<String, ProductPrice> productMap = groupManager.autoBuyProductMap
                        .computeIfAbsent(bot.getBotId() + "", k -> new ConcurrentHashMap<>());

                ProductPrice existingProduct = productMap.get(itemName);
                if (existingProduct != null && price <= existingProduct.getPrice()) {
                    if (isAtSelf) {
                        existingProduct.setCode(code);
                        existingProduct.setBuyPrice(price);
                        existingProduct.setPriceDiff((int) (existingProduct.getPrice() - price));
                        autoBuyMap.computeIfAbsent(bot.getBotId(), k -> new CopyOnWriteArrayList<>())
                                .add(existingProduct);
                        break;
                    }else{
                        long currentTime = System.currentTimeMillis();
                        if (lastMarketTimeMap.get(bot.getBotId()) == null) {
                            lastMarketTimeMap.put(bot.getBotId(), System.currentTimeMillis());
                            getMarketIndex(bot,group,itemName);
                            break;
                        }else{
                            if (currentTime - lastMarketTimeMap.get(bot.getBotId()) > 5000) {
                                lastMarketTimeMap.put(bot.getBotId(), System.currentTimeMillis());
                                getMarketIndex(bot,group,itemName);
                                break;
                            }
                        }


                    }
                }
            }
        }
        if(isAtSelf){
            if (autoBuyMap.get(bot.getBotId()).isEmpty()) {
                try {
                    if(bot.getBotConfig().getFrequency()>0){
                        Thread.sleep(bot.getBotConfig().getFrequency() * 1000L);
                    }
                    sendNextCommand(bot, bot.getBotConfig());
                } catch (InterruptedException e) {
                    logger.info(e.getMessage());
                }
            } else {
                this.buyHerbs(group, bot);

            }
        }else{
            long currentTime = System.currentTimeMillis();
            if (lastMarketTimeMap.get(bot.getBotId()) == null) {
                lastMarketTimeMap.put(bot.getBotId(), System.currentTimeMillis());
                this.buyHerbs(group, bot);
            }else{
                if (currentTime - lastMarketTimeMap.get(bot.getBotId()) > 5000) {
                    lastMarketTimeMap.put(bot.getBotId(), System.currentTimeMillis());
                    this.buyHerbs(group, bot);
                }
            }
        }

    }

    private double extractPrice(String message) {
        if (message.contains("万 [")) {
            return Double.parseDouble(message.split("价格:|万")[1]);
        } else if (message.contains("亿 [")) {
            return Double.parseDouble(message.split("价格:|亿")[1]) * 10000.0;
        } else {
            return Double.MAX_VALUE;
        }
    }

    private String extractItemName(String rawName) {
        StringBuilder result = new StringBuilder();
        for (char c : rawName.toCharArray()) {
            if (Character.toString(c).matches("[\\u4e00-\\u9fa5()（）]")) {
                result.append(c);
            }
        }
        return result.toString();
    }

    private void buyHerbs(Group group, Bot bot) {
        for (ProductPrice productPrice : autoBuyMap.get(bot.getBotId())) {
            try {
                bot.getGroup(bot.getBotConfig().getGroupId()).sendMessage(new MessageChain().at("3889001741")
                        .text("坊市购买 " + productPrice.getCode()));
                break;
            } catch (Exception e) {
                logger.error("发送购买消息失败");
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 定时检查任务，刷新捡漏命令 */
    @Scheduled(fixedDelay = 10000L, initialDelay = 30000L)
    public void 超时执行刷新任务() {
        BotFactory.getBots().values().forEach(bot -> {
            BotConfig botConfig = bot.getBotConfig();
            if (botConfig.isEnableAutoBuyLowPrice()
                    && System.currentTimeMillis() - botConfig.getAutoTaskRefreshTime() > 10000L
                    && botConfig.getAutoVerifyModel() == 2) {

                autoBuyMap.put(bot.getBotId(), new CopyOnWriteArrayList<>());
                sendNextCommand(bot, botConfig);
            }
        });
    }
}
