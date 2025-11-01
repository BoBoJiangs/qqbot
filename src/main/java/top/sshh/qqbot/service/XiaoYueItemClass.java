package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.springframework.stereotype.Component;
import com.zhuangxv.bot.core.Group;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class XiaoYueItemClass {

    private static final Map<String, String> ITEM_GRADE_MAP = new HashMap<>();
    private static final Set<String> SPECIAL_ITEMS = new HashSet<>();

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 物品分类(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        if(!messageChain.isEmpty()){
            message =   messageChain.get(messageChain.size()-1).toString();
        }

        if (message.contains("出售结果如下") && botConfig.isEnableXyHerbClass()) {

            System.out.println(message);
            String[] lines = message.split("\n");
            try {
                showItemClass(lines, group, messageId);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @PostConstruct
    public void init() throws IOException {
        // 读取药材和技能文件
        File medicineFile = new File("xiaoyue/medicines.txt");
        File skillFile = new File("xiaoyue/skills.txt");
        if (medicineFile.exists()) {
            loadItems("xiaoyue/medicines.txt");
        }
        if (skillFile.exists()) {
            loadItems("xiaoyue/skills.txt");
        }

        // 读取特殊物品（可包含药材或技能）
        File specialFile = new File("xiaoyue/special.txt");
        if (specialFile.exists()) {
            loadSpecialItems("xiaoyue/special.txt");
        }
    }

    private void showItemClass(String[] records, Group group, Integer messageId) {
        Map<String, Integer> gradeCount = new HashMap<>();
        Map<String, Integer> gradeSpecialCount = new HashMap<>();

        Pattern pattern = Pattern.compile("【(.*?)】(\\d+)个(?!\\d)");

        for (String record : records) {
            Matcher matcher = pattern.matcher(record);
            while (matcher.find()) {
                String name = matcher.group(1).trim();
                int count = Integer.parseInt(matcher.group(2));
                String grade = ITEM_GRADE_MAP.getOrDefault(name, "未知");

                gradeCount.put(grade, gradeCount.getOrDefault(grade, 0) + count);

                if (SPECIAL_ITEMS.contains(name)) {
                    gradeSpecialCount.put(grade, gradeSpecialCount.getOrDefault(grade, 0) + count);
                }
            }
        }

        // 输出顺序统一
        String[] gradeOrder = {
                "一品药材", "二品药材", "三品药材", "四品药材", "五品药材",
                "六品药材", "七品药材", "八品药材", "九品药材",
                "天元", "圣器", "圣人","神器", "未知"
        };

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("道友成功出售：\n");

        // 计算七品、八品、九品的数量（应用乘除规则）
        int sevenCount = gradeCount.getOrDefault("七品药材", 0);
        int sevenSpecial = gradeSpecialCount.getOrDefault("七品药材", 0);
        int eightCount = gradeCount.getOrDefault("八品药材", 0);
        int eightSpecial = gradeSpecialCount.getOrDefault("八品药材", 0);
        int nineCount = gradeCount.getOrDefault("九品药材", 0);
        int nineSpecial = gradeSpecialCount.getOrDefault("九品药材", 0);

        // 应用规则：七品除2，九品乘2
        int adjustedSevenCount = sevenCount / 2;
        int adjustedSevenSpecial = sevenSpecial / 2;
        int adjustedSevenNormal = adjustedSevenCount - adjustedSevenSpecial;

        int adjustedEightNormal = eightCount - eightSpecial;

        int adjustedNineCount = nineCount * 2;
        int adjustedNineSpecial = nineSpecial * 2;
        int adjustedNineNormal = adjustedNineCount - adjustedNineSpecial;

        // 计算总计（只包含七品、八品、九品的调整后数量）
        int totalNormal = adjustedSevenNormal + adjustedEightNormal + adjustedNineNormal;
        int totalSpecial = adjustedSevenSpecial + eightSpecial + adjustedNineSpecial;

        for (String grade : gradeOrder) {
            int count = gradeCount.getOrDefault(grade, 0);
            if (count > 0) {
                int special = gradeSpecialCount.getOrDefault(grade, 0);

                // 对七品和九品应用特殊规则
                if ("七品药材".equals(grade)) {
                    stringBuilder.append(grade).append(" ").append(adjustedSevenNormal);
                    if (adjustedSevenSpecial > 0) {
                        stringBuilder.append(" 特殊").append(adjustedSevenSpecial);
                    }
                } else if ("九品药材".equals(grade)) {
                    stringBuilder.append(grade).append(" ").append(adjustedNineNormal);
                    if (adjustedNineSpecial > 0) {
                        stringBuilder.append(" 特殊").append(adjustedNineSpecial);
                    }
                } else {
                    int normalCount = count - special;
                    stringBuilder.append(grade).append(" ").append(normalCount);
                    if (special > 0) {
                        stringBuilder.append(" 特殊").append(special);
                    }
                }
                stringBuilder.append("\n");
            }
        }

        // 添加总计
        stringBuilder.append("普通总计 ").append(totalNormal).append(" 特殊总计 ").append(totalSpecial);

        group.sendMessage((new MessageChain()).reply(messageId).text(stringBuilder.toString()));
    }

    private void loadItems(String fileName) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(fileName));
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("=");
            if (parts.length == 2) {
                String name = parts[0].trim();
                String grade = parts[1].trim();
                ITEM_GRADE_MAP.put(name, grade);
            }
        }
    }

    private void loadSpecialItems(String fileName) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(fileName));
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty()) {
                SPECIAL_ITEMS.add(line);
            }
        }
    }
}
