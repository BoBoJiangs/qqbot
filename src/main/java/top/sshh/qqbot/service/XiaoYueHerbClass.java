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
public class XiaoYueHerbClass {

    private static final Map<String, String> MEDICINE_GRADE_MAP = new HashMap<>();
    private static final Set<String> SPECIAL_MEDICINES = new HashSet<>();


    @GroupMessageHandler(
//            senderIds = {3889282919L}
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 药材分类(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        if(message.contains("出售结果如下") && botConfig.isEnableXyHerbClass()){
            String[] lines = message.split("\n");
            try {
                showHerbalClass(lines,group,messageId);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    @PostConstruct
    public void init() throws IOException {
        // 1. 读取文件
        File file = new File("xiaoyue/medicines.txt");
        if(file.exists()){
            loadMedicines("xiaoyue/medicines.txt");
            loadSpecialMedicines("xiaoyue/special.txt");
        }

    }

    public void showHerbalClass(String[] records,Group group,Integer messageId) throws IOException {


        // 3. 统计
        Map<String, Integer> gradeCount = new HashMap<>();
        Map<String, Integer> gradeSpecialCount = new HashMap<>();

        Pattern pattern = Pattern.compile("【(.*?)】(\\d+)个");
        for (String record : records) {
            Matcher matcher = pattern.matcher(record);
            if (matcher.find()) {
                String name = matcher.group(1).trim();
                int count = Integer.parseInt(matcher.group(2));
                String grade = MEDICINE_GRADE_MAP.getOrDefault(name, "未知");

                // 总数
                gradeCount.put(grade, gradeCount.getOrDefault(grade, 0) + count);

                // 特殊数
                if (SPECIAL_MEDICINES.contains(name)) {
                    gradeSpecialCount.put(grade, gradeSpecialCount.getOrDefault(grade, 0) + count);
                }
            }
        }

        // 4. 输出（跳过数量为0的）
        String[] gradeOrder = {
                "一品药材", "二品药材", "三品药材", "四品药材", "五品药材",
                "六品药材", "七品药材", "八品药材", "九品药材", "未知"
        };
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("道友成功出售药材：");
        stringBuilder.append("\n");
        for (String grade : gradeOrder) {
            int count = gradeCount.getOrDefault(grade, 0);
            if (count > 0) {
                int special = gradeSpecialCount.getOrDefault(grade, 0);
                int normalCount = count - special;  // 减去特殊数
                stringBuilder.append(grade).append(" ").append(normalCount);
//                System.out.printf("%s %d", grade, normalCount);
                if (special > 0) {
                    stringBuilder.append(" 特殊").append(special);
//                    System.out.printf(" 特殊%d", special);
                }
                stringBuilder.append("\n");
            }
        }
        group.sendMessage((new MessageChain()).reply(messageId).text(stringBuilder.toString()));
    }

    // 读取药材文件
    private  void loadMedicines(String fileName) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(fileName));
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("=");
            if (parts.length == 2) {
                String name = parts[0].trim();
                String grade = parts[1].trim();
                MEDICINE_GRADE_MAP.put(name, grade);
            }
        }
    }

    // 读取特殊药材文件
    private  void loadSpecialMedicines(String fileName) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(fileName));
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty()) {
                SPECIAL_MEDICINES.add(line);
            }
        }
    }
}
