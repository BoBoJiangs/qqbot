//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.MessageNumber;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.data.RemindTime;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GroupManager {
    private static final Logger logger = LoggerFactory.getLogger(GroupManager.class);
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    Map<String, RemindTime> mjXslmap = new ConcurrentHashMap();
    Map<String, RemindTime> ltmap = new ConcurrentHashMap();

    private static final ForkJoinPool customPool = new ForkJoinPool(20);
    private static final List<String> MJ_TEXT_LIST = Arrays.asList(" 【秘境结算提醒】秘境试炼已结束！此番奇遇定让您感悟大道，快查看收获，或许有突破境界的机缘！",
            "【秘境结算提醒】叮！秘境探索结算中，本次收获：四库全书 1 本，戏书 1 本，以及队友的嫌弃三连～",
            "【秘境结算提醒】秘境探索时间已到！您在秘境中历经磨砺，珍贵法宝与上古传承正等待您开启！",
            "【秘境结算提醒】秘境之行圆满收官！天地灵气凝聚的机缘已化作奖励，助您打破修为桎梏！",
            "【秘境结算提醒】秘境探索结束，本次成就：迷路 10 次，摔进陷阱 3 次，荣获‘秘境路痴’称号！",
            "【秘境结算提醒】秘境时限已至！您探索秘境的收获即将揭晓，说不定能获得改变命运的仙缘！",
            "【秘境结算提醒】秘境任务已结算！您在秘境中的探索成果斐然，丰厚奖励助您早日飞升！",
            "【秘境结算提醒】秘境时间到！您在秘境中积累的气运，已化作珍贵资源，快来开启这份修仙大礼！");
    private static final List<String> XSL_TEXT_LIST = Arrays.asList("【悬赏结算提醒】悬赏令时限已至！斩妖除魔的功绩化作海量灵石，速来领取这份天道酬勤的嘉奖！",
            "【悬赏结算提醒】通缉妖邪的期限已到，您的英勇战绩已被悬赏阁收录，丰厚奖赏静待仙友笑纳！",
            "【悬赏结算提醒】悬赏任务圆满收官！此番降魔除祟之举，必能助您在修仙路上再添助力，速来领奖！",
            "【悬赏结算提醒】悬赏令结算时刻已到！您的侠义之名远扬，丰厚灵石与珍贵功法正等着您来领取！",
            "【悬赏结算提醒】悬赏令时间到！您的努力终有回报，海量资源已备好，助您在修仙之途大步迈进！",
            "【悬赏结算提醒】悬赏任务结算啦！您的英勇无畏让修真界重归安宁，快来领取这份荣耀奖赏！",
            "【悬赏结算提醒】悬赏令结算时刻来临！您的付出已化作修仙至宝，速来开启这份惊喜收获！");
    private static final List<String> LT_TEXT_LIST = Arrays.asList("【灵田结算提醒】隔壁炼丹童子盯着您的灵田流口水，请速速收药！",
            "【灵田结算提醒】灵田中的灵植已成熟！饱满的灵果蕴含天地精华，采摘后可助您炼丹突破，福泽深厚！",
            "【灵田结算提醒】灵植成熟，丰收已至！这片灵田的产出，将为您的修仙大业增添无限生机与灵力！",
            "【灵田结算提醒】灵田丰收啦！您的辛勤耕耘终有回报，成熟的灵植能炼制出逆天丹药，快来收取！",
            "【灵田结算提醒】灵植已到收获时节！采摘灵田中的宝贝，为您的修仙之路积累丰厚资源，前程似锦！",
            "【灵田结算提醒】灵田中的灵植焕发生机！此刻收取，定能收获满满灵气，助您修为一日千里！",
            "【灵田结算提醒】灵植成熟可收！您的灵田培育出的珍稀作物，能为您带来意想不到的修仙助力！",
            "【灵田结算提醒】灵田收获时间到！这片充满灵气的土地，为您孕育出了珍贵的灵植，速来采摘！");
    private static final String FILE_PATH = "./cache/task_data.json";
//    @Value("${botId}")
//    private Long botId;

    public static List<Long> remindGroupIdList = Arrays.asList(1023764416L, 971327442L, 679831529L, 824484501L, 690933736L, 978207420L);
    //    @Autowired
//    public DanCalculator danCalculator;
    private Map<String, Map<String, PendingLingTianRecord>> pendingLingTianRecords = new ConcurrentHashMap();
    private Map<String, Set<String>> excludeAlchemyMap = new ConcurrentHashMap();
    private Map<String, Set<String>> excludeSellMap = new ConcurrentHashMap();
    public Map<String, Map<String, ProductPrice>> autoBuyProductMap = new ConcurrentHashMap();
    public Map<String, MessageNumber> MESSAGE_NUMBER_MAP = new ConcurrentHashMap();

    public GroupManager() {

    }


    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        message = message.trim();
        if (message.equals("清空发言统计")) {
            MESSAGE_NUMBER_MAP.put(bot.getBotId() + "", new MessageNumber(0, System.currentTimeMillis()));
            group.sendMessage((new MessageChain()).reply(messageId).text("执行成功"));
        } else if (message.equals("同步发言统计")) {
            saveTasksToFile();
            group.sendMessage((new MessageChain()).reply(messageId).text("执行成功"));
        } else if (message.equals("同步数据")) {
            saveTasksToFile();
            group.sendMessage((new MessageChain()).reply(messageId).text("执行成功"));
        }

        Long botId;
        Long groupId;
        String groupString;
        if (message.startsWith("添加上架排除物品")) {
            botId = bot.getBotId();
            groupString = message.substring(8).trim();
            this.modifyExcludeSell(botId, groupString, true);
            group.sendMessage((new MessageChain()).reply(messageId).text("添加成功"));
            return;
        }

        if (message.startsWith("移除上架排除物品")) {
            botId = bot.getBotId();
            groupString = message.substring(8).trim();
            if (StringUtils.isNotBlank(groupString)) {
                this.modifyExcludeSell(botId, groupString, false);
            } else {
                excludeSellMap.clear();
            }

            group.sendMessage((new MessageChain()).reply(messageId).text("移除成功"));
            return;
        }

        String typeString;
        if ("查看上架排除物品".equals(message)) {
            typeString = this.getExcludeSellList(bot.getBotId());
            group.sendMessage((new MessageChain()).reply(messageId).text("当前上架排除物品：\n" + typeString));
            return;
        }

        if (message.startsWith("添加炼金排除物品")) {
            botId = bot.getBotId();
            groupString = message.substring(8).trim();
            this.modifyExcludeAlchemy(botId, groupString, true);
            group.sendMessage((new MessageChain()).reply(messageId).text("添加成功"));
            return;
        }

        if (message.startsWith("移除炼金排除物品")) {

            botId = bot.getBotId();
            groupString = message.substring(8).trim();
            if (StringUtils.isNotBlank(groupString)) {
                this.modifyExcludeAlchemy(botId, groupString, false);
            } else {
                excludeAlchemyMap.clear();
            }

            group.sendMessage((new MessageChain()).reply(messageId).text("移除成功"));
            return;
        }

        if ("查看炼金排除物品".equals(message)) {
            typeString = this.getExcludeAlchemyList(bot.getBotId());
            group.sendMessage((new MessageChain()).reply(messageId).text("当前炼金排除物品：\n" + typeString));
        }

    }

    // cron 表达式：0 55 7 * * * 表示每天早上 7 点 55 分执行
    @Scheduled(cron = "0 58 7 * * *")
    public void executeMessageTask() {
        logger.info("定时清空发言统计");
        BotFactory.getBots().values().forEach((bot) -> {
            MESSAGE_NUMBER_MAP.put(bot.getBotId() + "", new MessageNumber(0, System.currentTimeMillis()));

        });
    }


    @PostConstruct
    public void init() {
        this.loadTasksFromFile();

        logger.info("已从本地加载{}个灵田任务 {}个发言统计", this.ltmap.size(), this.MESSAGE_NUMBER_MAP.size());
    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 用户名获取qq(Bot bot, Group group, Member member, MessageChain chain, String msg, Integer msgId) {
        if (msg.contains("@3889001741") && (msg.contains("灵田收取") || msg.contains("灵田结算") || msg.contains("探索秘境") || msg.contains("道具使用次元之钥"))) {
            String userName = member.getCard().trim();
            Long userId = member.getUserId();
            Long groupId = group.getGroupId();
            ((Map) this.pendingLingTianRecords.computeIfAbsent(groupId + "", (k) -> {
                return new ConcurrentHashMap();
            })).put(userName, new PendingLingTianRecord(userName, userId, groupId));
        }

    }

    @Scheduled(
            fixedRate = 3600000L
    )
    public void autoSaveTasks() {
        this.saveTasksToFile();
        logger.debug("定时任务数据持久化完成");
    }

    @PreDestroy
    public void onShutdown() {
        this.saveTasksToFile();
        logger.info("程序关闭时持久化任务数据完成");
    }

    public synchronized void saveTasksToFile() {
//        try {
//            ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(Paths.get(FILE_PATH)));
//            Map<String, Object> data = new HashMap();
//            data.put("灵田", this.ltmap);
//            data.put("发言统计", MESSAGE_NUMBER_MAP);
//            data.put("自动购买", this.autoBuyProductMap);
//            data.put("炼金排除", this.excludeAlchemyMap);
//            data.put("上架排除", this.excludeSellMap);
//            oos.writeObject(data);
////            oos.writeChars(JSON.toJSONString(data));
//            oos.close();
//        } catch (Throwable var5) {
//            logger.error("任务数据保存失败：", var5);
//        }

        try {
            Map<String, Object> data = new HashMap<>();
            data.put("灵田", this.ltmap);
            data.put("发言统计", MESSAGE_NUMBER_MAP);
            data.put("自动购买", this.autoBuyProductMap);
            data.put("炼金排除", this.excludeAlchemyMap);
            data.put("上架排除", this.excludeSellMap);

            // 直接写入JSON字节（UTF-8编码）
            Files.write(Paths.get(FILE_PATH),
                    JSON.toJSONString(data).getBytes(StandardCharsets.UTF_8));

        } catch (Throwable e) {
            logger.error("任务数据保存失败：", e);
        }

        logger.info("正在保存 {} 个灵田任务 {}个发言统计", this.ltmap.size(), MESSAGE_NUMBER_MAP.size());
    }

    public synchronized void loadTasksFromFile() {
        try {
            // 读取JSON文件
            byte[] jsonBytes = Files.readAllBytes(Paths.get(FILE_PATH));
            String jsonStr = new String(jsonBytes, StandardCharsets.UTF_8);

            // 解析JSON（使用TypeReference处理复杂泛型）
            JSONObject data = JSON.parseObject(jsonStr);

            // 1. 处理灵田数据（Map<String, RemindTime>）
            this.ltmap = data.getObject("灵田",
                    new TypeReference<Map<String, RemindTime>>() {
                    });

            // 2. 处理发言统计（Map<String, MessageNumber>）
            this.MESSAGE_NUMBER_MAP = data.getObject("发言统计",
                    new TypeReference<Map<String, MessageNumber>>() {
                    });

            // 3. 处理自动购买（嵌套Map）
            this.autoBuyProductMap = data.getObject("自动购买",
                    new TypeReference<Map<String, Map<String, ProductPrice>>>() {
                    });

            // 4. 处理排除列表
            this.excludeAlchemyMap = data.getObject("炼金排除",
                    new TypeReference<Map<String, Set<String>>>() {
                    });
            this.excludeSellMap = data.getObject("上架排除",
                    new TypeReference<Map<String, Set<String>>>() {
                    });

            logger.info("加载成功：{} 个灵田任务，{} 条发言统计",
                    ltmap.size(), MESSAGE_NUMBER_MAP.size());
        } catch (Throwable e) {
            logger.error("数据加载失败：", e);
            initDefaultData();
        }
    }

    private void initDefaultData() {
        this.ltmap = new ConcurrentHashMap<>();
        this.MESSAGE_NUMBER_MAP = new ConcurrentHashMap<>();
        this.autoBuyProductMap = new ConcurrentHashMap<>();
        this.excludeAlchemyMap = new ConcurrentHashMap<>();
        this.excludeSellMap = new ConcurrentHashMap<>();
    }

    public String getExcludeAlchemyList(Long botId) {
        Set<String> items = (Set) this.excludeAlchemyMap.getOrDefault(botId + "", ConcurrentHashMap.newKeySet());
        return items.isEmpty() ? "无炼金排除道具" : StringUtils.join(items, "\n");
    }

    public void modifyExcludeAlchemy(Long botId, String itemsStr, boolean isAdd) {
        Set<String> items = (Set) this.excludeAlchemyMap.computeIfAbsent(botId + "", (k) -> {
            return ConcurrentHashMap.newKeySet();
        });
        Arrays.stream(itemsStr.split("[&＆]")).map(String::trim).filter(StringUtils::isNotBlank).forEach((item) -> {
            if (isAdd) {
                items.add(item);
            } else {
                items.remove(item);
            }

        });
        this.saveTasksToFile();
    }

    public String getExcludeSellList(Long botId) {
        Set<String> items = (Set) this.excludeSellMap.getOrDefault(botId + "", ConcurrentHashMap.newKeySet());
        return items.isEmpty() ? "无上架排除道具" : StringUtils.join(items, "\n");
    }

    public void modifyExcludeSell(Long botId, String itemsStr, boolean isAdd) {
        Set<String> items = (Set) this.excludeSellMap.computeIfAbsent(botId + "", (k) -> {
            return ConcurrentHashMap.newKeySet();
        });
        Arrays.stream(itemsStr.split("[&＆]")).map(String::trim).filter(StringUtils::isNotBlank).forEach((item) -> {
            if (isAdd) {
                items.add(item);
            } else {
                items.remove(item);
            }

        });
        this.saveTasksToFile();
    }

    public boolean isAlchemyExcluded(Long botId, String itemName) {
        return ((Set) this.excludeAlchemyMap.getOrDefault(botId + "", Collections.emptySet())).contains(itemName);
    }

    public boolean isSellExcluded(Long botId, String itemName) {
        return ((Set) this.excludeSellMap.getOrDefault(botId + "", Collections.emptySet())).contains(itemName);
    }

//    private synchronized void loadTasksFromFile() {
//        File dataFile = new File(FILE_PATH);
//        if (dataFile.exists()) {
//            try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(dataFile.toPath()))) {
//                Map<String, Object> data = (Map)ois.readObject();
//
//                // 安全初始化 map
//                this.ltmap = data.containsKey("灵田") ?
//                        (ConcurrentHashMap<String, RemindTime>)data.get("灵田") :
//                        new ConcurrentHashMap<>();
//
//                this.MESSAGE_NUMBER_MAP = data.containsKey("发言统计") ?
//                        (ConcurrentHashMap<Long, MessageNumber>)data.get("发言统计") :
//                        new ConcurrentHashMap<>();
//
//                this.excludeAlchemyMap = data.containsKey("炼金排除") ?
//                        (ConcurrentHashMap<Long, Set<String>>)data.get("炼金排除") :
//                        new ConcurrentHashMap<>();
//
//                this.excludeSellMap = data.containsKey("上架排除") ?
//                        (ConcurrentHashMap<Long, Set<String>>)data.get("上架排除") :
//                        new ConcurrentHashMap<>();
//            } catch (Exception e) {
//                logger.error("任务数据加载失败，初始化空map", e);
//                this.ltmap = new ConcurrentHashMap<>();
//                this.MESSAGE_NUMBER_MAP = new ConcurrentHashMap<>();
//            }
//        } else {
//            logger.warn("未找到序列化文件，初始化空map");
//            this.ltmap = new ConcurrentHashMap<>();
//            this.MESSAGE_NUMBER_MAP = new ConcurrentHashMap<>();
//        }
//    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 群管设置(final Bot bot, final Group group, final Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isControlQQ = false;
        if (StringUtils.isNotBlank(bot.getBotConfig().getControlQQ())) {
            isControlQQ = ("&" + bot.getBotConfig().getControlQQ() + "&").contains("&" + member.getUserId() + "&");
        } else {
            isControlQQ = bot.getBotConfig().getMasterQQ() == member.getUserId();
        }
        if (bot.getBotConfig().isEnableSelfTitle() && message.contains("我要头衔")) {
            final String specialTitle = message.substring(message.indexOf("我要头衔") + 4).trim();
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        bot.setGroupSpecialTitle(member.getUserId(), specialTitle, 0, group.getGroupId());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
            });
        }
        if (isControlQQ && (message.contains("上管") || message.contains("下管"))) {
            try {
                Pattern pattern = Pattern.compile("(上管|下管)[@]?(\\d{5,12})");
                Matcher matcher = pattern.matcher(message);

                while (matcher.find()) {
                    String type = matcher.group(1); // "上管"或"下管"
                    String qqNumber = matcher.group(2); // QQ号
                    customPool.submit(new Runnable() {
                        public void run() {
                            bot.setGroupAdmin(Long.parseLong(qqNumber), group.getGroupId(), type.equals("上管"));
                            group.sendMessage((new MessageChain()).reply(messageId).text("操作成功"));
                        }
                    });

                }
            } catch (Exception e) {
                group.sendMessage((new MessageChain()).text("管理员设置失败，请注意格式：@我+上管/下管+QQ号"));
                e.printStackTrace();
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 修改群昵称(final Bot bot, final Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isAtSelf = message.contains("" + bot.getBotId());
        if (group.getGroupId() == 682220759L && isAtSelf && message.contains("道友今天双修次数已经到达上限")) {
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        bot.setGroupCard(group.getGroupId(), bot.getBotId(), "A无偿双修(剩余0次)");
                    } catch (Exception var2) {
                    }

                }
            });
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void 统计群聊发言次数(final Bot bot, final Group group, Member member, MessageChain messageChain, String message, Integer messageId) {


        if (group != null && group.getGroupId() > 0) {
            // 确保Map存在
            if (MESSAGE_NUMBER_MAP == null) {
                MESSAGE_NUMBER_MAP = new ConcurrentHashMap<>();
            }
            MessageNumber messageNumber = MESSAGE_NUMBER_MAP.get(bot.getBotId() + "");
            if (messageNumber == null) {
                messageNumber = new MessageNumber();
                messageNumber.setNumber(1);
                messageNumber.setTime(System.currentTimeMillis());
            } else {
                if (messageNumber.isCrossResetTime()) {
                    messageNumber.setNumber(1);
                    messageNumber.setTime(System.currentTimeMillis());
                } else {
                    messageNumber.setNumber(messageNumber.getNumber() + 1);
                    messageNumber.setTime(System.currentTimeMillis());
                }

            }
//            if ((danCalculator!=null && danCalculator.config!=null && danCalculator.config.getAlchemyQQ() == bot.getBotId()) &&
//                    (messageNumber.getNumber() == 10 || messageNumber.getNumber() % 100 == 0 ) ) {
//                bot.setGroupCard(bot.getBotConfig().getGroupId(), bot.getBotId(), bot.getBotName()+"(发言次数:"+messageNumber.getNumber()+")");
//            }
            MESSAGE_NUMBER_MAP.put(bot.getBotId() + "", messageNumber);
        }
        message = message.trim();
        if (message.equals("发言统计")) {
            MessageNumber messageNumber = MESSAGE_NUMBER_MAP.get(bot.getBotId() + "");
            if (messageNumber != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("今日发言次数：").append(messageNumber.getNumber()).append("\n");
                sb.append("最新更新时间：").append(sdf.format(new Date(messageNumber.getTime())));
                group.sendMessage(new MessageChain().reply(messageId).text(sb.toString()));
            }

        }

    }

    @Scheduled(
            cron = "0 0 4 * * *"
    )
    public void 重置双修次数() throws Exception {
        BotFactory.getBots().values().forEach((bot) -> {
            if (bot.getBotId() != bot.getBotConfig().getMasterQQ()) {
                bot.setGroupCard(682220759L, bot.getBotId(), "A无偿双修");
            }

        });
    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 秘境结算提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        message = processReplyMessage(messageChain);
        if (bot.getBotConfig().isEnableGroupManager() ) {

            if (isRemindGroup(bot, group)) {
                if (message.contains("进行中的：") && message.contains("可结束") && message.contains("探索")) {
                    this.extractInfo(message, "秘境", group, bot,member);
                } else if (message.contains("进入秘境") && message.contains("探索需要花费")) {
                    this.extractInfo(message, "秘境", group, bot,member);
                } else if (message.contains("秘境") && message.contains("道友已") && message.contains("分钟")) {
                    this.handleNewsExploration(message, group, bot);
                } else if (message.contains("秘境") && message.contains("时轮压缩") && message.contains("分钟")) {
                    this.handleNewsExploration(message, group, bot);
                }
            }


        }

    }

    public boolean isRemindGroup(Bot bot, Group group) {
        boolean isGroupQQ = true;
        if (StringUtils.isNotBlank(bot.getBotConfig().getGroupQQ())) {
            isGroupQQ = ("&" + bot.getBotConfig().getGroupQQ() + "&").contains("&" + group.getGroupId() + "&");
        }

        return isGroupQQ;
    }

    private void handleNewsExploration(String msg, Group group, Bot bot) {
        Map<String, PendingLingTianRecord> groupRecords = (Map) this.pendingLingTianRecords.get(group.getGroupId() + "");
        if (groupRecords != null && !groupRecords.isEmpty()) {
            Optional<Map.Entry<String, PendingLingTianRecord>> matchedEntry = groupRecords.entrySet().stream().filter((entryx) -> {
                return msg.contains((CharSequence) entryx.getKey());
            }).findFirst();
            if (matchedEntry.isPresent()) {
                Map.Entry<String, PendingLingTianRecord> entry = (Map.Entry) matchedEntry.get();
                PendingLingTianRecord record = (PendingLingTianRecord) entry.getValue();

                try {
                    Pattern pattern = Pattern.compile("⏳\\s*[^:]*：\\s*(\\d+\\.?\\d*)\\s*(分钟|小时)");
                    Matcher matcher = pattern.matcher(msg);
                    if (matcher.find()) {
                        String minutes = matcher.group(1);
                        addMjXslMap(record.userId.toString(), "秘境", group, minutes, bot);
//                        this.updateAutoReplyMap(record.userId.toString(), (long)(minutes * 60000.0), group.getGroupId(), bot.getBotId());
                        logger.info("秘境提醒 - 用户[{}:{}]", record.userName, record.userId);
                    }
                } finally {
                    groupRecords.remove(record.userName);
                    if (groupRecords.isEmpty()) {
                        this.pendingLingTianRecords.remove(group.getGroupId() + "");
                    }

                }
            }
        }

    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 悬赏令结算提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        message = processReplyMessage(messageChain);
        if (bot.getBotConfig().isEnableGroupManager() && !remindGroupIdList.contains(group.getGroupId())
                && message.contains("进行中的悬赏令") && message.contains("可结束")) {

            if (isRemindGroup(bot, group)) {
                this.extractInfo(message, "悬赏", group, bot,member);
            }

        }

    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 新版悬赏令结算提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        message = processReplyMessage(messageChain);
        if (bot.getBotConfig().isEnableGroupManager()
                && (message.contains("悬赏令进行中") || message.contains("悬赏令接取成功")) && message.contains("预计")) {
            if (isRemindGroup(bot, group)) {
                // 提取QQ号
//                Pattern qqPattern = Pattern.compile("@(\\d+)");
//                Matcher qqMatcher = qqPattern.matcher(message);
//                String qq = qqMatcher.find() ? qqMatcher.group(1) : "未找到QQ号";
                String qq = member.getUserId() + "";
                // 提取预计时间
                Pattern timePattern = null;
                if (message.contains("预计时间")) {
                    timePattern = Pattern.compile("预计时间：(\\d+\\.?\\d*)分钟");
                }
                if (message.contains("预计剩余时间")) {
                    timePattern = Pattern.compile("预计剩余时间：(\\d+\\.?\\d*)分钟");
                }
                if (timePattern != null) {
                    Matcher timeMatcher = timePattern.matcher(message);
                    String time = timeMatcher.find() ? timeMatcher.group(1) : "未找到预计时间";
                    addMjXslMap(qq, "悬赏", group, time, bot);
                }
            }


//            this.extractInfo(message, "悬赏", group);
        }

    }

    // 处理回复消息
    private String processReplyMessage(MessageChain messageChain) {
        List<ReplyMessage> replyMessageList =  messageChain.getMessageByType(ReplyMessage.class);
        if(!replyMessageList.isEmpty()){
            ReplyMessage replyMessage = messageChain.getMessageByType(ReplyMessage.class).get(0);
            MessageChain replyMessageChain = replyMessage.getChain();

            if (replyMessageChain != null) {
                List<TextMessage> textMessageList = replyMessageChain.getMessageByType(TextMessage.class);
                if (textMessageList != null && !textMessageList.isEmpty()) {
                    return textMessageList.get(textMessageList.size() - 1).getText();
                }
            }
        }

        return "";
    }

    public void extractInfo(String input, String type, Group group, Bot bot,Member member) {
        String qqPattern = "@(\\d+)";
        String timePattern = "(\\d+\\.?\\d*)(?:\\(原\\d+\\.?\\d*\\))?(?:分钟|分钟后)";
        Pattern qqRegex = Pattern.compile(qqPattern);
        Pattern timeRegex = Pattern.compile(timePattern);
//        Matcher qqMatcher = qqRegex.matcher(input);
//        String qq = "";
//        if (qqMatcher.find()) {
//            qq = qqMatcher.group(1);
//        } else {
//            logger.warn("未找到QQ号");
//        }
        String qq = member.getUserId() + "";
        Matcher timeMatcher = timeRegex.matcher(input);
        String time = "";
        if (timeMatcher.find()) {
            time = timeMatcher.group(1);
        } else {
            logger.warn("未找到时间");
        }
        addMjXslMap(qq, type, group, time, bot);

    }

    private void addMjXslMap(String qq, String type, Group group, String time, Bot bot) {
        if (StringUtils.isNotBlank(qq) && StringUtils.isNotBlank(time)) {
            RemindTime remindTime = new RemindTime();
            remindTime.setQq(Long.parseLong(qq));
            long expireTime = (long) (Double.parseDouble(time) * 60.0 * 1000.0 + (double) System.currentTimeMillis());
            remindTime.setExpireTime(expireTime);
            remindTime.setText(type);
            remindTime.setGroupId(group.getGroupId());
            remindTime.setRemindQq(bot.getBotId());
            this.mjXslmap.put(qq, remindTime);
            group.sendMessage(new MessageChain().at(qq).text("收到结算提醒，将在" + sdf.format(expireTime) + "提醒你结算任务"));
        }
    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 灵田领取提醒(Bot bot, Group group, Member member, MessageChain messageChain, String msg, Integer messageId) throws InterruptedException {
        msg = processReplyMessage(messageChain);
        if (bot.getBotConfig().isEnableGroupManager()) {
            if (isRemindGroup(bot, group)) {
                if (msg.contains("灵田还不能收取") && msg.contains("下次收取时间为")) {
                    this.handleLingTianMessage(msg, group, bot,member);
                } else if (msg.contains("收获药材") && !msg.contains("道友的洞天福地") && (msg.contains("道友成功") || msg.contains("道友本次采集"))) {
//                    this.handleFormat2(msg, group, bot, messageId);
                    this.updateLingTianTimer(member.getUserId()+"", "47.0", group, bot.getBotId());
                } else if (msg.contains("道友的灵田灵气未满，尚需孕育") && msg.contains("下次收成时间")) {
                    Pattern pattern = Pattern.compile("下次收成时间：(\\d+\\.\\d+)小时");
                    Matcher matcher = pattern.matcher(msg);
                    if (matcher.find()) {
                        String hours = matcher.group(1);
                        this.updateLingTianTimer(member.getUserId()+"", hours, group, bot.getBotId());
                    }
//                    this.handleFormat3(msg, group, bot);
                }
            }

        }

    }


    private void handleFormat3(String msg, Group group, Bot bot) {
        Map<String, PendingLingTianRecord> groupRecords = (Map) this.pendingLingTianRecords.get(group.getGroupId() + "");
        if (groupRecords != null && !groupRecords.isEmpty()) {
            Optional<Map.Entry<String, PendingLingTianRecord>> matchedEntry = groupRecords.entrySet().stream().filter((entryx) -> {
                return msg.contains((CharSequence) entryx.getKey());
            }).findFirst();
            if (matchedEntry.isPresent()) {
                Map.Entry<String, PendingLingTianRecord> entry = (Map.Entry) matchedEntry.get();
                PendingLingTianRecord record = (PendingLingTianRecord) entry.getValue();

                try {
                    Pattern pattern = Pattern.compile("下次收成时间：(\\d+\\.\\d+)小时");
                    Matcher matcher = pattern.matcher(msg);
                    if (matcher.find()) {
                        String hours = matcher.group(1);
                        this.updateLingTianTimer(record.userId.toString(), hours, group, bot.getBotId());
                        logger.info("收到灵田提醒 - 用户[{}:{}]", record.userName, record.userId);
                    }
                } finally {
                    groupRecords.remove(record.userName);
                    if (groupRecords.isEmpty()) {
                        this.pendingLingTianRecords.remove(group.getGroupId() + "");
                    }

                }
            }
        }

    }

    private void handleFormat2(String msg, Group group, Bot bot, Integer msgId) {
        Pattern pattern = Pattern.compile("@(\\d+)");
        Matcher matcher = pattern.matcher(msg);

        customPool.submit(() -> {
            String qq = "";
            if (matcher.find()) {
                qq = matcher.group(1);
                if (qq.length() > 8 && qq.length() < 13) {
                    this.updateLingTianTimer(qq, "47.0", group, bot.getBotId());
                }
            } else {
                Map<String, PendingLingTianRecord> groupRecords = (Map) this.pendingLingTianRecords.get(group.getGroupId() + "");
                if (groupRecords == null || groupRecords.isEmpty()) {
                    return;
                }

                Optional<Map.Entry<String, PendingLingTianRecord>> matchedEntry = groupRecords.entrySet().stream().filter((entryxx) -> {
                    return msg.contains((CharSequence) entryxx.getKey());
                }).findFirst();
                if (matchedEntry.isPresent()) {
                    Map.Entry<String, PendingLingTianRecord> entry = (Map.Entry) matchedEntry.get();
                    PendingLingTianRecord record = (PendingLingTianRecord) entry.getValue();

                    try {
                        this.updateLingTianTimer(record.userId.toString(), "47.0", group, bot.getBotId());
                    } finally {
                        groupRecords.remove(record.userName);
                        if (groupRecords.isEmpty()) {
                            this.pendingLingTianRecords.remove(group.getGroupId() + "");
                        }

                    }
                }
            }

        });
    }


    private void handleLingTianMessage(String message, Group group, Bot bot,Member member) {
        Pattern pattern = Pattern.compile("@(\\d+).*?(\\d+\\.\\d+)小时", 32);
        Matcher matcher = pattern.matcher(message);
        String qqNumber =member.getUserId() + "";
        String time = "";
        if (matcher.find()) {
//            qqNumber = matcher.group(1);
            time = matcher.group(2);
        }
        updateLingTianTimer(qqNumber, time, group, bot.getBotId());

    }

    private void updateLingTianTimer(String qqNumber, String time, Group group, Long botId) {
        if (StringUtils.isNotBlank(qqNumber) && StringUtils.isNotBlank(time)) {
            RemindTime remindTime = new RemindTime();
            remindTime.setText("灵田");
            remindTime.setQq(Long.parseLong(qqNumber));
            remindTime.setExpireTime((long) (Double.parseDouble(time) * 60.0 * 60.0 * 1000.0 + (double) System.currentTimeMillis()));
            remindTime.setGroupId(group.getGroupId());
            remindTime.setRemindQq(botId);
            this.ltmap.put(qqNumber, remindTime);
            group.sendMessage((new MessageChain()).at(qqNumber).text("道友下次灵田收取时间：" + sdf.format(new Date(remindTime.getExpireTime()))));
        }
    }

    @Scheduled(
            fixedDelay = 30000L,
            initialDelay = 3000L
    )
    public void 结算() {
        this.checkAndNotify(this.mjXslmap, "悬赏", "秘境");
        this.checkAndNotify(this.ltmap, "灵田", "灵田");
    }

    private void checkAndNotify(Map<String, RemindTime> map, String taskType1, String taskType2) {
        Iterator<Map.Entry<String, RemindTime>> iterator = map.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, RemindTime> entry = (Map.Entry) iterator.next();
            RemindTime remindTime = (RemindTime) entry.getValue();
            if (remindTime.getExpireTime() > 0L && System.currentTimeMillis() >= remindTime.getExpireTime()) {
                if (System.currentTimeMillis() - remindTime.getExpireTime() < 1000L * 60 * 30) {
                    Bot bot = BotFactory.getBots().get(remindTime.getRemindQq());
                    if (bot != null) {
                        switch (remindTime.getText()) {
                            case "悬赏":
                                bot.getGroup(remindTime.getGroupId()).sendMessage((new MessageChain())
                                        .at(remindTime.getQq() + "").text(XSL_TEXT_LIST.get(new Random().nextInt(XSL_TEXT_LIST.size()))));
                                break;
                            case "秘境":
                                bot.getGroup(remindTime.getGroupId()).sendMessage((new MessageChain())
                                        .at(remindTime.getQq() + "").text(MJ_TEXT_LIST.get(new Random().nextInt(MJ_TEXT_LIST.size()))));
                                break;
                            case "灵田":
                                bot.getGroup(remindTime.getGroupId()).sendMessage((new MessageChain())
                                        .at(remindTime.getQq() + "").text(LT_TEXT_LIST.get(new Random().nextInt(LT_TEXT_LIST.size()))));
                                break;
                        }

                    }
                }

                iterator.remove();

            }
        }


    }

    private static class PendingLingTianRecord {
        String userName;
        Long userId;
        Long groupId;
        long timestamp;

        PendingLingTianRecord(String userName, Long userId, Long groupId) {
            this.userName = userName;
            this.userId = userId;
            this.groupId = groupId;
            this.timestamp = System.currentTimeMillis();
        }
    }
}

