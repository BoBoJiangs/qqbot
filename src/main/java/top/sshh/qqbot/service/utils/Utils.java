package top.sshh.qqbot.service.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zhuangxv.bot.api.ApiResult;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Button;
import com.zhuangxv.bot.core.Buttons;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.Message;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.TextMessage;
import org.apache.commons.lang3.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utils {
    private static final Pattern CAPTCHA_PROMPT_PATTERN =
            Pattern.compile("请点击[^\\r\\n\\]]*?按钮");

//    public static boolean isAtSelf(Bot bot, Group group) {
//
//        return  group.getGroupId() == bot.getBotConfig().getGroupId();
//    }
    public static boolean isAtSelf(Bot bot, Group group, String message,long xxGroupId) {
        String safeMessage = StringUtils.defaultString(message);
        if(xxGroupId == 0){
            return safeMessage.contains(""+bot.getBotId()) || safeMessage.contains("@"+bot.getBotName()) ;
        }
        return group != null && (group.getGroupId() == bot.getBotConfig().getGroupId()
                || safeMessage.contains(""+bot.getBotId())) ;
    }
    public static boolean isAtSelf(Bot bot, String message) {
        return StringUtils.defaultString(message).contains(""+bot.getBotId());
    }
    public static Group getRemindGroup(Bot bot,long xxGroupId) {
        long groupId = bot.getBotConfig().getGroupId();
        long taskId = bot.getBotConfig().getTaskId();
        if (taskId > 0) {
            groupId = taskId;
        }
        if (xxGroupId != 0L) {
            groupId = xxGroupId;
        }
        return bot.getGroup(groupId);
    }

    /**
     * 向机器人当前已加入的群发送消息。目标群不在本地群列表时直接跳过，
     * 避免机器人退群或群配置错误导致空指针异常。
     *
     * @return 是否已发起发送
     */
    public static boolean sendGroupMessage(Bot bot, long groupId, MessageChain messageChain) {
        if (bot == null || groupId <= 0L || messageChain == null) {
            return false;
        }
        Group group = bot.getGroup(groupId);
        if (group == null) {
            return false;
        }
        group.sendMessage(messageChain);
        return true;
    }

    // 计算手续费率
    public static double calculateFeeRate(int price) {
        if (price <= 500) return 0.05;
        if (price <= 1000) return 0.1;
        if (price <= 1500) return 0.15;
        if (price <= 2000) return 0.2;
        return 0.3;
    }

    public static String formatButtons(List<Button> buttonList, int buttonsPerRow) {
        if (buttonList == null || buttonList.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int totalButtons = buttonList.size();
        int rows = (int) Math.ceil((double) totalButtons / buttonsPerRow);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < buttonsPerRow; col++) {
                int index = row * buttonsPerRow + col;
                if (index >= totalButtons) {
                    break;
                }
                Button button = buttonList.get(index);
                sb.append(" [").append(index + 1).append("] ").append(button.getLabel());

                // 添加空格分隔，最后一项不加
                if (col < buttonsPerRow - 1 && index < totalButtons - 1) {
                    sb.append("  ");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

//    public static void forwardMessage(Bot bot,long xxGroupId, String message){
//        if(bot.getBotConfig().isEnableForwardMessage()){
//            getRemindGroup(bot,xxGroupId).sendMessage(new MessageChain().text(cleanMessage(message)));
//        }
//    }
    /**
     * 转发文本清洗：剥离 markdown 链接壳（[@宣藩九](mqqapi://...) -> @宣藩九）、
     * 删除 [](%7B...%7D) 版本标记行和空行，让转发到控制群的内容可读
     */
    public static String cleanForwardText(String text) {
        if (text == null) {
            return null;
        }
        String s = stripMarkdownLink(text);
        s = s.replaceAll("(?m)^[ \\t]*\\n", "");
        return s.trim();
    }

    /**
     * 提取消息链中的文本内容。SnowLuma 的卡片通常是
     * at + markdown + inline_keyboard，其中正文位于 MarkdownMessage（TextMessage 子类）中。
     */
    public static String getMessageText(MessageChain messageChain) {
        if (messageChain == null || messageChain.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Message message : messageChain) {
            if (message instanceof TextMessage && StringUtils.isNotBlank(((TextMessage) message).getText())) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(((TextMessage) message).getText());
            }
        }
        return text.toString();
    }

    public static void forwardMessage(Bot bot,long xxGroupId,  MessageChain messageChain){
        if (bot == null || !bot.getBotConfig().isEnableForwardMessage() || xxGroupId <= 0) {
            return;
        }
        try {
            // 不再只取最后一个文本段，兼容 SnowLuma 的 Markdown 卡片和多文本段消息。
            String message = cleanForwardText(getMessageText(messageChain));
            if (StringUtils.isBlank(message)) {
                return;
            }
            Group targetGroup = getRemindGroup(bot, xxGroupId);
            if (targetGroup == null) {
                return;
            }
            targetGroup.sendMessage(new MessageChain().text(message));
        } catch (Exception e) {
            // 转发失败不能中断同一条事件的其他业务处理。
        }
    }


    public static String cleanMessage(String message) {
        String cleaned = message.replaceAll("content\\[\\[.*?\\][\\s\\S]*?](?=\\s|$)", "");
        return cleaned.replaceAll("(\\n\\s*)+$", "").trim();
    }

    public static void downLoadImage(String url,String path) {

        // 目标文件夹
        File imagesDir = new File(path);
        if (!imagesDir.exists()) {
            imagesDir.mkdirs();  // 创建images目录
        }

        // 生成时间戳文件名
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
        String fileName = timestamp + ".jpg";

        File destinationFile = new File(imagesDir, fileName);

        try {
            URL imageUrl = new URL(url);
            BufferedImage image = ImageIO.read(imageUrl);

            if (image != null) {
                ImageIO.write(image, "jpg", destinationFile);
            } else {
                System.out.println("读取图片失败");
            }
        } catch (IOException e) {
            System.out.println("下载保存图片时发生错误");
            e.printStackTrace();
        }
    }

    public static String readString(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, "UTF-8");
    }

    public static String readString(Path path, String charset) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, charset);
    }

    public static String extractAfterPleaseClick(String resultText) {
        resultText = resultText.replaceAll("请点点", "请点击");
        resultText = resultText.replaceAll("情点", "请点");
        resultText = resultText.replaceAll("漏点", "请点");
        resultText = resultText.replaceAll("乘情", "表情");
        resultText = resultText.replaceAll("乘击", "点击");
        resultText = resultText.replaceAll("请击", "点击");
        resultText = resultText.replaceAll("点请", "点击");
        resultText = resultText.replaceAll("图4", "图中");
        resultText = resultText.replaceAll("图8", "图中");
        resultText = resultText.replaceAll("表蟹", "表情");
        resultText = resultText.replaceAll("表鲸", "表情");
        resultText = resultText.replaceAll("表请", "表情");
        resultText = resultText.replaceAll("点表", "点击");
        resultText = resultText.replaceAll("鲸击", "点击");


        int index = resultText.indexOf("请点击");
        if (index != -1) {
            return resultText.substring(index);
        }

        return resultText; // 如果没有找到"请点击"，返回原字符串
    }

    /**
     * 提取验证码卡片中的题目，去掉 SnowLuma Markdown 卡片的版本标记、@链接、图片链接和尾部括号。
     * 例如只保留：请点击图中第2个表情对应的按钮
     */
    public static String extractCaptchaPrompt(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        String normalized = text.replace("\\(", "(")
                .replace("\\)", ")")
                .replace("\\[", "[")
                .replace("\\]", "]");
        Matcher matcher = CAPTCHA_PROMPT_PATTERN.matcher(normalized);
        String prompt = null;
        while (matcher.find()) {
            prompt = matcher.group().trim();
        }
        return StringUtils.isNotBlank(prompt) ? prompt : cleanForwardText(normalized);
    }

    /**
     * 更灵活的格式化，自动选择单位
     */
    public static String formatNumberWithUnit(long number) {
        if (number < 10000) {
            return number + "";
        } else if (number < 100000000) {
            double tenThousand = number / 10000.0;
            return String.format("%.2f万", tenThousand);
        } else {
            double hundredMillion = number / 100000000.0;
            return String.format("%.2f亿", hundredMillion);
        }
    }

    /**
     * 从消息字符串中解析 inline_keyboard 段重建按钮。
     * SnowLuma 等协议端不携带 NapCat 的 elements/msgSeq 扩展结构，bot-core 注入的
     * Buttons 会是空列表，此时调用本方法兜底；NapCat 下注入成功则无需调用。
     */
    public static Buttons parseButtonsFromMessage(Bot bot, String message, Integer messageId) {
        if (StringUtils.isBlank(message) || !message.contains("inline_keyboard")) {
            return null;
        }
        JSONObject keyboardData = null;
        // 格式1：message整体是JSON数组
        try {
            JSONArray segments = JSON.parseArray(message);
            if (segments != null) {
                for (int i = 0; i < segments.size(); i++) {
                    JSONObject seg = segments.getJSONObject(i);
                    if (seg != null && "inline_keyboard".equals(seg.getString("type"))) {
                        keyboardData = seg.getJSONObject("data");
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 格式2：拼接文本中嵌着 json[{"type":"inline_keyboard",...}] 渲染段
        if (keyboardData == null) {
            String extracted = extractJsonSegment(message, "inline_keyboard");
            if (extracted != null) {
                try {
                    keyboardData = JSON.parseObject(extracted).getJSONObject("data");
                } catch (Exception ignored) {
                }
            }
        }
        if (keyboardData == null) {
            return null;
        }
        JSONArray rows = keyboardData.getJSONArray("rows");
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Buttons buttons = new Buttons();
        buttons.setBotAppid(keyboardData.getString("bot_appid"));
        for (int j = 0; j < rows.size(); j++) {
            JSONObject row = rows.getJSONObject(j);
            JSONArray buttonArray = row == null ? null : row.getJSONArray("buttons");
            if (buttonArray == null || buttonArray.isEmpty()) {
                continue;
            }
            buttons.addButtonList(JSON.parseArray(buttonArray.toJSONString(), Button.class));
        }
        if (buttons.getButtonList() == null || buttons.getButtonList().isEmpty()) {
            return null;
        }
        buttons.setMsgSeq(resolveMsgSeq(bot, messageId));
        return buttons;
    }

    /**
     * SnowLuma 的 click_inline_keyboard_button 要求 msg_seq 为无符号的消息序号
     * （message_seq），而事件里只能拿到 message_id，这里通过 get_msg 反查换算。
     */
    private static String resolveMsgSeq(Bot bot, Integer messageId) {
        if (bot == null || messageId == null) {
            return messageId == null ? "" : String.valueOf(messageId);
        }
        try {
            ApiResult result = bot.invoke(new GetMsgApi(messageId));
            if (result != null && result.getData() != null) {
                JSONObject data = (JSONObject) JSON.toJSON(result.getData());
                Long seq = data == null ? null : data.getLong("message_seq");
                if (seq != null && seq > 0) {
                    return String.valueOf(seq);
                }
            }
        } catch (Exception ignored) {
        }
        return String.valueOf(messageId);
    }

    /**
     * 从拼接渲染的消息文本中提取包含指定 type 的完整 JSON 对象（花括号配平，容忍字符串内的转义）
     */
    private static String extractJsonSegment(String message, String typeMarker) {
        int marker = message.indexOf("\"type\":\"" + typeMarker + "\"");
        if (marker < 0) {
            return null;
        }
        int start = message.lastIndexOf('{', marker);
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < message.length(); i++) {
            char c = message.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return message.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /**
     * 剥离 markdown 链接语法，保留链接文本：[地龙干](mqqapi://...) -> 地龙干
     * SnowLuma 下游戏卡片消息为 markdown 段，名字/翻页等文本嵌在链接里
     */
    public static String stripMarkdownLink(String s) {
        if (s == null) {
            return null;
        }
        return s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1").trim();
    }

    /**
     * 从药材背包行提取数量，兼容 markdown 渲染：
     * "拥有数量:44---[炼金](mqqapi://...)" 与纯文本 "拥有数量:44" 均可
     * 解析不到返回 -1
     */
    public static int parseHerbCount(String line) {
        if (line == null) {
            return -1;
        }
        Matcher matcher = Pattern.compile("拥有数量:\\s*(\\d+)").matcher(line);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }
}
