//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.*;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.Config;
import top.sshh.qqbot.data.MessageNumber;
import top.sshh.qqbot.service.GroupManager;

import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static top.sshh.qqbot.constant.Constant.targetDir;

@Component
public class AutoAlchemyTask {
    private static final Logger log = LoggerFactory.getLogger(AutoAlchemyTask.class);
    //    public static final String targetDir = "C:\\Users\\Administrator\\Desktop\\修仙java脚本";
    private List<String> medicinalList = new ArrayList();
    public int page = 1;
    @Autowired
    public DanCalculator danCalculator;
    @Autowired
    public GroupManager groupManager;
    private static final ForkJoinPool customPool = new ForkJoinPool(20);
    private List<String> alchemyList = new CopyOnWriteArrayList();
    private Group group;
//    private Config config;
    public static boolean isCreateDan = true;
    private static final AtomicBoolean MATCHING = new AtomicBoolean(false);


    public AutoAlchemyTask() {
    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void enableScheduled(final Bot bot, final Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        Config config = danCalculator.getConfig(bot.getBotId());
        if ((StringUtils.isEmpty(message) || !botConfig.isEnableAlchemy()) && config == null) {
            return;
        }
        if ("炼丹命令".equals(message)) {
            group.sendMessage((new MessageChain()).reply(messageId).text(this.showReplyMessage(message,config)));
        }

        if ("炼丹设置".equals(message)) {
            group.sendMessage((new MessageChain()).reply(messageId).text(this.showReplyMessage(message,config)));
        }

        if ("设置炼丹指定丹药".startsWith(message)) {
            AutoAlchemyTask.this.danCalculator.saveConfig(config,bot.getBotId());
        }


        if ("开始自动炼丹".equals(message)) {
            this.group = group;
            this.resetPram();
            botConfig.setStartAuto(true);
            botConfig.setAutoBuyHerbsMode(0);
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        AutoAlchemyTask.clearFile(bot.getBotId() + "/背包药材.txt");
                        group.sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
            });
        }

        if ("停止自动炼丹".equals(message)) {
            this.resetPram();
            botConfig.setStartAuto(false);
            group.sendMessage((new MessageChain()).text("已停止自动炼丹"));
        }

        if ("未匹配到丹方，请检查丹方设置".equals(message)) {
            this.resetPram();
            botConfig.setStartAuto(false);
        }

        if (message.equals("查询炼丹配方")) {
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        try {
                            File dataFile = new File(bot.getBotId() + "/炼丹配方.txt");
                            if (dataFile.exists()) {
//
                                bot.uploadGroupFile(group.getGroupId(), dataFile.getAbsolutePath(), "炼丹配方.txt", "");
                            } else {
                                System.err.println("文件不存在！");
                            }
                        } catch (SecurityException e) {
                            System.err.println("权限不足：" + e.getMessage());
                        } catch (Exception e) {
                            System.err.println("其他错误：" + e.getMessage());
                        }

                    } catch (Exception var2) {
                        System.out.println("上传文件异常");
                    }

                }
            });
        }

        if (message.equals("查询药材价格")) {
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        File dataFile = new File(bot.getBotId() + "/药材价格.txt");
                        bot.uploadGroupFile(group.getGroupId(), dataFile.getAbsolutePath(), "药材价格.txt", "");
                    } catch (Exception var2) {
                        System.out.println("上传文件异常");
                    }

                }
            });
        }

        if (message.equals("添加成功,开始同步炼丹配方")) {
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        if (MATCHING.compareAndSet(false, true)) {
                            try {
                                danCalculator.loadData(bot.getBotId());
                                danCalculator.calculateAllDans(bot.getBotId());
                                group.sendMessage((new MessageChain()).text("已同步炼丹配方！"));
                            } finally {
                                MATCHING.set(false);
                            }
                        } else {
                            group.sendMessage((new MessageChain()).text("正在匹配丹方，请稍后操作！"));
                        }

                    } catch (Exception var2) {
                        MATCHING.set(false);
                    }

                }
            });

        }

        if (message.startsWith("查丹方")) {
//            final String string = message.substring(message.indexOf("查丹方") + 3).trim();
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        AutoAlchemyTask.this.danCalculator.parseRecipes(message, group, bot);
                    } catch (Exception var2) {
                        System.out.println("加载基础数据异常");
                    }

                }
            });
        }

        if (message.startsWith("更新炼丹配置")) {
            Pattern pattern = Pattern.compile("是否是炼金丹药：(true|false).*?炼金丹期望收益：(-?\\d+).*?坊市丹期望收益：(\\d+).*?丹药数量：(\\d+).*?坊市丹名称：([^\\n]+).*?炼丹QQ号码：(\\d+).*?开启全自动炼丹：(true|false).*?背包药材数量限制：(\\d+).*?降低采购药材价格：(\\d+)", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {


                if ((config.isAlchemy() != Boolean.parseBoolean(matcher.group(1))) ||
                        (config.getAlchemyNumber() != Integer.parseInt(matcher.group(2))) ||
                        (config.getMakeNumber() != Integer.parseInt(matcher.group(3))) ||
                        (config.getDanNumber() != Integer.parseInt(matcher.group(4))) ||
                        (!config.getMakeName().equals(matcher.group(5))) ||
                        (config.getAlchemyQQ() != (Long.parseLong(matcher.group(6))))) {
                    customPool.submit(new Runnable() {
                        public void run() {
                            try {
                                if (MATCHING.compareAndSet(false, true)) {
                                    try {
                                        setConfig(matcher,config);
                                        AutoAlchemyTask.this.danCalculator.saveConfig(config,bot.getBotId());
                                        group.sendMessage((new MessageChain()).text("丹方配置已更新，正在重新匹配丹方！"));
                                        AutoAlchemyTask.this.danCalculator.loadData(bot.getBotId());
                                        AutoAlchemyTask.this.danCalculator.calculateAllDans(bot.getBotId());
                                        group.sendMessage((new MessageChain()).text("丹方匹配成功！"));
                                        AutoAlchemyTask.this.danCalculator.addAutoBuyHerbs(bot.getBotId());
                                    } finally {
                                        MATCHING.set(false);
                                    }
                                } else {
                                    group.sendMessage((new MessageChain()).text("正在匹配丹方，请稍后操作！"));
                                }

                            } catch (Exception e) {
                                MATCHING.set(false);
                                group.sendMessage((new MessageChain()).text("配置更新失败！！！"));
                            }

                        }
                    });
                } else {
                    setConfig(matcher,config);
                    AutoAlchemyTask.this.danCalculator.saveConfig(config,bot.getBotId());
                    group.sendMessage((new MessageChain()).text("配置已更新！"));
                }

            } else {
                String alchemyConfig = "\n更新炼丹配置\n是否是炼金丹药：" + config.isAlchemy() + "\n炼金丹期望收益：" + config.getAlchemyNumber() + "\n坊市丹期望收益：" + config.getMakeNumber() + "\n丹药数量：" + config.getDanNumber() + "\n坊市丹名称：" + config.getMakeName() + "\n炼丹QQ号码：" + config.getAlchemyQQ() + "\n开启全自动炼丹：" + config.isFinishAutoBuyHerb() + "\n背包药材数量限制：" + config.getLimitHerbsCount() + "\n降低采购药材价格：" + config.getAddPrice();
                group.sendMessage((new MessageChain()).reply(messageId).text("输入格式不正确！示例：" + alchemyConfig));
            }
        }
        try {
            if (group != null && group.getGroupId() > 0 && groupManager != null && groupManager.MESSAGE_NUMBER_MAP != null) {
                MessageNumber messageNumber = groupManager.MESSAGE_NUMBER_MAP.get(bot.getBotId()+"");
                if ((danCalculator != null && config != null && config.getAlchemyQQ() == bot.getBotId()) &&
                        (messageNumber.getNumber() == 10 || messageNumber.getNumber() % 100 == 0)) {
                    bot.setGroupCard(bot.getBotConfig().getGroupId(), bot.getBotId(), bot.getBotName() + "(发言次数:" + messageNumber.getNumber() + ")");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setConfig(Matcher matcher,Config config) {
        config.setAlchemy(Boolean.parseBoolean(matcher.group(1)));
        config.setAlchemyNumber(Integer.parseInt(matcher.group(2)));
        config.setMakeNumber(Integer.parseInt(matcher.group(3)));
        config.setDanNumber(Integer.parseInt(matcher.group(4)));
        config.setMakeName(matcher.group(5));
        config.setAlchemyQQ(Long.parseLong(matcher.group(6)));
        config.setFinishAutoBuyHerb(Boolean.parseBoolean(matcher.group(7)));
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
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        AutoAlchemyTask.this.danCalculator.parseRecipes(string, group, bot);
                    } catch (Exception var2) {
                        System.out.println("加载基础数据异常");
                    }

                }
            });
        }

    }

    private String showReplyMessage(String message,Config config) {
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
                String alchemyConfig = "是否是炼金丹药：" + config.isAlchemy() + "\n炼金丹期望收益：" + config.getAlchemyNumber() + "\n坊市丹期望收益：" + config.getMakeNumber() + "\n丹药数量：" + config.getDanNumber() + "\n坊市丹名称：" + config.getMakeName() + "\n炼丹QQ号码：" + config.getAlchemyQQ() + "\n开启全自动炼丹：" + config.isFinishAutoBuyHerb() + "\n背包药材数量限制：" + config.getLimitHerbsCount() + "\n降低采购药材价格：" + config.getAddPrice();
                sb.append("－－－－－当前设置－－－－－\n");
                sb.append(alchemyConfig);
            }

            return sb.toString();
        }
    }

    private void resetPram() {
        this.medicinalList.clear();
        this.page = 1;
        this.alchemyList.clear();
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 自动炼丹(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId, Buttons buttons) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();

        if (isAtSelf(group,bot) && botConfig.isStartAuto()) {
            if(message.contains("请检查炼丹炉是否在背包中") || message.contains("验证码不正确") || message.contains("成功炼成丹药") || message.contains("药材是否在背包中") ){
                if(message.contains("验证码不正确") && !this.alchemyList.isEmpty()){
                    this.autoAlchemy(group);
                    return;
                }
                if (!this.alchemyList.isEmpty()) {
                    this.alchemyList.remove(0);
                }
                Config config = danCalculator.getConfig(bot.getBotId());
                if (this.alchemyList.isEmpty()) {
                    this.resetPram();
                    if(config.isFinishAutoBuyHerb()){
                        group.sendMessage((new MessageChain()).text("确认一键丹药炼金"));
                    }else{
                        group.sendMessage((new MessageChain()).text("自动炼丹完成！！"));
                        botConfig.setStartAuto(false);
                    }
                }
                this.autoAlchemy(group);


            }

        }

    }

    private boolean isAtSelf(Group group,Bot bot){
//        return message.contains("@" + bot.getBotId()) || message.contains("@" +bot.getBotName()) ;
        return group.getGroupId() == bot.getBotConfig().getGroupId();
    }

    private void autoAlchemy(Group group) {
        Iterator var3 = this.alchemyList.iterator();

        while (var3.hasNext()) {
            String remedy = (String) var3.next();

            try {
                group.sendMessage((new MessageChain()).at("3889001741").text(remedy));
                break;
            } catch (Exception var5) {
                Thread.currentThread().interrupt();
            }
        }

    }

//    @GroupMessageHandler(
//            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
//    )
//    public void 统计群聊发言次数(final Bot bot, final Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
//
//        try {
//            if (group != null && group.getGroupId() > 0 && groupManager != null && groupManager.MESSAGE_NUMBER_MAP != null) {
//                MessageNumber messageNumber = groupManager.MESSAGE_NUMBER_MAP.get(bot.getBotId()+"");
//                if ((danCalculator != null && danCalculator.config != null && danCalculator.config.getAlchemyQQ() == bot.getBotId()) &&
//                        (messageNumber.getNumber() == 10 || messageNumber.getNumber() % 100 == 0)) {
//                    bot.setGroupCard(bot.getBotConfig().getGroupId(), bot.getBotId(), bot.getBotName() + "(发言次数:" + messageNumber.getNumber() + ")");
//                }
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//
//
//    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 药材背包(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();


        if (isGroup && (message.contains("上一页") || message.contains("下一页")|| message.contains("药材背包")) && botConfig.isStartAuto()) {
            Iterator var7 = messageChain.getMessageByType(TextMessage.class).iterator();
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
                System.out.println("msg==" + msg);
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
                    group.sendMessage((new MessageChain()).text("药材背包已刷新，开始匹配丹方..."));
                    this.parseHerbList(bot.getBotId());
                }
            } else {
//                System.out.println("message==" + message);

            }
        }

    }

    private static final String DAN_LU = "丹炉寒铁铸心炉";

    private void buyHerbAndSmeltDan(Long botId) throws Exception {
        Map<String, List<String>> parseRecipes = this.parseRecipes(botId);

        boolean matchedAny = false;

        for (Map.Entry<String, List<String>> entry : parseRecipes.entrySet()) {
            List<String> recipeList = entry.getValue();
            if (recipeList == null || recipeList.isEmpty()) continue;

            for (int i = 0; i < recipeList.size(); i++) {
                String recipe = recipeList.get(i);
                Map<String, String> herbMap = this.getParseRecipeMap(recipe);

                // 展示用（严格保留原始数量，含负号）
                String main = "";
                String lead = "";
                String assist = "";

                // 校验/扣减用（同名药材总需求量，按绝对值累加）
                Map<String, Integer> totalNeed = new HashMap<>();

                for (Map.Entry<String, String> he : herbMap.entrySet()) {
                    String rolePlusName = he.getKey();       // 例如: "主药幻心草"
                    String herbName     = cleanHerbName(rolePlusName); // "幻心草"

                    String[] parts = he.getValue().split("&");
                    int countRaw  = Integer.parseInt(parts[0]);  // 可能是 -1
                    int countAbs  = Math.abs(countRaw);

                    // 保留原始展示
                    if (rolePlusName.contains("主药")) main  = rolePlusName + countRaw;
                    if (rolePlusName.contains("药引")) lead  = rolePlusName + countRaw;
                    if (rolePlusName.contains("辅药")) assist = rolePlusName + countRaw;

                    // 同名药材总需求量（用于库存校验与扣减）
                    totalNeed.merge(herbName, countAbs, Integer::sum);
                }

                boolean canSmelt = StringUtils.isNotBlank(main)
                        && StringUtils.isNotBlank(lead)
                        && StringUtils.isNotBlank(assist);

                // 先校验库存（同名药材按总量一次性校验）
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
                    // ==== 新增：解析收益 ====
                    int profit = parseProfit(recipe);
                    // 展示严格按原始数量（你的例子就会是：主药幻心草-1药引玄冰花-1辅药玄冰花-1）
//                    this.alchemyList.add("配方" + main + lead + assist + DAN_LU);

                    this.alchemyList.add("配方" + main + lead + assist + DAN_LU + " == 炼金收益" + profit);

                    // 扣减库存（同名药材按总量扣减一次）
                    for (Map.Entry<String, Integer> need : totalNeed.entrySet()) {
                        modifyHerbCount(need.getKey(), need.getValue(), botId);
                    }

                    // 继续尝试当前配方
                    i--;
                }
            }
        }

        if (!matchedAny) {
            if (this.group != null) {
                this.group.sendMessage(new MessageChain().text("未匹配到丹方，请检查丹方设置"));
            }
            this.resetPram();
        } else {
            if (this.group != null) {
                this.group.sendMessage(new MessageChain().text("匹配到" + this.alchemyList.size() + "个丹方，准备开始自动炼丹"));
            }
             this.autoAlchemy(this.group);
        }
    }

    /** 从丹方字符串里解析“炼金收益” */
    private int parseProfit(String recipe) {
        Pattern p = Pattern.compile("炼金收益(\\d+)");
        Matcher m = p.matcher(recipe);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0; // 没有写收益时返回 0
    }

    /** 去掉角色前缀，得到纯药材名 */
    private String cleanHerbName(String key) {
        return key.replace("主药", "").replace("药引", "").replace("辅药", "");
    }



    /**
     * 提取公共方法：叠加药材数量
     */
    private int adjustHerbCount(int herbCount, String herb, String main, String lead, String assist) {
        herbCount += extractCountIfContains(main, herb);
        herbCount += extractCountIfContains(lead, herb);
        herbCount += extractCountIfContains(assist, herb);
        return herbCount;
    }

    /**
     * 从字符串里提取某个 herb 的数量
     */
    private int extractCountIfContains(String text, String herb) {
        if (StringUtils.isBlank(text) || !text.contains(herb)) {
            return 0;
        }
        return Integer.parseInt(text.replace(herb, "").replace("主药", "").replace("药引", "").replace("辅药", ""));
    }

    /**
     * 封装发送消息
     */
    private void notifyGroup(String msg) {
        if (this.group != null) {
            this.group.sendMessage(new MessageChain().text(msg));
        }
    }


    private static void clearFile(String filePath) {
        try {
            FileWriter fw = new FileWriter(filePath, false);

            try {
                System.out.println("背包文件清空");
            } catch (Throwable var3) {
            }

            fw.close();
        } catch (Exception var4) {
            System.out.println("背包文件清空错误");
        }

    }

    public Map<String, List<String>> parseRecipes(Long botId) throws IOException {
        Map<String, List<String>> danRecipes = new LinkedHashMap();
        BufferedReader reader = new BufferedReader(new FileReader(botId + "/炼丹配方.txt"));
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
                    currentRecipes = new ArrayList();
                } else if (currentDan != null && !line.isEmpty()) {
                    currentRecipes.add(line);
                }
            }
        }

        if (currentDan != null) {
            danRecipes.put(currentDan, currentRecipes);
        }

        reader.close();
        return danRecipes;
    }

    private int herbExistence(String herb, int herbCount,Long botId) {
        int count = getHerbCount(herb,botId);
        return count == -1 ? herbCount : herbCount - count;
    }

    private Map<String, String> getParseRecipeMap(String v) {
        Map<String, String> map = new LinkedHashMap();
        String[] str = v.split(" ");
        String[] var4 = str;
        int var5 = str.length;

        for (int var6 = 0; var6 < var5; ++var6) {
            String s = var4[var6];
            if (s.contains("主药") || s.contains("药引") || s.contains("辅药")) {
                String[] myStrs = s.split("-");
                map.put(myStrs[0], myStrs[1]);
            }
        }

        return map;
    }

    public static int getHerbCount(String name,Long botId) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(botId+ "/背包药材.txt"));

            while (true) {
                try {
                    String line;
                    if ((line = reader.readLine()) != null) {
                        String[] parts = line.split(" ");
                        if (!parts[0].equals(name)) {
                            continue;
                        }

                        return Integer.parseInt(parts[1]);
                    }
                } catch (Throwable var4) {
                    reader.close();
                    reader.close();
                    return -1;
                }

                reader.close();
                return -1;
            }
        } catch (IOException var5) {
            return -1;
        }
    }

    public static void modifyHerbCount(String name, int amount,Long botId) {
        List<String> lines = new ArrayList();
        boolean found = false;

        try {
            BufferedReader reader = new BufferedReader(new FileReader(botId + "/背包药材.txt"));

            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(" ");
                    if (parts[0].equals(name)) {
                        found = true;
                        int newAmount = Integer.parseInt(parts[1]) - amount;
                        if (newAmount > 0) {
                            lines.add(parts[0] + " " + newAmount);
                        }
                    } else {
                        lines.add(line);
                    }
                }
            } catch (Throwable var11) {
                reader.close();
            }

            reader.close();
        } catch (IOException var12) {
        }

        if (!found && amount > 0) {
            lines.add(name + " " + amount);
        }

        try {
            FileWriter fw = new FileWriter(botId + "/背包药材.txt", false);

            try {
                BufferedWriter writer = new BufferedWriter(fw);

                try {
                    Iterator var19 = lines.iterator();

                    while (var19.hasNext()) {
                        String line = (String) var19.next();
                        writer.write(line);
                        writer.newLine();
                    }

                    writer.flush();
                } catch (Throwable var8) {
                    writer.close();
                }

                writer.close();
            } catch (Exception var9) {
                fw.close();
            }

            fw.close();
        } catch (IOException var10) {
        }

    }

    public void parseHerbList(Long botId) throws Exception {
        String currentHerb = null;
        Iterator var2 = this.medicinalList.iterator();

        while (var2.hasNext()) {
            String line = (String) var2.next();
            line = line.trim();
            if (line.contains("名字：")) {
                currentHerb = line.replaceAll("名字：", "");
            } else if (currentHerb != null && line.contains("拥有数量:")) {
                int count = Integer.parseInt(line.split("拥有数量:|炼金")[1]);
                this.updateMedicine(currentHerb, count,botId);
                currentHerb = null;
            }
        }

        System.out.println("药材背包已更新");
        customPool.submit(new Runnable() {
            public void run() {
                try {
                    AutoAlchemyTask.this.buyHerbAndSmeltDan(botId);
                } catch (Exception var2) {
                    System.out.println("加载药材基础数据异常");
                }

            }
        });
    }

    public void updateMedicine(String name, int quantity,Long botId) {
        String filePath = botId + "/背包药材.txt";
        List<String> lines = new ArrayList<>();
        boolean found = false;
        // 读取文件内容并处理
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 分割药材名称和数量（忽略多余空格）
                String[] parts = line.split("\\s+");
                if (parts.length >= 1 && parts[0].equals(name)) {
                    lines.add(name + " " + quantity); // 替换当前行
                    found = true;
                } else {
                    lines.add(line); // 保留原有行
                }
            }
        } catch (FileNotFoundException e) {
            // 文件不存在时无需处理
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // 如果未找到药材，添加新行
        if (!found) {
            lines.add(name + " " + quantity);
        }

        // 将修改后的内容写入文件
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
