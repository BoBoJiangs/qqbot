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
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.ProductPrice;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
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

import static top.sshh.qqbot.constant.Constant.targetDir;
import static top.sshh.qqbot.service.utils.Utils.getRemindGroup;
import static top.sshh.qqbot.service.utils.Utils.isAtSelf;

@Component
public class AutoBuyGoods {
    private static final Logger logger = LoggerFactory.getLogger(AutoBuyGoods.class);
    private final ExecutorService customPool = Executors.newCachedThreadPool();
    private Map<Long, List<ProductPrice>> autoBuyMap = new ConcurrentHashMap();
    private List<String> medicinalList = new ArrayList();
    private Map<String, ProductPrice> herbPackMap = new ConcurrentHashMap();
    private List<Integer> makeDrugIndexList = new ArrayList<>();
    @Autowired
    public GroupManager groupManager;
    @Value("${xxGroupId:0}")
    private Long xxGroupId;

    public AutoBuyGoods() {
    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        message = message.trim();
        if (!message.contains("可用命令")) {
            if (message.startsWith("开始捡漏")) {
                try {
                    String[] parts = message.split(" ");

                    int frequency = Integer.parseInt(parts[1].split("频率")[1]);  // "频率2"
                    String action = parts[2];
                    botConfig.setCommand(action);
                    botConfig.setFrequency(frequency);
                    bot.getBotConfig().setEnableAutoBuyLowPrice(true);
                    autoBuyMap.put(bot.getBotId(),new CopyOnWriteArrayList<>());
                    botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());
                    bot.getGroup(botConfig.getGroupId()).sendMessage((new MessageChain()).at("3889001741").text(action));
                } catch (Exception e) {
                    group.sendMessage((new MessageChain()).text("格式不正确\n命令格式: 开始捡漏 频率2 坊市查看装备"));
                }
            }

            if ("停止捡漏".equals(message)) {
                botConfig.setCommand("");
                autoBuyMap.put(bot.getBotId(),new CopyOnWriteArrayList<>());
                bot.getBotConfig().setEnableAutoBuyLowPrice(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("停止捡漏成功"));
            }
        }



    }




    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 成功购买物品(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId();
        boolean isAtSelf = isAtSelf(bot,group,message,xxGroupId);
        if (isGroup && isAtSelf && botConfig.isEnableAutoBuyLowPrice() && (message.contains("道友成功购买") || message.contains("卖家正在进行其他操作") || message.contains("今天已经很努力了") ||
                message.contains("坊市现在太繁忙了") || message.contains("没钱还来买东西") || message.contains("未查询") || message.contains("道友的上一条指令还没执行完"))) {
            botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());
            if (message.contains("道友成功购买")) {
                String buyMessage = String.valueOf(messageChain.get(messageChain.size()-1));
                if(StringUtils.isNotBlank(message)){
                    getRemindGroup(bot,xxGroupId).sendMessage((new MessageChain()).text(buyMessage));
                }

            }

            if(message.contains("今天已经很努力了")){
                botConfig.setEnableAutoBuyLowPrice(false);
            }
            if (!autoBuyMap.get(bot.getBotId()).isEmpty()) {
                autoBuyMap.get(bot.getBotId()).remove(0);
            }
            if(autoBuyMap.get(bot.getBotId()).isEmpty()){
                group.sendMessage((new MessageChain()).at("3889001741").text(botConfig.getCommand()));
            }else{
                this.buyHerbs(group, bot);
            }

        }

    }


    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 自动购买药材(Bot bot, Group group, String message, Integer messageId)  {
        BotConfig botConfig = bot.getBotConfig();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
        if (isGroup && message.contains("不鼓励不保障任何第三方交易行为") && !message.contains("下架") && botConfig.isEnableAutoBuyLowPrice() ) {
            this.customPool.submit(() -> {
                botConfig.setAutoTaskRefreshTime(System.currentTimeMillis());
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
                Map<String, ProductPrice> productMap = (Map)this.groupManager.autoBuyProductMap.computeIfAbsent(bot.getBotId()+"", (k) -> {
                    return new ConcurrentHashMap();
                });
                ProductPrice existingProduct = (ProductPrice)productMap.get(itemName);
                if (existingProduct != null && price <= (double)existingProduct.getPrice()) {
                    if (botConfig.isStop()) {
                        botConfig.setStop(false);
                        return;
                    }

                    if (group.getGroupId() == botConfig.getGroupId()) {
//                        group.sendMessage((new MessageChain()).at("3889001741").text(" 坊市购买 " + code));
                        existingProduct.setCode(code);
                        existingProduct.setPriceDiff((int) (existingProduct.getPrice() - price));
                        autoBuyMap.get(bot.getBotId()).add(existingProduct);
                    }

                }
            }
        }
        autoBuyMap.get(bot.getBotId()).sort(Comparator.comparingLong(ProductPrice::getPriceDiff).reversed());
        if(autoBuyMap.get(bot.getBotId()).isEmpty()){
            try {
                Thread.sleep(bot.getBotConfig().getFrequency() * 1000L);
                group.sendMessage((new MessageChain()).at("3889001741").text(bot.getBotConfig().getCommand()));
            } catch (InterruptedException e) {
                logger.info(e.getMessage());
            }
        }else{
            this.buyHerbs(group, bot);
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
        Iterator var3 = autoBuyMap.get(bot.getBotId()).iterator();

        while(var3.hasNext()) {
            ProductPrice productPrice = (ProductPrice)var3.next();

            try {
                if (bot.getBotConfig().isEnableAutoBuyLowPrice()) {
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
            fixedDelay = 10000L,
            initialDelay = 30000L
    )
    public void 超时执行刷新任务() {
        BotFactory.getBots().values().forEach((bot) -> {
            BotConfig botConfig = bot.getBotConfig();
            if (botConfig.isEnableAutoBuyLowPrice() && StringUtils.isNotBlank(botConfig.getCommand())) {
                if(System.currentTimeMillis() - botConfig.getAutoTaskRefreshTime() > 10000L && bot.getBotConfig().getAutoVerifyModel() == 2){
                    this.autoBuyMap.put(bot.getBotId(), new CopyOnWriteArrayList<>());
                    bot.getGroup(botConfig.getGroupId()).sendMessage((new MessageChain()).at("3889001741").text(bot.getBotConfig().getCommand()));
                }

            }

        });
    }
}
