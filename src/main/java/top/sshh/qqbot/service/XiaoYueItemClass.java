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
        if (message.contains("出售结果如下") && botConfig.isEnableXyHerbClass()) {
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

        Pattern pattern = Pattern.compile("【(.*?)】(\\d+)个");
        for (String record : records) {
            Matcher matcher = pattern.matcher(record);
            if (matcher.find()) {
                String name = matcher.group(1).trim();
                int count = Integer.parseInt(matcher.group(2));
                String grade = ITEM_GRADE_MAP.getOrDefault(name, "未知");

                // 总数
                gradeCount.put(grade, gradeCount.getOrDefault(grade, 0) + count);

                // 特殊数
                if (SPECIAL_ITEMS.contains(name)) {
                    gradeSpecialCount.put(grade, gradeSpecialCount.getOrDefault(grade, 0) + count);
                }
            }
        }

        // 输出顺序统一
        String[] gradeOrder = {
                "一品药材", "二品药材", "三品药材", "四品药材", "五品药材",
                "六品药材", "七品药材", "八品药材", "九品药材",
                "天元", "圣器", "圣人", "未知"
        };

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("道友成功出售：\n");
        for (String grade : gradeOrder) {
            int count = gradeCount.getOrDefault(grade, 0);
            if (count > 0) {
                int special = gradeSpecialCount.getOrDefault(grade, 0);
                int normalCount = count - special;
                stringBuilder.append(grade).append(" ").append(normalCount);
                if (special > 0) {
                    stringBuilder.append(" 特殊").append(special);
                }
                stringBuilder.append("\n");
            }
        }
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
