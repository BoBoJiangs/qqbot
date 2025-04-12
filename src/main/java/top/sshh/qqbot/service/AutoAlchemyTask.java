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
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.Config;

import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static top.sshh.qqbot.service.DanCalculator.targetDir;

@Component
public class AutoAlchemyTask {
    private static final Logger log = LoggerFactory.getLogger(AutoAlchemyTask.class);
    //    public static final String targetDir = "C:\\Users\\Administrator\\Desktop\\修仙java脚本";
    private List<String> medicinalList = new ArrayList();
    public int page = 1;
    @Autowired
    public DanCalculator danCalculator;
    private static final ForkJoinPool customPool = new ForkJoinPool(20);
    private List<String> alchemyList = new CopyOnWriteArrayList();
    private Group group;
    private Config config;

    public AutoAlchemyTask() {
    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void enableScheduled(final Bot bot, final Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        if ("炼丹命令".equals(message)) {
            group.sendMessage((new MessageChain()).reply(messageId).text(this.showReplyMessage(message)));
        }

        if ("炼丹设置".equals(message)) {
            group.sendMessage((new MessageChain()).reply(messageId).text(this.showReplyMessage(message)));
        }

        if ("开始自动炼丹".equals(message)) {
            this.group = group;
            this.resetPram();
            botConfig.setStartAuto(true);
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        AutoAlchemyTask.clearFile(targetDir+"背包药材.txt");
                        group.sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
                    } catch (Exception var2) {
                    }

                }
            });
        }

        if ("停止自动炼丹".equals(message)) {
            this.resetPram();
            botConfig.setStartAuto(false);
            group.sendMessage((new MessageChain()).text("已停止自动炼丹"));
        }

        if (message.equals("查询炼丹配方")) {
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        File dataFile = new File(targetDir+"炼丹配方.txt");
                        bot.uploadGroupFile(group.getGroupId(), dataFile.getAbsolutePath(), "炼丹配方.txt", "");
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
                        File dataFile = new File(targetDir+"properties/药材价格.txt");
                        bot.uploadGroupFile(group.getGroupId(), dataFile.getAbsolutePath(), "药材价格.txt", "");
                    } catch (Exception var2) {
                        System.out.println("上传文件异常");
                    }

                }
            });
        }

        if (message.equals("添加成功,同步炼丹配方")) {
            this.danCalculator.loadData();
            this.danCalculator.calculateAllDans();
            group.sendMessage((new MessageChain()).text("已同步炼丹配方！"));
        }

        if (message.startsWith("查丹方")) {
//            final String string = message.substring(message.indexOf("查丹方") + 3).trim();
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        AutoAlchemyTask.this.danCalculator.parseRecipes(message, group);
                    } catch (Exception var2) {
                        System.out.println("加载基础数据异常");
                    }

                }
            });
        }

        if (message.startsWith("更新炼丹配置")) {
            this.config = this.danCalculator.getConfig();
            Pattern pattern = Pattern.compile("是否是炼金丹药：(true|false).*?炼金丹期望收益：(\\d+).*?坊市丹期望收益：(\\d+).*?丹药数量：(\\d+).*?坊市丹名称：([^\\n]+).*?炼丹QQ号码：(\\d+).*?炼丹完成是否购买药材：(true|false).*?背包药材数量限制：(\\d+).*?降低采购药材价格：(\\d+)", 32);
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                this.config.setAlchemy(Boolean.parseBoolean(matcher.group(1)));
                this.config.setAlchemyNumber(Integer.parseInt(matcher.group(2)));
                this.config.setMakeNumber(Integer.parseInt(matcher.group(3)));
                this.config.setDanNumber(Integer.parseInt(matcher.group(4)));
                this.config.setMakeName(matcher.group(5));
                this.config.setAlchemyQQ(Long.parseLong(matcher.group(6)));
                this.config.setFinishAutoBuyHerb(Boolean.parseBoolean(matcher.group(7)));
                this.config.setLimitHerbsCount(Integer.parseInt(matcher.group(8)));
                this.config.setAddPrice(Integer.parseInt(matcher.group(9)));
                customPool.submit(new Runnable() {
                    public void run() {
                        try {
                            AutoAlchemyTask.this.danCalculator.saveConfig(AutoAlchemyTask.this.config);
                            group.sendMessage((new MessageChain()).text("丹方配置已更新，正在重新匹配丹方！"));
                            AutoAlchemyTask.this.danCalculator.loadData();
                            AutoAlchemyTask.this.danCalculator.calculateAllDans();
                            group.sendMessage((new MessageChain()).text("丹方匹配成功！"));
                            AutoAlchemyTask.this.danCalculator.addAutoBuyHerbs();
                        } catch (Exception var2) {
                        }

                    }
                });
            } else {
                this.config = this.danCalculator.getConfig();
                String alchemyConfig = "\n更新炼丹配置\n是否是炼金丹药：" + this.config.isAlchemy() + "\n炼金丹期望收益：" + this.config.getAlchemyNumber() + "\n坊市丹期望收益：" + this.config.getMakeNumber() + "\n丹药数量：" + this.config.getDanNumber() + "\n坊市丹名称：" + this.config.getMakeName() + "\n炼丹QQ号码：" + this.config.getAlchemyQQ() + "\n炼丹完成是否购买药材：" + this.config.isFinishAutoBuyHerb() + "\n背包药材数量限制：" + this.config.getLimitHerbsCount() + "\n降低采购药材价格：" + this.config.getAddPrice();
                group.sendMessage((new MessageChain()).reply(messageId).text("输入格式不正确！示例：" + alchemyConfig));
            }
        }

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
                        AutoAlchemyTask.this.danCalculator.parseRecipes(string, group);
                    } catch (Exception var2) {
                        System.out.println("加载基础数据异常");
                    }

                }
            });
        }

    }

    private String showReplyMessage(String message) {
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
            return sb.toString();
        } else {
            if (message.equals("炼丹设置")) {
                this.config = this.danCalculator.getConfig();
                String alchemyConfig = "是否是炼金丹药：" + this.config.isAlchemy() + "\n炼金丹期望收益：" + this.config.getAlchemyNumber() + "\n坊市丹期望收益：" + this.config.getMakeNumber() + "\n丹药数量：" + this.config.getDanNumber() + "\n坊市丹名称：" + this.config.getMakeName() + "\n炼丹QQ号码：" + this.config.getAlchemyQQ() + "\n炼丹完成是否购买药材：" + this.config.isFinishAutoBuyHerb() + "\n背包药材数量限制：" + this.config.getLimitHerbsCount() + "\n降低采购药材价格：" + this.config.getAddPrice();
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
    public void 自动炼丹(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        if (message.contains("" + bot.getBotId()) && botConfig.isStartAuto() && (message.contains("请检查炼丹炉是否在背包中") || message.contains("成功炼成丹药") || message.contains("药材是否在背包中"))) {
            if (!this.alchemyList.isEmpty()) {
                this.alchemyList.remove(0);
            }

            if (this.alchemyList.isEmpty()) {
                this.resetPram();
                botConfig.setStartAuto(false);
                group.sendMessage((new MessageChain()).text("自动炼丹完成！！"));
            }

            this.autoAlchemy(group);
        }

    }

    private void autoAlchemy(Group group) {
        Iterator var3 = this.alchemyList.iterator();

        while(var3.hasNext()) {
            String remedy = (String)var3.next();

            try {
                group.sendMessage((new MessageChain()).at("3889001741").text(remedy));
                break;
            } catch (Exception var5) {
                Thread.currentThread().interrupt();
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 药材背包(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws Exception {
        BotConfig botConfig = bot.getBotConfig();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
        if (message.contains("" + bot.getBotId()) || message.contains(bot.getBotName())) {

        } else {
        }

        if (isGroup && message.contains("拥有数量") && message.contains("坊市数据") && botConfig.isStartAuto()) {
            Iterator var7 = messageChain.getMessageByType(TextMessage.class).iterator();
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
                    this.parseHerbList();
                }
            } else {
//                System.out.println("message==" + message);

            }
        }

    }

    private void buyHerbAndSmeltDan() throws Exception {
        Map<String, List<String>> parseRecipes = this.parseRecipes();
        Iterator var2 = parseRecipes.entrySet().iterator();

        while(true) {
            List value;
            String v;
            do {
                do {
                    if (!var2.hasNext()) {
                        if (this.alchemyList.isEmpty() && this.group != null) {
                            this.group.sendMessage((new MessageChain()).text("未匹配到丹方，请检查丹方设置"));
                            this.resetPram();
                        } else {
                            this.group.sendMessage((new MessageChain()).text("配到" + this.alchemyList.size() + "个丹方，准备开始自动炼丹"));
                        }

                        this.autoAlchemy(this.group);
                        return;
                    }

                    Map.Entry<String, List<String>> entry = (Map.Entry)var2.next();
                    value = (List)entry.getValue();
                    v = (String)entry.getKey();
                } while(value == null);
            } while(value.isEmpty());

            for(int d = 0; d < value.size(); ++d) {
                v = (String)value.get(d);
                Map<String, String> herbMap = this.getParseRecipeMap(v);
                String main = "";
                String lead = "";
                String assist = "";
                boolean b = true;
                Iterator var13 = herbMap.entrySet().iterator();

                Map.Entry herbEntry;
                String key;
                String herb;
                while(var13.hasNext()) {
                    herbEntry = (Map.Entry)var13.next();
                    key = (String)herbEntry.getKey();
                    herb = key.replaceAll("主药", "").replaceAll("药引", "").replaceAll("辅药", "");
                    String[] sList = ((String)herbEntry.getValue()).split("&");
                    int herbCount = Integer.parseInt(sList[0]);
                    int herbPrice = Integer.parseInt(sList[1]);
                    if (key.contains("主药")) {
                        main = key + herbCount;
                        if (lead.contains(herb)) {
                            herbCount += Integer.parseInt(lead.replaceAll(herb, "").replaceAll("主药", "").replaceAll("药引", "").replaceAll("辅药", ""));
                        }

                        if (assist.contains(herb)) {
                            herbCount += Integer.parseInt(assist.replaceAll(herb, "").replaceAll("主药", "").replaceAll("药引", "").replaceAll("辅药", ""));
                        }
                    }

                    if (key.contains("药引")) {
                        lead = key + herbCount;
                        if (main.contains(herb)) {
                            herbCount += Integer.parseInt(main.replaceAll(herb, "").replaceAll("主药", "").replaceAll("药引", "").replaceAll("辅药", ""));
                        }

                        if (assist.contains(herb)) {
                            herbCount += Integer.parseInt(assist.replaceAll(herb, "").replaceAll("主药", "").replaceAll("药引", "").replaceAll("辅药", ""));
                        }
                    }

                    if (key.contains("辅药")) {
                        assist = key + herbCount;
                        if (main.contains(herb)) {
                            herbCount += Integer.parseInt(main.replaceAll(herb, "").replaceAll("主药", "").replaceAll("药引", "").replaceAll("辅药", ""));
                        }

                        if (lead.contains(herb)) {
                            herbCount += Integer.parseInt(lead.replaceAll(herb, "").replaceAll("主药", "").replaceAll("药引", "").replaceAll("辅药", ""));
                        }
                    }

                    int stayHerbCount = this.herbExistence(herb, herbCount);
                    if (stayHerbCount > 0) {
                        b = false;
                        break;
                    }
                }

                if (b) {
//                    System.out.println("背包药材校验成功！开始炼丹");

                    if (StringUtils.isNotBlank(main) && StringUtils.isNotBlank(lead) && StringUtils.isNotBlank(assist)) {
                        this.alchemyList.add("配方" + main + lead + assist + "丹炉寒铁铸心炉");
                    }

                    --d;
                    var13 = herbMap.entrySet().iterator();

                    while(var13.hasNext()) {
                        herbEntry = (Map.Entry)var13.next();
                        key = (String)herbEntry.getKey();
                        herb = key.replaceAll("主药", "").replaceAll("药引", "").replaceAll("辅药", "");
                        int amount = Integer.parseInt(((String)herbEntry.getValue()).split("&")[0]);
                        modifyHerbCount(herb, amount);
                    }
                }
            }
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

    public Map<String, List<String>> parseRecipes() throws IOException {
        Map<String, List<String>> danRecipes = new LinkedHashMap();
        BufferedReader reader = new BufferedReader(new FileReader(targetDir+"炼丹配方.txt"));
        String currentDan = null;
        List<String> currentRecipes = null;

        String line;
        while((line = reader.readLine()) != null) {
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

    private int herbExistence(String herb, int herbCount) {
        int count = getHerbCount(herb);
        return count == -1 ? herbCount : herbCount - count;
    }

    private Map<String, String> getParseRecipeMap(String v) {
        Map<String, String> map = new LinkedHashMap();
        String[] str = v.split(" ");
        String[] var4 = str;
        int var5 = str.length;

        for(int var6 = 0; var6 < var5; ++var6) {
            String s = var4[var6];
            if (s.contains("主药") || s.contains("药引") || s.contains("辅药")) {
                String[] myStrs = s.split("-");
                map.put(myStrs[0], myStrs[1]);
            }
        }

        return map;
    }

    public static int getHerbCount(String name) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(targetDir+"背包药材.txt"));

            while(true) {
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

    public static void modifyHerbCount(String name, int amount) {
        List<String> lines = new ArrayList();
        boolean found = false;

        try {
            BufferedReader reader = new BufferedReader(new FileReader(targetDir+"背包药材.txt"));

            String line;
            try {
                while((line = reader.readLine()) != null) {
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
            FileWriter fw = new FileWriter(targetDir+"背包药材.txt", false);

            try {
                BufferedWriter writer = new BufferedWriter(fw);

                try {
                    Iterator var19 = lines.iterator();

                    while(var19.hasNext()) {
                        String line = (String)var19.next();
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
                this.updateMedicine(currentHerb, count);
                currentHerb = null;
            }
        }

        System.out.println("药材背包已更新");
        customPool.submit(new Runnable() {
            public void run() {
                try {
                    AutoAlchemyTask.this.buyHerbAndSmeltDan();
                } catch (Exception var2) {
                    System.out.println("加载药材基础数据异常");
                }

            }
        });
    }

    public void updateMedicine(String name, int quantity) {
        String filePath = targetDir+"背包药材.txt";
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
