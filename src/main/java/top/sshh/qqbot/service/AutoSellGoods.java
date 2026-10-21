package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import top.sshh.qqbot.constant.Constant;
import top.sshh.qqbot.data.ProductLowPrice;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.CompletionMessageSender;
import top.sshh.qqbot.service.utils.HerbBackpackParser;
import top.sshh.qqbot.service.utils.MarketPriceUtils;
import top.sshh.qqbot.service.utils.Utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AutoSellGoods {
    private static final Logger logger = LoggerFactory.getLogger(AutoSellGoods.class);
    private static final String BATCH_HERBS = "批量上架药材";
    private final ScheduledExecutorService herbScheduler;
    private final Map<Long, Object> herbSaleLocks = new ConcurrentHashMap<>();
    private final Map<Long, HerbSaleSession> herbSales = new ConcurrentHashMap<>();
    private final Map<Long, LinkedHashSet<String>> herbReplyHistory = new ConcurrentHashMap<>();
    private Map<Long, List<ProductPrice>> herbPackMap = new ConcurrentHashMap();
    private Map<Long, List<ProductPrice>> equipPackMap = new ConcurrentHashMap();
    private Map<Long, List<ProductPrice>> pillPackMap = new ConcurrentHashMap();
    @Autowired
    private ProductPriceResponse productPriceResponse;
    @Value("${xxGroupId:0}")
    private long xxGroupId;
    @Autowired
    private GroupManager groupManager;

    public AutoSellGoods() {
        this(Executors.newScheduledThreadPool(2, task -> {
            Thread thread = new Thread(task, "batch-herb-selling");
            thread.setDaemon(true);
            return thread;
        }));
    }

    AutoSellGoods(ScheduledExecutorService scheduler) {
        this.herbScheduler = scheduler;
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
        synchronized (herbSaleLock(bot.getBotId())) {
            if (BATCH_HERBS.equals(message)) {
                if (herbSales.containsKey(bot.getBotId()) && BATCH_HERBS.equals(botConfig.getCommand())) {
                    group.sendMessage(new MessageChain().text("批量上架药材正在执行，请先停止执行后再开始。"));
                    return;
                }
                cancelHerbSale(bot.getBotId());
                Group executionGroup = bot.getGroup(groupId);
                if (executionGroup == null) {
                    group.sendMessage(new MessageChain().text("无法获取修炼群，未开始批量上架药材。"));
                    return;
                }
                botConfig.setCommand(BATCH_HERBS);
                botConfig.setStop(false);
                botConfig.setPage(1);
                List<ProductPrice> queue = new ArrayList<>();
                herbPackMap.put(bot.getBotId(), queue);
                HerbSaleSession session = new HerbSaleSession(bot, executionGroup, queue);
                herbSales.put(bot.getBotId(), session);
                sendNextHerbOperation(session);
            }
            if ("批量炼金装备".equals(message)) {
                cancelHerbSale(bot.getBotId());
                botConfig.setCommand("批量炼金装备");
                botConfig.setPage(1);
                equipPackMap.remove(bot.getBotId());
                Utils.sendGroupMessage(bot, groupId, new MessageChain().at("3889001741").text("我的背包"));
            }
            if ("批量炼金丹药".equals(message)) {
                cancelHerbSale(bot.getBotId());
                botConfig.setCommand("批量炼金丹药");
                botConfig.setPage(1);
                pillPackMap.remove(bot.getBotId());
                Utils.sendGroupMessage(bot, groupId, new MessageChain().at("3889001741").text("丹药背包"));
            }
            if ("停止执行".equals(message)) {
                cancelHerbSale(bot.getBotId());
                botConfig.setCommand("");
                botConfig.setPage(1);
                herbPackMap.remove(bot.getBotId());
                equipPackMap.remove(bot.getBotId());
                pillPackMap.remove(bot.getBotId());
            }
        }
    }

    @GroupMessageHandler(senderIds = { 3889001741L })
    public void 成功上架药材(Bot bot, Group group, Member member, MessageChain messageChain, String message,
            Integer messageId) {
        synchronized (herbSaleLock(bot.getBotId())) {
            HerbSaleSession session = herbSales.get(bot.getBotId());
            String text = gameText(message, messageChain);
            if (!matchesHerbReply(session, group, messageChain, text)) return;
            boolean captcha = text.contains("请点击") && text.contains("表情");
            boolean verified = !captcha && !text.contains("验证码不正确") && !text.contains("验证失败")
                    && !text.contains("未验证通过") && !text.contains("验证未通过")
                    && (text.contains("不需要验证") || text.contains("验证成功") || text.contains("验证通过")
                        || text.contains("奖励") && text.contains("灵石"));
            boolean operationResult = text.contains("物品成功上架坊市") || text.contains("炼金成功")
                    || text.contains("上架价格过高") || text.contains("上架价格过低") || text.contains("物品数量不足")
                    || text.contains("道友的上一条指令还没执行完") || text.contains("操作失败")
                    || text.contains("坊市现在太繁忙了");
            // 背包页由药材背包处理器去重，此处理器不能提前消费同一条事件。
            if (!captcha && !verified && !operationResult && !text.contains("解除限制")) return;
            if (!rememberHerbReply(bot.getBotId(), group.getGroupId(), messageId)) return;
            if (text.contains("解除限制")) {
                stopHerbSaleWithError(session, "账号受限，已停止批量上架药材。");
                return;
            }
            if (captcha) {
                session.waitingForCaptcha = true;
                bot.getBotConfig().setStop(true);
                cancelHerbDelay(session);
                return;
            }
            HerbOperation current = session.current;
            boolean matchesOperation = current != null && matchesHerbOperationReply(current, messageChain);
            boolean success = matchesOperation && (current.kind == HerbOperationKind.LIST
                    && text.contains("物品成功上架坊市")
                    || current.kind == HerbOperationKind.ALCHEMY && text.contains("炼金成功"));
            boolean skip = matchesOperation && (current.kind == HerbOperationKind.LIST
                    && (text.contains("上架价格过高") || text.contains("上架价格过低"))
                    || current.product != null && text.contains("物品数量不足"));
            if (session.waitingForCaptcha) {
                if (verified) {
                    session.waitingForCaptcha = false;
                    bot.getBotConfig().setStop(false);
                    if (!success && !skip) {
                        scheduleHerbOperation(session);
                        return;
                    }
                } else if (!success && !skip) {
                    return;
                }
                // 成功回执可能先于验证奖励到达：先确认当前项，仍等验证后才发送下一项。
            }
            if (!matchesOperation) return;
            if (success || skip) {
                cancelHerbDelay(session);
                session.current = null;
                session.queue.remove(current.product);
                if (skip) {
                    group.sendMessage(new MessageChain().text("药材：" + current.product.getName()
                            + "上架价格或数量不符合要求，已跳过。"));
                }
                if (session.queue.isEmpty()) {
                    finishHerbSelling(group, bot.getBotConfig());
                } else {
                    scheduleHerbOperation(session);
                }
            } else if (text.contains("道友的上一条指令还没执行完") || text.contains("操作失败")
                    || text.contains("坊市现在太繁忙了")) {
                if (current.attempts >= 3) {
                    stopHerbSaleWithError(session, "当前指令连续失败，已停止批量上架药材；未处理项目仍保留。");
                } else {
                    // 临时失败只重试当前项，不能移除队首、触发下一项。
                    scheduleHerbOperation(session);
                }
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
                botConfig.setCommand("");
                CompletionMessageSender.send(bot, xxGroupId, "装备炼金完成");
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
                botConfig.setCommand("");
                CompletionMessageSender.send(bot, xxGroupId, "丹药炼金完成");
            } else {
                this.alchemyPill(dataList, group, botConfig);
            }

        }

    }

    @GroupMessageHandler(senderIds = { 3889001741L })
    public void 药材背包(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId)
            throws Exception {
        synchronized (herbSaleLock(bot.getBotId())) {
            HerbSaleSession session = herbSales.get(bot.getBotId());
            String text = gameText(message, messageChain);
            if (!matchesHerbReply(session, group, messageChain, text) || session.inventoryLoaded
                    || session.waitingForCaptcha || session.current == null
                    || session.current.kind != HerbOperationKind.BACKPACK) return;
            String content = Utils.getForwardMessageText(messageChain);
            if (StringUtils.isBlank(content)) content = StringUtils.defaultString(message);
            if (!content.contains("药材背包") && !(content.contains("炼金") && content.contains("坊市数据"))) return;
            Matcher page = Pattern.compile("第\\s*(\\d+)\\s*页").matcher(content);
            if (page.find() && Integer.parseInt(page.group(1)) != session.inventoryPage) return;
            if (!matchesHerbOperationReply(session.current, messageChain)
                    || !rememberHerbReply(bot.getBotId(), group.getGroupId(), messageId)) return;
            cancelHerbDelay(session);
            parseHerbList(Arrays.asList(content.split("\n")), bot);
            session.current = null;
            if (content.contains("下一页")) {
                session.inventoryPage++;
                bot.getBotConfig().setPage(session.inventoryPage);
            } else {
                session.inventoryLoaded = true;
            }
            if (session.inventoryLoaded && session.queue.isEmpty()) {
                finishHerbSelling(group, bot.getBotConfig());
            } else {
                scheduleHerbOperation(session);
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
        boolean b = !Constant.ALCHEMY_PROTECTED_ITEMS.contains(currentPill);
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

    private void buyHerbs(List<ProductPrice> queue, Group group, BotConfig config) {
        Bot bot = group.getBot();
        synchronized (herbSaleLock(bot.getBotId())) {
            if (!BATCH_HERBS.equals(config.getCommand())) return;
            HerbSaleSession session = herbSales.get(bot.getBotId());
            if (session == null) {
                session = new HerbSaleSession(bot, group, queue == null ? new ArrayList<>() : queue);
                session.inventoryLoaded = true;
                herbPackMap.put(bot.getBotId(), session.queue);
                herbSales.put(bot.getBotId(), session);
            }
            if (session.current == null && session.delayToken == null) sendNextHerbOperation(session);
        }
    }

    private void sendNextHerbOperation(HerbSaleSession session) {
        if (!isCurrentHerbSale(session) || session.waitingForCaptcha || session.current != null) return;
        if (session.bot.getBotConfig().isStop()) {
            scheduleHerbOperation(session);
            return;
        }
        if (!session.inventoryLoaded) {
            session.current = new HerbOperation(HerbOperationKind.BACKPACK, null,
                    "药材背包" + (session.inventoryPage == 1 ? "" : session.inventoryPage));
            sendHerbOperation(session);
            return;
        }
        while (!session.queue.isEmpty()) {
            ProductPrice product = session.queue.get(0);
            ProductPrice price = productPriceResponse.getFirstByNameOrderByTimeDesc(product.getName().trim());
            if (price == null || price.getPrice() <= 0) {
                session.group.sendMessage(new MessageChain().text("药材：" + product.getName() + "未找到坊市价格，已跳过"));
                session.queue.remove(0);
                continue;
            }
            boolean alchemy = price.getPrice() < ProductLowPrice.getLowPrice(product.getName()) * 1.1;
            long listingPrice = MarketPriceUtils.limitListingPrice(price, ((long) price.getPrice() - 10L) * 10000L);
            session.current = new HerbOperation(alchemy ? HerbOperationKind.ALCHEMY : HerbOperationKind.LIST, product,
                    alchemy ? "炼金 " + product.getName() + " " + product.getHerbCount()
                            : "确认坊市上架 " + product.getName() + " " + listingPrice + " " + product.getHerbCount());
            sendHerbOperation(session);
            if (alchemy && isCurrentHerbSale(session)) {
                session.group.sendMessage(new MessageChain().text("物品：" + price.getName() + "市场价：" + price.getPrice()
                        + "万，炼金：" + ProductLowPrice.getLowPrice(price.getName()) + "万，直接炼金处理。"));
            }
            return; // 炼金和上架都必须等待回执，不能发送后立即出队。
        }
        finishHerbSelling(session.group, session.bot.getBotConfig());
    }

    private void sendHerbOperation(HerbSaleSession session) {
        HerbOperation operation = session.current;
        if (operation == null || !isCurrentHerbSale(session) || session.waitingForCaptcha) return;
        operation.attempts++;
        try {
            int messageId = session.group.sendMessage(new MessageChain().at("3889001741").text(operation.command));
            if (messageId != 0) operation.sentMessageIds.add(String.valueOf(messageId));
        } catch (Exception e) {
            logger.warn("批量上架药材发送失败 botId={} command={}", session.bot.getBotId(), operation.command, e);
            stopHerbSaleWithError(session, "指令发送失败，已停止批量上架药材，请检查后重新开始。");
        }
    }

    private void scheduleHerbOperation(HerbSaleSession session) {
        if (!isCurrentHerbSale(session) || session.waitingForCaptcha || session.delayToken != null) return;
        Object token = new Object();
        session.delayToken = token;
        try {
            session.nextTask = herbScheduler.schedule(() -> {
                synchronized (herbSaleLock(session.bot.getBotId())) {
                    if (!isCurrentHerbSale(session) || session.delayToken != token) return;
                    session.delayToken = null;
                    session.nextTask = null;
                    if (session.waitingForCaptcha) return;
                    if (session.bot.getBotConfig().isStop()) {
                        scheduleHerbOperation(session);
                    } else if (session.current == null) {
                        buyHerbs(session.queue, session.group, session.bot.getBotConfig());
                    } else {
                        sendHerbOperation(session);
                    }
                }
            }, 2L, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            stopHerbSaleWithError(session, "任务调度失败，已停止批量上架药材。");
        }
    }

    private Object herbSaleLock(long botId) {
        return herbSaleLocks.computeIfAbsent(botId, ignored -> new Object());
    }

    private boolean isCurrentHerbSale(HerbSaleSession session) {
        return herbSales.get(session.bot.getBotId()) == session
                && BATCH_HERBS.equals(session.bot.getBotConfig().getCommand());
    }

    private boolean matchesHerbReply(HerbSaleSession session, Group group, MessageChain chain, String text) {
        if (session == null || !isCurrentHerbSale(session) || group == null
                || group.getGroupId() != session.group.getGroupId()) return false;
        String botId = String.valueOf(session.bot.getBotId());
        if (chain != null) {
            List<AtMessage> mentions = chain.getMessageByType(AtMessage.class);
            if (!mentions.isEmpty()) return mentions.stream().anyMatch(at -> botId.equals(at.getQq()));
            if (session.current != null && !session.current.sentMessageIds.isEmpty()
                    && chain.getMessageByType(ReplyMessage.class).stream()
                        .anyMatch(reply -> botId.equals(reply.getQq())
                                && session.current.sentMessageIds.contains(reply.getId()))) return true;
        }
        return Pattern.compile("(?<!\\d)" + botId + "(?!\\d)").matcher(text).find();
    }

    private boolean matchesHerbOperationReply(HerbOperation operation, MessageChain chain) {
        if (chain == null || operation.sentMessageIds.isEmpty()) return true;
        List<ReplyMessage> replies = chain.getMessageByType(ReplyMessage.class);
        return replies.isEmpty() || replies.stream()
                .anyMatch(reply -> operation.sentMessageIds.contains(reply.getId()));
    }

    private String gameText(String message, MessageChain chain) {
        return StringUtils.defaultString(message) + "\n" + Utils.getMessageText(chain);
    }

    private boolean rememberHerbReply(long botId, long groupId, Integer messageId) {
        if (messageId == null || messageId == 0) return true;
        LinkedHashSet<String> history = herbReplyHistory.computeIfAbsent(botId, ignored -> new LinkedHashSet<>());
        if (!history.add(groupId + ":" + messageId)) return false;
        while (history.size() > 2048) history.remove(history.iterator().next());
        return true;
    }

    private void cancelHerbDelay(HerbSaleSession session) {
        session.delayToken = null;
        if (session.nextTask != null) session.nextTask.cancel(false);
        session.nextTask = null;
    }

    private void cancelHerbSale(long botId) {
        HerbSaleSession session = herbSales.remove(botId);
        if (session != null) cancelHerbDelay(session);
    }

    private void stopHerbSaleWithError(HerbSaleSession session, String message) {
        if (!isCurrentHerbSale(session)) return;
        cancelHerbSale(session.bot.getBotId());
        session.bot.getBotConfig().setCommand("");
        session.group.sendMessage(new MessageChain().text(message));
    }

    private void finishHerbSelling(Group group, BotConfig botConfig) {
        cancelHerbSale(group.getBot().getBotId());
        botConfig.setCommand("");
        CompletionMessageSender.send(group.getBot(), xxGroupId, "药材上架完成");
    }

    @PreDestroy
    public void shutdown() {
        for (Long botId : new ArrayList<>(herbSales.keySet())) {
            synchronized (herbSaleLock(botId)) {
                cancelHerbSale(botId);
            }
        }
        herbScheduler.shutdownNow();
    }

    private enum HerbOperationKind { BACKPACK, LIST, ALCHEMY }

    private static class HerbOperation {
        final HerbOperationKind kind;
        final ProductPrice product;
        final String command;
        int attempts;
        final Set<String> sentMessageIds = new HashSet<>();

        HerbOperation(HerbOperationKind kind, ProductPrice product, String command) {
            this.kind = kind;
            this.product = product;
            this.command = command;
        }
    }

    private static class HerbSaleSession {
        final Bot bot;
        final Group group;
        final List<ProductPrice> queue;
        int inventoryPage = 1;
        boolean inventoryLoaded;
        boolean waitingForCaptcha;
        HerbOperation current;
        Object delayToken;
        ScheduledFuture<?> nextTask;

        HerbSaleSession(Bot bot, Group group, List<ProductPrice> queue) {
            this.bot = bot;
            this.group = group;
            this.queue = queue;
        }
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
