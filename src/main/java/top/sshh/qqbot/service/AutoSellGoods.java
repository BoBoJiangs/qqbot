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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.ProductLowPrice;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.Utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AutoSellGoods {
    private static final Logger logger = LoggerFactory.getLogger(AutoSellGoods.class);
    private Map<Long, List<ProductPrice>> herbPackMap = new ConcurrentHashMap();
    @Autowired
    private ProductPriceResponse productPriceResponse;
    @Value("${xxGroupId:0}")
    private Long xxGroupId;

    public AutoSellGoods(){

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
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
            bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
        }
        if ("停止执行".equals(message)) {
            botConfig.setCommand("");
            botConfig.setPage(1);
            herbPackMap.clear();
        }
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 成功上架药材(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        if (Utils.isAtSelf(bot, group) && (message.contains("物品成功上架坊市") || message.contains("道友的上一条指令还没执行完")
                || message.contains("操作失败")|| message.contains("物品数量不足"))) {
            List<ProductPrice> autoBuyList = herbPackMap.get(bot.getBotId());
            if (!autoBuyList.isEmpty()) {
                autoBuyList.remove(0);
            }
            if(autoBuyList.isEmpty()){
                Utils.getRemindGroup(bot,xxGroupId).sendMessage(new MessageChain().text("药材上架完成"));
                botConfig.setCommand("");
            }else{
                this.buyHerbs(autoBuyList,group, bot.getBotConfig());
            }

        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 药材背包(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        if (Utils.isAtSelf(bot, group) && message.contains("拥有数量") && message.contains("坊市数据") && "批量上架药材".equals(botConfig.getCommand())) {
            List<TextMessage> textMessages = messageChain.getMessageByType(TextMessage.class);
            boolean hasNextPage = false;
            TextMessage textMessage = null;
            if (textMessages.size() > 1) {
                textMessage = (TextMessage)textMessages.get(textMessages.size()-1);
            } else {
                textMessage = (TextMessage)textMessages.get(0);
            }

            if (textMessage != null) {
                String msg = textMessage.getText();
                if (message.contains("炼金") && message.contains("坊市数据")) {
                    String[] lines = msg.split("\n");
                    this.parseHerbList(Arrays.asList(lines),bot);
                    if (msg.contains("下一页")) {
                        hasNextPage = true;
                    }
                }

                if (hasNextPage) {
                    botConfig.setPage(botConfig.getPage() + 1);
                    group.sendMessage((new MessageChain()).at("3889001741").text("药材背包" + botConfig.getPage()));
                } else {

                    buyHerbs(this.herbPackMap.get(bot.getBotId()),group,botConfig);

                }
            }
        }

    }

    public void parseHerbList(List<String> medicinalList,Bot bot) throws Exception {
        String currentHerb = null;
        Iterator var2 = medicinalList.iterator();

        while(var2.hasNext()) {
            String line = (String)var2.next();
            line = line.trim();
            if (line.contains("名字：")) {
                currentHerb = line.replaceAll("名字：", "");
            } else if (currentHerb != null && line.contains("拥有数量:")) {
                int count = Integer.parseInt(line.split("拥有数量:|炼金")[1]);

                herbsCountLimit10(bot,count,currentHerb);
                currentHerb = null;
            }
        }

    }

    private void herbsCountLimit10(Bot bot, int quantity,String currentHerb) {
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


    private void buyHerbs(List<ProductPrice> autoBuyList,Group group, BotConfig botConfig) {
        Iterator var3 = autoBuyList.iterator();

        while(var3.hasNext()) {
            ProductPrice productPrice = (ProductPrice)var3.next();

            try {
                if(StringUtils.isEmpty(botConfig.getCommand())){
                    break;
                }
                ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(productPrice.getName().trim());
                if (first != null) {
                    if ((double) first.getPrice() < (double) ProductLowPrice.getLowPrice(productPrice.getName()) * 1.1) {
                        group.sendMessage((new MessageChain()).text("物品：" + first.getName() + "市场价：" + first.getPrice() + "万，炼金：" + ProductLowPrice.getLowPrice(first.getName()) + "万，不上架。"));
                        if (!autoBuyList.isEmpty()) {
                            autoBuyList.remove(0);
                        }
                        this.buyHerbs(autoBuyList,group, botConfig);

                    } else {
                        group.sendMessage((new MessageChain()).at("3889001741")
                                .text("确认坊市上架 " + first.getName() + " " + (first.getPrice() - 10) * 10000 + " " + productPrice.getHerbCount()));
                    }
                }
                break;
            } catch (Exception var6) {
                Thread.currentThread().interrupt();
            }
        }

    }


}
