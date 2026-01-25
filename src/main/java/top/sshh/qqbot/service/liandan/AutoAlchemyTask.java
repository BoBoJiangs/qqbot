package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Buttons;
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
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.MessageNumber;
import top.sshh.qqbot.service.GroupManager;

import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static top.sshh.qqbot.constant.Constant.targetDir;

@Component
public class AutoAlchemyTask {
    private static final Logger log = LoggerFactory.getLogger(AutoAlchemyTask.class);
    private static final String DAN_LU = "丹炉寒铁铸心炉";

    // 按 botId 隔离：药材消息缓存（用于 parseHerbList）
    private final Map<Long, List<String>> medicinalListMap = new ConcurrentHashMap<>();

    // 按 botId 隔离：当前页（原 public int page = 1）
    private final Map<Long, Integer> pageMap = new ConcurrentHashMap<>();

    // 按 botId 隔离：炼丹配方队列（原 alchemyList）
    private final Map<Long, CopyOnWriteArrayList<String>> alchemyListMap = new ConcurrentHashMap<>();

    // 与每个 botId 对应的 group（启动自动炼丹时保存，发送消息使用）
    private final Map<Long, Group> groupMap = new ConcurrentHashMap<>();

    // 不需要按 bot 隔离（全局）
//    public static volatile boolean isCreateDan = true;

    // 保持原来的线程池规模
    private static final ForkJoinPool customPool = new ForkJoinPool(20);

    // 控制并发匹配，和原来一样为全局
//    public static final AtomicBoolean MATCHING = new AtomicBoolean(false);
    public static final ReentrantLock matchingLock = new ReentrantLock();

    // 每个 bot 的文件锁，避免同 bot 并发读写文件冲突
    private final Map<Long, ReentrantLock> botLockMap = new ConcurrentHashMap<>();

    @Autowired
    public DanCalculator danCalculator;

    @Autowired
    public GroupManager groupManager;

    public AutoAlchemyTask() {}

    // -------------------- 锁 / 状态 辅助 --------------------
    private ReentrantLock getBotLock(Long botId) {
        return botLockMap.computeIfAbsent(botId, id -> new ReentrantLock());
    }

    private void resetPram(Long botId) {
        medicinalListMap.put(botId, new ArrayList<>());
        pageMap.put(botId, 1);
        alchemyListMap.put(botId, new CopyOnWriteArrayList<>());
        groupMap.remove(botId);
    }

    private List<String> getMedicinalList(Long botId) {
        return medicinalListMap.computeIfAbsent(botId, k -> new ArrayList<>());
    }

    private CopyOnWriteArrayList<String> getAlchemyList(Long botId) {
        return alchemyListMap.computeIfAbsent(botId, k -> new CopyOnWriteArrayList<>());
    }

    private int getPage(Long botId) {
        return pageMap.getOrDefault(botId, 1);
    }

    private void setPage(Long botId, int p) {
        pageMap.put(botId, p);
    }

    // -------------------- 消息处理入口（大函数，保持原逻辑） --------------------
    @GroupMessageHandler(ignoreItself = IgnoreItselfEnum.ONLY_ITSELF)
    public void enableScheduled(final Bot bot, final Group group, Member member, MessageChain messageChain, String message, Integer messageId)
            throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        Config config = danCalculator.getConfig(bot.getBotId());
        if ((StringUtils.isEmpty(message) || !botConfig.isEnableAlchemy()) && config == null) {
            return;
        }

        long botId = bot.getBotId();

        if ("炼丹命令".equals(message)) {
            group.sendMessage((new MessageChain()).reply(messageId).text(this.showReplyMessage(message, config)));
        }

        if ("炼丹设置".equals(message)) {
            group.sendMessage((new MessageChain()).reply(messageId).text(this.showReplyMessage(message, config)));
        }

        if ("设置炼丹指定丹药".startsWith(message)) {
            danCalculator.saveConfig(config, botId);
        }

        if ("开始自动炼丹".equals(message)) {
            resetPram(botId);
            groupMap.put(botId, group);
            if (botConfig.getCultivationMode() == 1) {
                botConfig.setStartScheduled(false);
            }
            botConfig.setStartAuto(true);
            botConfig.setAutoBuyHerbsMode(0);
            customPool.submit(() -> {
                try {
                    clearFile(botId + "/背包药材.txt", botId);
                    group.sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
                } catch (Exception e) {
                    log.error("开始自动炼丹任务提交失败, botId=" + botId, e);
                }
            });
        }

        if ("停止自动炼丹".equals(message)) {
            resetPram(botId);
            botConfig.setStartAuto(false);
            group.sendMessage((new MessageChain()).text("已停止自动炼丹"));
        }

        if ("未匹配到丹方，请检查丹方设置".equals(message)) {
            resetPram(botId);
            botConfig.setStartAuto(false);
        }

        if (message.equals("查询炼丹配方")) {
            customPool.submit(() -> {
                try {
                    File dataFile = new File(botId + "/炼丹配方.txt");
                    if (dataFile.exists()) {
                        bot.uploadGroupFile(group.getGroupId(), dataFile.getAbsolutePath(), "炼丹配方.txt", "");
                    } else {
                        System.err.println("文件不存在！");
                    }
                } catch (Exception e) {
                    System.err.println("上传文件异常");
                }
            });
        }

        if (message.equals("查询药材价格")) {
            customPool.submit(() -> {
                try {
                    File dataFile = new File(botId + "/药材价格.txt");
                    bot.uploadGroupFile(group.getGroupId(), dataFile.getAbsolutePath(), "药材价格.txt", "");
                } catch (Exception e) {
                    System.err.println("上传文件异常");
                }
            });
        }

        if (message.equals("添加成功,开始同步炼丹配方")) {
            customPool.submit(() -> {
                // 尝试获取锁，非阻塞方式
                if (!matchingLock.tryLock()) {
                    group.sendMessage((new MessageChain()).text("正在匹配丹方，请稍后操作！"));
                    return;
                }

                try {
                    danCalculator.loadData(botId);
                    danCalculator.calculateAllDans(botId);
                    group.sendMessage((new MessageChain()).text("已同步炼丹配方！"));
                } catch (Exception e) {
                    group.sendMessage((new MessageChain()).text("同步失败：" + e.getMessage()));
                } finally {
                    matchingLock.unlock();
                }
            });
        }

        if (message.startsWith("查丹方")) {
            customPool.submit(() -> {
                try {
                    danCalculator.parseRecipes(message, group, bot);
                } catch (Exception e) {
                    System.out.println("加载基础数据异常");
                }
            });
        }

        if (message.startsWith("更新炼丹配置")) {
            Pattern pattern = Pattern.compile("是否是炼金丹药：(是|否).*?炼金丹期望收益：(-?\\d+).*?坊市丹期望收益：(\\d+).*?丹药数量：(\\d+).*?坊市丹名称：([^\\n]+).*?炼丹QQ号码：(\\d+).*?开启全自动炼丹：(是|否).*?背包药材数量限制：(\\d+).*?降低采购药材价格：(\\d+)", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                if ((config.isAlchemy() != "是".equals(matcher.group(1))) ||
                        (config.getAlchemyNumber() != Integer.parseInt(matcher.group(2))) ||
                        (config.getMakeNumber() != Integer.parseInt(matcher.group(3))) ||
                        (config.getDanNumber() != Integer.parseInt(matcher.group(4))) ||
                        (!config.getMakeName().equals(matcher.group(5))) ||
                        (config.getAlchemyQQ() != (Long.parseLong(matcher.group(6))))) {

                    customPool.submit(() -> {

                        // 尝试获取锁，非阻塞方式
                        if (!matchingLock.tryLock()) {
                            group.sendMessage((new MessageChain()).text("正在匹配丹方，请稍后操作！"));
                            return;
                        }

                        try {
                            setConfig(matcher, config);
                            danCalculator.saveConfig(config, botId);
                            group.sendMessage((new MessageChain()).text("丹方配置已更新，正在重新匹配丹方！"));
                            danCalculator.loadData(botId);
                            danCalculator.calculateAllDans(botId);
                            group.sendMessage((new MessageChain()).text("丹方匹配成功！"));
                            danCalculator.addAutoBuyHerbs(botId);
                        } catch (Exception e) {
                            group.sendMessage((new MessageChain()).text("同步失败：" + e.getMessage()));
                        } finally {
                            matchingLock.unlock();
                        }
                    });
                } else {
                    setConfig(matcher, config);
                    danCalculator.saveConfig(config, botId);
                    group.sendMessage((new MessageChain()).text("配置已更新！"));
                }
            } else {
                String alchemyConfig = "\n更新炼丹配置\n是否是炼金丹药：" + (config.isAlchemy() ? "是" : "否") + "\n炼金丹期望收益：" + config.getAlchemyNumber() + "\n坊市丹期望收益：" + config.getMakeNumber() + "\n丹药数量：" + config.getDanNumber() + "\n坊市丹名称：" + config.getMakeName() + "\n炼丹QQ号码：" + config.getAlchemyQQ() + "\n开启全自动炼丹：" + (config.isFinishAutoBuyHerb() ? "是" : "否") + "\n背包药材数量限制：" + config.getLimitHerbsCount() + "\n降低采购药材价格：" + config.getAddPrice();
                group.sendMessage((new MessageChain()).reply(messageId).text("输入格式不正确！示例：" + alchemyConfig));
            }
        }

        // 原有的发言统计更新（保持不变）
        try {
            if (group != null && group.getGroupId() > 0 && groupManager != null && groupManager.MESSAGE_NUMBER_MAP != null) {
                MessageNumber messageNumber = groupManager.MESSAGE_NUMBER_MAP.get(bot.getBotId() + "");
                if ((danCalculator != null && config != null && config.getAlchemyQQ() == bot.getBotId()) &&
                        (messageNumber.getNumber() == 10 || messageNumber.getNumber() % 100 == 0)) {
                    bot.setGroupCard(bot.getBotConfig().getGroupId(), bot.getBotId(), bot.getBotName() + "(发言次数:" + messageNumber.getNumber() + ")");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setConfig(Matcher matcher, Config config) {
        config.setAlchemy("是".equals(matcher.group(1)));
        config.setAlchemyNumber(Integer.parseInt(matcher.group(2)));
        config.setMakeNumber(Integer.parseInt(matcher.group(3)));
        config.setDanNumber(Integer.parseInt(matcher.group(4)));
        config.setMakeName(matcher.group(5));
        config.setAlchemyQQ(Long.parseLong(matcher.group(6)));
        config.setFinishAutoBuyHerb("是".equals(matcher.group(7)));
        config.setLimitHerbsCount(Integer.parseInt(matcher.group(8)));
        config.setAddPrice(Integer.parseInt(matcher.group(9)));
    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 查丹方(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (message.contains("查丹方")) {
            final String string = message.substring(message.indexOf("查丹方")).trim();
            customPool.submit(() -> {
                try {
                    danCalculator.parseRecipes(string, group, bot);
                } catch (Exception var2) {
                    System.out.println("加载基础数据异常");
                }
            });
        }
    }

    private String showReplyMessage(String message, Config config) {
        StringBuilder sb = new StringBuilder();
        if (message.equals("炼丹命令")) {
            sb.append("－－－－－功能设置－－－－－\n");
            sb.append("取消采购药材××\n");
            sb.append("批量取消采购药材\n");
            sb.append("查询采购药材\n");
            sb.append("采购药材×× ××\n");
            sb.append("开始/停止自动炼丹\n");
            sb.append("查询炼丹配方\n");
            sb.append("查询药材价格\n");
            sb.append("更新炼丹配置××\n");
            sb.append("发言统计\n");
            sb.append("清空发言统计\n");
            sb.append("同步发言统计\n");
            sb.append("刷新指定药材坊市 ×&×&×\n");
            sb.append("取消刷新指定药材坊市\n");
            sb.append("批量修改性平价格 ××\n");
            return sb.toString();
        } else {
            if (message.equals("炼丹设置")) {
                String alchemyConfig = "是否是炼金丹药：" + (config.isAlchemy() ? "是" : "否") + "\n炼金丹期望收益：" + config.getAlchemyNumber() + "\n坊市丹期望收益：" + config.getMakeNumber() + "\n丹药数量：" + config.getDanNumber() + "\n坊市丹名称：" + config.getMakeName() + "\n炼丹QQ号码：" + config.getAlchemyQQ() + "\n开启全自动炼丹：" + (config.isFinishAutoBuyHerb() ? "是" : "否") + "\n背包药材数量限制：" + config.getLimitHerbsCount() + "\n降低采购药材价格：" + config.getAddPrice();
                sb.append("－－－－－当前设置－－－－－\n");
                sb.append(alchemyConfig);
            }

            return sb.toString();
        }
    }

    private void resetPram() {
        // 保留兼容方法（不带 botId），但尽量避免使用
    }

    // -------------------- 自动炼丹消息处理 --------------------
    @GroupMessageHandler(senderIds = {3889001741L})
    public void 自动炼丹(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId, Buttons buttons) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        long botId = bot.getBotId();

        if (isAtSelf(group, bot) && botConfig.isStartAuto()) {
            if (message.contains("请检查炼丹炉是否在背包中") || message.contains("验证码不正确") || message.contains("成功炼成丹药") || message.contains("药材是否在背包中")) {
                CopyOnWriteArrayList<String> alchemyList = getAlchemyList(botId);
                if (message.contains("验证码不正确") && !alchemyList.isEmpty()) {
                    this.autoAlchemy(botId);
                    return;
                }
                if (!alchemyList.isEmpty()) {
                    alchemyList.remove(0);
                }
                Config config = danCalculator.getConfig(botId);
                if (alchemyList.isEmpty()) {

                    if (config != null && config.isFinishAutoBuyHerb()) {
                        Group g = groupMap.get(botId);
                        if (g != null) g.sendMessage((new MessageChain()).text("确认一键丹药炼金"));
                    } else {
                        group.sendMessage((new MessageChain()).text("自动炼丹完成！！"));
                        botConfig.setStartAuto(false);
                        resetPram(botId);
                    }
                }
                this.autoAlchemy(botId);
            }
        }
    }

    private boolean isAtSelf(Group group, Bot bot) {
        return group.getGroupId() == bot.getBotConfig().getGroupId();
    }

    private void autoAlchemy(Long botId) {
        CopyOnWriteArrayList<String> alchemyList = getAlchemyList(botId);
        Group g = groupMap.get(botId);
        for (String remedy : alchemyList) {
            try {
                if (g != null) {
                    g.sendMessage((new MessageChain()).at("3889001741").text(remedy));
                }
                break;
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -------------------- 药材背包 处理（来自系统发送的药材背包消息） --------------------
    @GroupMessageHandler(senderIds = {3889001741L})
    public void 药材背包(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        long botId = bot.getBotId();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();

        if (isGroup && (message.contains("上一页") || message.contains("下一页") || message.contains("药材背包")) && botConfig.isStartAuto()) {
            List<TextMessage> textMessages = messageChain.getMessageByType(TextMessage.class);
            boolean hasNextPage = false;
            TextMessage textMessage = null;
            if (textMessages.size() > 1) {
                textMessage = textMessages.get(textMessages.size() - 1);
            } else if (!textMessages.isEmpty()) {
                textMessage = textMessages.get(0);
            }

            if (textMessage != null) {
                String msg = textMessage.getText();
                if (message.contains("炼金") && message.contains("坊市数据")) {
                    String[] lines = msg.split("\n");
                    List<String> list = getMedicinalList(botId);
                    list.addAll(Arrays.asList(lines));
                    if (msg.contains("下一页")) {
                        hasNextPage = true;
                    }
                    medicinalListMap.put(botId, list);
                }

                if (hasNextPage) {
                    int nextPage = getPage(botId) + 1;
                    setPage(botId, nextPage);
                    group.sendMessage((new MessageChain()).at("3889001741").text("药材背包" + nextPage));
                } else {
                    group.sendMessage((new MessageChain()).text("药材背包已刷新，开始匹配丹方..."));
                    parseHerbList(botId);
                }
            }
        }
    }

    // -------------------- 读取并解析 背包药材 -> 更新到背包文件 -> 启动配方匹配 --------------------
    public void parseHerbList(Long botId) throws Exception {
        List<String> medicinalList = getMedicinalList(botId);
        String currentHerb = null;

        for (String line : medicinalList) {
            line = line.trim();
            if (line.contains("名字：")) {
                currentHerb = line.replaceAll("名字：", "");
            } else if (currentHerb != null && line.contains("拥有数量:")) {
                try {
                    int count = Integer.parseInt(line.split("拥有数量:|炼金")[1]);
                    updateMedicine(currentHerb, count, botId);
                } catch (Exception e) {
                    // 忽略解析错误，继续
                }
                currentHerb = null;
            }
        }

        log.info("药材背包已更新, botId={}", botId);
        customPool.submit(() -> {
            try {
                buyHerbAndSmeltDan(botId);
            } catch (Exception e) {
                System.out.println("加载药材基础数据异常");
            }
        });
    }

    /** 解析并构造可炼丹的配方队列，扣减背包并开始自动炼丹 */
    private void buyHerbAndSmeltDan(Long botId) throws Exception {
        Map<String, List<String>> parseRecipes = this.parseRecipes(botId);
        boolean matchedAny = false;

        for (Map.Entry<String, List<String>> entry : parseRecipes.entrySet()) {
            List<String> recipeList = entry.getValue();
            if (recipeList == null || recipeList.isEmpty()) continue;

            for (int i = 0; i < recipeList.size(); i++) {
                String recipe = recipeList.get(i);
                Map<String, String> herbMap = this.getParseRecipeMap(recipe);

                String main = "";
                String lead = "";
                String assist = "";
                Map<String, Integer> totalNeed = new HashMap<>();

                for (Map.Entry<String, String> he : herbMap.entrySet()) {
                    String rolePlusName = he.getKey();          // e.g. 主药幻心草
                    String herbName = cleanHerbName(rolePlusName); // 幻心草

                    String[] parts = he.getValue().split("&");
                    int countRaw = 0;
                    try {
                        countRaw = Integer.parseInt(parts[0]);
                    } catch (Exception ignore) {}

                    int countAbs = Math.abs(countRaw);

                    if (rolePlusName.contains("主药")) main = rolePlusName + countRaw;
                    if (rolePlusName.contains("药引")) lead = rolePlusName + countRaw;
                    if (rolePlusName.contains("辅药")) assist = rolePlusName + countRaw;

                    totalNeed.merge(herbName, countAbs, Integer::sum);
                }

                boolean canSmelt = StringUtils.isNotBlank(main) && StringUtils.isNotBlank(lead) && StringUtils.isNotBlank(assist);

                if (canSmelt) {
                    for (Map.Entry<String, Integer> need : totalNeed.entrySet()) {
                        int lack = herbExistence(need.getKey(), need.getValue(), botId);
                        if (lack > 0) {
                            canSmelt = false;
                            break;
                        }
                    }
                }

                if (canSmelt) {
                    matchedAny = true;
                    int profit = parseProfit(recipe);
                    // 生成配方描述（保留原始数量）并加入炼丹队列
                    String formulaText = "配方" + main + lead + assist + DAN_LU + " == 炼金收益" + profit;
                    getAlchemyList(botId).add(formulaText);

                    // 扣减背包药材（按同名合计一次扣减）
                    for (Map.Entry<String, Integer> need : totalNeed.entrySet()) {
                        modifyHerbCount(need.getKey(), need.getValue(), botId);
                    }

                    // 尝试再次使用当前配方（可能剩余药材仍可再次配）
                    i--;
                }
            }
        }

        Group g = groupMap.get(botId);
        if (!matchedAny) {
            if (g != null) g.sendMessage(new MessageChain().text("未匹配到丹方，请检查丹方设置"));
            resetPram(botId);
        } else {
            if (g != null) g.sendMessage(new MessageChain().text("匹配到" + getAlchemyList(botId).size() + "个丹方，准备开始自动炼丹"));
            this.autoAlchemy(botId);
        }
    }

    /** 从丹方字符串里解析“炼金收益” */
    private int parseProfit(String recipe) {
        Pattern p = Pattern.compile("炼金收益(\\d+)");
        Matcher m = p.matcher(recipe);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    /** 去掉角色前缀，得到纯药材名 */
    private String cleanHerbName(String key) {
        return key.replace("主药", "").replace("药引", "").replace("辅药", "");
    }

    /** 解析丹方行，得到 map（例如：主药幻心草-1 => key=主药幻心草, value=-1&...） */
    private Map<String, String> getParseRecipeMap(String v) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] str = v.split(" ");
        for (String s : str) {
            if (s.contains("主药") || s.contains("药引") || s.contains("辅药")) {
                String[] myStrs = s.split("-");
                if (myStrs.length >= 2) {
                    map.put(myStrs[0], myStrs[1]);
                }
            }
        }
        return map;
    }

    // -------------------- 文件/背包相关（带锁） --------------------

    /** 返回背包中某药材的数量，找不到返回 -1 */
    public int getHerbCount(String name, Long botId) {
        ReentrantLock lock = getBotLock(botId);
        lock.lock();
        try (BufferedReader reader = new BufferedReader(new FileReader(botId + "/背包药材.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && parts[0].equals(name)) {
                    try {
                        return Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignore) {
                        return -1;
                    }
                }
            }
            return -1;
        } catch (IOException e) {
            return -1;
        } finally {
            lock.unlock();
        }
    }

    /** 修改药材数量：从文件中读取、扣减/更新、写回（线程安全） */
    public void modifyHerbCount(String name, int amount, Long botId) {
        ReentrantLock lock = getBotLock(botId);
        lock.lock();
        try {
            String filePath = botId + "/背包药材.txt";
            List<String> lines = new ArrayList<>();
            boolean found = false;

            // 读取
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2 && parts[0].equals(name)) {
                        found = true;
                        int newAmount = Integer.parseInt(parts[1]) - amount;
                        if (newAmount > 0) {
                            lines.add(parts[0] + " " + newAmount);
                        }
                        // 如果 newAmount <= 0 就不加入（相当于删除）
                    } else {
                        lines.add(line);
                    }
                }
            } catch (FileNotFoundException e) {
                // 文件不存在则后面会新建
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (!found && amount > 0) {
                // 原逻辑：未找到且需要扣减时，添加一条（保持原行为）
                lines.add(name + " " + amount);
            }

            // 写回
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, false))) {
                for (String outLine : lines) {
                    writer.write(outLine);
                    writer.newLine();
                }
                writer.flush();
            } catch (IOException e) {
                // 忽略写入失败
            }
        } finally {
            lock.unlock();
        }
    }

    /** 读取配方文件（botId/炼丹配方.txt） */
    public Map<String, List<String>> parseRecipes(Long botId) throws IOException {
        Map<String, List<String>> danRecipes = new LinkedHashMap<>();
        String path = botId + "/炼丹配方.txt";
        File file = new File(path);
        if (!file.exists()) return danRecipes;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String currentDan = null;
            List<String> currentRecipes = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    if (line.endsWith("配方")) {
                        if (currentDan != null) {
                            danRecipes.put(currentDan, currentRecipes);
                        }
                        currentDan = line.replace("配方", "").trim();
                        currentRecipes = new ArrayList<>();
                    } else if (currentDan != null) {
                        currentRecipes.add(line);
                    }
                }
            }
            if (currentDan != null) {
                danRecipes.put(currentDan, currentRecipes);
            }
        }
        return danRecipes;
    }

    /** 判断背包是否满足需求：若返回值 > 0 表示缺少数量 */
    private int herbExistence(String herb, int herbCount, Long botId) {
        int count = getHerbCount(herb, botId);
        return count == -1 ? herbCount : herbCount - count;
    }

    /** 将药材数量信息写入背包（覆盖或新增），线程安全 */
    public void updateMedicine(String name, int quantity, Long botId) {
        ReentrantLock lock = getBotLock(botId);
        lock.lock();
        try {
            String filePath = botId + "/背包药材.txt";
            List<String> lines = new ArrayList<>();
            boolean found = false;

            // 读取并替换
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 1 && parts[0].equals(name)) {
                        lines.add(name + " " + quantity);
                        found = true;
                    } else {
                        lines.add(line);
                    }
                }
            } catch (FileNotFoundException e) {
                // 文件不存在，后面会新建
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (!found) {
                lines.add(name + " " + quantity);
            }

            // 写回
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, false))) {
                for (String out : lines) {
                    writer.write(out);
                    writer.newLine();
                }
                writer.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } finally {
            lock.unlock();
        }
    }

    private static void clearFile(String filePath, Long botId) {
        // 按原逻辑直接覆盖为空文件（注意：此方法在上文 enableScheduled 中以非阻塞方式调用）
        try (FileWriter fw = new FileWriter(filePath, false)) {
            System.out.println("背包文件清空: " + filePath);
        } catch (Exception e) {
            System.out.println("背包文件清空错误: " + e.getMessage());
        }
    }
}
