package top.sshh.qqbot.verifycode;


import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.*;
import com.zhuangxv.bot.message.Message;
import com.zhuangxv.bot.message.MessageChain;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.GuessIdiom;
import top.sshh.qqbot.data.RecognitionResult;
import top.sshh.qqbot.service.GroupManager;
import top.sshh.qqbot.service.TestService;
import top.sshh.qqbot.service.utils.Utils;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static top.sshh.qqbot.service.GroupManager.customPool;

@Component
//@ConditionalOnProperty(name = "captcha.enabled", havingValue = "true", matchIfMissing = false)
public class RemoteVerifyCode {
    private static final Logger logger = LoggerFactory.getLogger(TestService.class);
    @Value("${xxGroupId:0}")
    private Long xxGroupId;
    //用来判断其他QQ是否出现相同验证码，用来判断是否验证失败
    public Map<Long, RecognitionResult> codeUrlMap = new ConcurrentHashMap();
    @Autowired
    private GroupManager groupManager;
    @Autowired
    private TestService testService;
    @Value("${captcha.shitu-api-url:#{null}}")
    private String shituApiUrl;
    public Map<Long, Buttons> botButtonMap = new ConcurrentHashMap();
    private static final Map<String, Integer> CHINESE_NUMBERS = new HashMap<>();

    static {
        CHINESE_NUMBERS.put("零", 0);
        CHINESE_NUMBERS.put("一", 1);
        CHINESE_NUMBERS.put("二", 2);
        CHINESE_NUMBERS.put("三", 3);
        CHINESE_NUMBERS.put("四", 4);
        CHINESE_NUMBERS.put("五", 5);
        CHINESE_NUMBERS.put("六", 6);
        CHINESE_NUMBERS.put("七", 7);
        CHINESE_NUMBERS.put("八", 8);
        CHINESE_NUMBERS.put("九", 9);
        CHINESE_NUMBERS.put("十", 10);
    }


    // 示例使用方法
    @PostConstruct
    public void init() {

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void autoVerifyCode(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId, Buttons buttons) {
        boolean isSelfGroup = Utils.isAtSelf(bot, group, message,xxGroupId);


        if (bot.getBotConfig().getAutoVerifyModel() != 0 && isSelfGroup && buttons != null && !buttons.getButtonList().isEmpty() && buttons.getButtonList().size() > 5) {
            String verifyQQ = message.split("at_tinyid=")[1].split("\\)")[0];
            if (bot.getBotId() == Long.parseLong(verifyQQ)) {
                String regex = "https?://[^\\s\\)]+";
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(message);
                while (matcher.find()) {
                    buttons.setImageUrl(matcher.group());
                    buttons.setImageText(messageChain.get(messageChain.size() - 1).toString());
                }
                botButtonMap.put(bot.getBotId(), buttons);
                List<Button> buttonList = buttons.getButtonList();
                if (codeUrlMap.get(Long.parseLong(verifyQQ)) != null) {
                    RecognitionResult codeData = codeUrlMap.get(Long.parseLong(verifyQQ));
                    if (buttons.getImageUrl().equals(codeData.getUrl())) {

                        verifyFailSendMessage(bot, group, messageChain, message, messageId, buttons, "", codeData);
                        return;

                    }
                }


                StringBuilder buttonBuilder = new StringBuilder();
                for (int i = 0; i < buttonList.size(); i++) {
                    Button button = buttonList.get(i);
                    if (GuessIdiom.getEmoji(button.getLabel()) != null) {
                        buttonBuilder.append(" ").append(GuessIdiom.getEmoji(button.getLabel())).append(" ");
                    } else {
                        buttonBuilder.append(" ").append(button.getLabel()).append(" ");
                    }

                }

                customPool.submit(new Runnable() {
                    public void run() {
                        if (StringUtils.isNotBlank(shituApiUrl)) {
                            autoVerifyCode(bot, group, messageChain, message, messageId, buttons, "");
                        }
                    }
                });
            }


        }
    }


//     @GroupMessageHandler(
//             senderIds = {3889001741L}
//     )
//     public void 辅助识别验证码(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId, Buttons buttons) {

//         if (bot.getBotConfig().isEnableAutoVerify() && buttons != null && !buttons.getButtonList().isEmpty() && buttons.getButtonList().size() > 13) {
//             String qq = message.split("at_tinyid=")[1].split("\\)")[0];
//             String verifyQQ = groupManager.autoVerifyQQ.get(qq);
//             if (verifyQQ != null) {
//                 String regex = "https?://[^\\s\\)]+";
//                 Pattern pattern = Pattern.compile(regex);
//                 Matcher matcher = pattern.matcher(message);
//                 botButtonMap.put(bot.getBotId(), buttons);
//                 while (matcher.find()) {
//                     buttons.setImageUrl(matcher.group());
//                     buttons.setImageText(messageChain.get(messageChain.size() - 1).toString());
//                 }
//                 if (codeUrlMap.get(Long.parseLong(verifyQQ)) != null) {
//                     RecognitionResult codeData = codeUrlMap.get(Long.parseLong(verifyQQ));
//                     if (buttons.getImageUrl().equals(codeData.getUrl())) {
//                         saveErrorImage(codeData.getUrl(), codeData.getTitle(), codeData.getResult());
// //                        sendFailMessage(bot, message, buttons, messageChain, codeData.getResult());
//                         errorClickButton(buttons, bot, group, codeData.result,verifyQQ);
//                         bot.getGroup(group.getGroupId()).sendMessage((new MessageChain()).at(verifyQQ).text("自动验证失败，请手动验证"));
//                         return;
//                     }
//                 }

//                 List<Button> buttonList = buttons.getButtonList();
//                 StringBuilder buttonBuilder = new StringBuilder();
//                 for (int i = 0; i < buttonList.size(); i++) {
//                     Button button = buttonList.get(i);
//                     if (GuessIdiom.getEmoji(button.getLabel()) != null) {
//                         buttonBuilder.append(" ").append(GuessIdiom.getEmoji(button.getLabel())).append(" ");
//                     } else {
//                         buttonBuilder.append(" ").append(button.getLabel()).append(" ");
//                     }

//                 }

//                 customPool.submit(new Runnable() {
//                     public void run() {
//                         if (StringUtils.isNotBlank(shituApiUrl)) {
//                             autoVerifyCode(bot, group, messageChain, message, messageId, buttons, verifyQQ);
//                         }

//                     }
//                 });
//             }


//         }
//     }


    private void autoVerifyCode(Bot bot, Group group, MessageChain messageChain, String message, Integer messageId, Buttons buttons, String verifyQQ) {
        try {
            List<Button> buttonList = buttons.getButtonList();
            StringBuilder buttontextBuilder = new StringBuilder();

            for (Button button : buttonList) {
                buttontextBuilder.append(button.getLabel()).append("|");
            }

            RecognitionResult recognitionResult = recognizeVerifyCode(buttons.getImageUrl(), buttons.getImageText());
            if (StringUtils.isNotBlank(verifyQQ)) {
                codeUrlMap.put(Long.parseLong(verifyQQ), recognitionResult);
            } else {
                codeUrlMap.put(bot.getBotId(), recognitionResult);
            }
//            if (StringUtils.isNotBlank(verifyQQ)) {
//                group.sendMessage((new MessageChain()).text(result));
//            }
            if (recognitionResult.result.contains("识别失败")) {
                verifyFailSendMessage(bot, group, messageChain, message, messageId, buttons, verifyQQ, recognitionResult);
                return;
            }
            boolean isSuccess = false;

            if (StringUtils.isNotBlank(recognitionResult.answer)) {
                String text = recognitionResult.answer;
                if (text.contains("序号")) {
                    text = text.replaceAll("序号", "");

                    if (StringUtils.isNumeric(text)) {
                        if (!buttons.getButtonList().isEmpty()) {
                            if (Integer.parseInt(text) <= buttons.getButtonList().size()) {
                                Button button = buttons.getButtonList().get(Integer.parseInt(text) - 1);
                                isSuccess = true;
                                if (StringUtils.isEmpty(verifyQQ)) {
                                    bot.clickKeyboardButton(group.getGroupId(), buttons.getBotAppid(), button.getId(), button.getData(), buttons.getMsgSeq());
                                } else {
                                    bot.getGroup(group.getGroupId()).sendMessage((new MessageChain()).at(verifyQQ).text("点击序号" + text));
                                }

                            }
                        }
                    }
                } else {
                    String emojiText = GuessIdiom.getEmoji(text);
                    String matchText = StringUtils.isNotBlank(emojiText) ? emojiText : text;

                    for (Button button : buttons.getButtonList()) {
                        if (!matchText.equals(button.getLabel())) continue;

                        isSuccess = true;
                        if (StringUtils.isEmpty(verifyQQ)) {
                            bot.clickKeyboardButton(
                                    group.getGroupId(),
                                    buttons.getBotAppid(),
                                    button.getId(),
                                    button.getData(),
                                    buttons.getMsgSeq()
                            );
                        } else {
                            bot.getGroup(group.getGroupId())
                                    .sendMessage(new MessageChain().at(verifyQQ).text("点击" + text));
                        }
                        break;
                    }


                }

            }
            if (!isSuccess) {

                verifyFailSendMessage(bot, group, messageChain, message, messageId, buttons, verifyQQ, recognitionResult);
            } else {

                groupManager.verifyCount.addCorrect();
            }
        } catch (Exception e) {
            RecognitionResult codeData = codeUrlMap.get(Long.parseLong(verifyQQ));
            saveErrorImage(codeData.getUrl(), codeData.getTitle(), codeData.getResult());
            testService.showButtonMsg(bot, group, messageId, message, buttons, messageChain);
            e.printStackTrace();
        }
    }

    /**
     * 识别错误后尝试随机点击一个按钮
     */
    private void errorClickButton(Buttons buttons, Bot bot, Group group, String resultText, String verifyQQ) {

        if (resultText.contains("加") && (resultText.length() == 11 || resultText.length() == 10)) {
            Button maxNumberButton = null;
            for (Button button : buttons.getButtonList()) {
                if (StringUtils.isNumeric(button.getLabel())) {
                    if (maxNumberButton == null) {
                        maxNumberButton = button;
                    } else {
                        if (Integer.parseInt(button.getLabel()) > Integer.parseInt(maxNumberButton.getLabel())) {
                            maxNumberButton = button;
                        }
                    }
                }

            }
            if (maxNumberButton != null) {
                logger.info("点击最大数字按钮：" + maxNumberButton.getLabel());
                if(!StringUtils.isEmpty(verifyQQ)){
                    bot.getGroup(group.getGroupId()).sendMessage((new MessageChain()).at(verifyQQ).text("点击" + maxNumberButton.getLabel()));
                }else{
                    bot.clickKeyboardButton(
                            group.getGroupId(),
                            buttons.getBotAppid(),
                            maxNumberButton.getId(),
                            maxNumberButton.getData(),
                            buttons.getMsgSeq()
                    );
                }

            }

        }else{
            if(bot.getBotConfig().getAutoVerifyModel() == 2 && StringUtils.isEmpty(verifyQQ)){
                bot.clickKeyboardButton(
                        group.getGroupId(),
                        buttons.getBotAppid(),
                        buttons.getButtonList().get(0).getId(),
                        buttons.getButtonList().get(0).getData(),
                        buttons.getMsgSeq()
                );
            }

        }

    }


    public RecognitionResult recognizeVerifyCode(String imageUrl, String title) {
        System.out.println("开始识别验证码: " + imageUrl);

        String answer = "";
        String resultText = "";
        RecognitionResult recognitionResult = new RecognitionResult();
        try {
            if ("http://113.45.9.127:8000/".equals(shituApiUrl)) {
                recognitionResult = callShituAPI(shituApiUrl, imageUrl);
            } else {
                recognitionResult = callShituAPI(shituApiUrl, imageUrl, title, "", "1");
            }
            recognitionResult.url = imageUrl;
            recognitionResult.title = title;
            resultText = recognitionResult.result;

            resultText = Utils.extractAfterPleaseClick(resultText);
            if (StringUtils.isNotBlank(title) && StringUtils.isNotBlank(resultText)) {
                if (title.contains("请问深色文字中字符") && title.contains("出现了几次")) {
                    Character targetChar = extractTargetChar(title);
                    if (targetChar != null) {
                        answer = String.valueOf(countCharOccurrences(resultText, targetChar));
                    } else {
                        System.out.println("识别失败，请手动点击验证码" + title);
                        answer = "识别失败，请手动点击验证码";
//                        recognitionResult.answer = "识别失败，请手动点击验证码";
//                        return resultText;
                    }
                } else if (title.contains("请按照深色文字的题目点击对应的答案")) {

                    if (resultText.contains("点击") && resultText.length() < 7) {

                        if (resultText.length() == 4 || resultText.length() == 5 ) {
                            char secondLastChar = resultText.charAt(resultText.length() - 2);
                            char lastChar = resultText.charAt(resultText.length() - 1);
                            String secondLastStr = String.valueOf(secondLastChar);
                            String lastStr = String.valueOf(lastChar);

                            if ("沙".equals(secondLastStr) || "漏".equals(lastStr)) {
                                answer = "沙漏";
                            } else if ("葡".equals(secondLastStr) || "萄".equals(lastStr) ||
                                    "萄".equals(secondLastStr) || "葡".equals(lastStr)) {
                                answer = "葡萄";
                            } else if ("书".equals(secondLastStr) || "本".equals(lastStr)) {
                                answer = "书本";
                            } else if ("图".equals(secondLastStr) || "钉".equals(lastStr)) {
                                answer = "图钉";
                            } else if ("西".equals(secondLastStr) || "瓜".equals(lastStr)) {
                                answer = "西瓜";
                            } else if ("汽".equals(secondLastStr) || "车".equals(lastStr)) {
                                answer = "汽车";
                            } else if ("鲸".equals(secondLastStr) || "鱼".equals(lastStr)) {
                                answer = "鲸鱼";
                            } else if (resultText.length() == 4 && "鸡".equals(lastStr)) {
                                answer = "鸡";
                            } else if (resultText.length() == 5 && "脑".equals(lastStr)) {
                                answer = "电脑";
                            } else if (resultText.length() == 5 && ("池".equals(lastStr)||"减".equals(lastStr))) {
                                answer = "电池";
                            } else if (resultText.length() == 5 && "电".equals(secondLastStr) && "沙".equals(lastStr)) {
                                answer = "电池";
                            } else if (resultText.length() == 5 && "电".equals(secondLastStr)) {
                                answer = "电池";
                            } else if (resultText.length() == 5 && ("苹".equals(secondLastStr) || "果".equals(lastStr))) {
                                answer = "苹果";
                            } else if (resultText.length() == 4) {
                                answer = lastStr;
                            } else {
                                answer = secondLastStr + lastStr;
                            }
                        } else {
                            answer = resultText.substring("请点击".length()).trim();
                            if (answer.equals("漏萄")) answer = "葡萄";
                        }
                    } else if (recognitionResult.emojiList.size() == 3) {
                        int idx = 0;
                        Matcher matcher = Pattern.compile("(\\d+|[一二三四五六七八九])").matcher(resultText);
                        if (matcher.find()) {
                            if (Integer.parseInt(matcher.group()) < 4) {
                                idx = Integer.parseInt(matcher.group()) - 1;
                            }
                            if (Integer.parseInt(matcher.group()) == 4 || Integer.parseInt(matcher.group()) == 7) {
                                idx = 0;
                            }
                        }
                        if (idx < recognitionResult.emojiList.size()) {
                            answer = recognitionResult.emojiList.get(idx);
                        }
                    } else if (resultText.contains("表") && resultText.contains("情")) {
                        int idx = 1;
                        Matcher matcher = Pattern.compile("(\\d+|[一二三四五六七八九])").matcher(resultText);
                        if (recognitionResult.emojiList.isEmpty()) {
                            if (matcher.find()) {
                                String match = matcher.group(1);
                                if (match.matches("\\d+")) {
                                    idx = Integer.parseInt(match);
                                } else {
                                    idx = CHINESE_NUMBERS.get(match); // 中文转数字
                                }
                            }
                            if (idx == 7) {
                                idx = 1;
                            }
                            answer = "序号" + idx;
                        } else if (recognitionResult.emojiList.size() == 3) {
                            if (matcher.find()) {
                                if (Integer.parseInt(matcher.group()) < 4) {
                                    idx = Integer.parseInt(matcher.group()) - 1;
                                }
                                if (Integer.parseInt(matcher.group()) == 4 || Integer.parseInt(matcher.group()) == 7) {
                                    idx = 0;
                                }
                            }
                            if (idx < recognitionResult.emojiList.size()) {
                                answer = recognitionResult.emojiList.get(idx);
                            }
                        }
                    } else if (resultText.contains("加") && (resultText.length() == 11 || resultText.length() == 10)) {
                        //                        resultText = resultText.replaceAll("点", "加");
                        answer = String.valueOf(calculate(resultText, "加"));
                    } else if (resultText.contains("减")) {
                        answer = String.valueOf(calculate(resultText, "减"));
                    } else if (resultText.contains("乘")) {
                        resultText = resultText.replaceAll("结乘", "结果");
                        resultText = resultText.replaceAll("乘点击", "请点击");
                        answer = String.valueOf(calculate(resultText, "乘"));
                    }
                } else if (title.contains("请问图中深色文字中包含几个字符")) {
                    answer = String.valueOf(resultText.length());
                }
            }
            if (StringUtils.isEmpty(answer)) {

                answer = "识别失败，请手动点击验证码";
            }

            recognitionResult.url = imageUrl;
            recognitionResult.answer = answer;
            recognitionResult.result = resultText;
            return recognitionResult;
//            return resultText + "\n正确答案：" + answer;
        } catch (Exception e) {
            e.printStackTrace();
            recognitionResult.answer = "识别失败，请手动点击验证码";
            return recognitionResult;
        }
    }


    private void verifyFailSendMessage(Bot bot, Group group, MessageChain messageChain, String message, Integer messageId, Buttons buttons, String verifyQQ, RecognitionResult recognitionResult) {
//        saveErrorImage(buttons.getImageUrl());
        StringBuilder stringBuilder = new StringBuilder();


        stringBuilder.append("识别成功率：" + groupManager.verifyCount.getAccuracy() + "%")
                .append("\n")
                .append("识别结果：").append(recognitionResult.result)
                .append("\n");
        if (recognitionResult.emojiList != null && !recognitionResult.emojiList.isEmpty()) {
            stringBuilder.append("识别表情：");
            for (String emoji : recognitionResult.emojiList) {
                stringBuilder.append(emoji);
            }
            stringBuilder.append("\n");
        }
        stringBuilder.append("正确答案："+recognitionResult.answer);
        if (StringUtils.isEmpty(verifyQQ)) {
            if (bot.getBotConfig().getAutoVerifyModel() != 2) {

                if (xxGroupId > 0){
                    testService.showButtonMsg(bot, group, messageId, message, buttons, messageChain);
                    bot.getGroup(xxGroupId).sendMessage((new MessageChain()).at(bot.getBotConfig().getMasterQQ() + "").text("自动验证失败，请手动验证"));
                }else{
                    bot.getGroup(bot.getBotConfig().getGroupId()).sendMessage((new MessageChain()).at(bot.getBotConfig().getMasterQQ() + "").text("自动验证失败，请手动验证"));
                }

            } else {
                errorClickButton(buttons, bot, group, recognitionResult.result,"");
            }
            RecognitionResult codeData = codeUrlMap.get(bot.getBotId());
            saveErrorImage(codeData.getUrl(), codeData.getTitle(), codeData.getResult());
        } else {
            RecognitionResult codeData = codeUrlMap.get(Long.parseLong(verifyQQ));
            saveErrorImage(codeData.getUrl(), codeData.getTitle(), codeData.getResult());
            bot.getGroup(group.getGroupId()).sendMessage((new MessageChain()).at(verifyQQ).text("自动验证失败，请手动验证"));
        }
        sendFailMessage(bot, message, buttons, messageChain, stringBuilder.toString());
    }

    public void sendFailMessage(Bot bot, String message, Buttons buttons, MessageChain messageChain, String result) {
        // if (buttons != null && !buttons.getButtonList().isEmpty() && buttons.getButtonList().size() > 13) {
        //     String regex = "https?://[^\\s\\)]+";
        //     Pattern pattern = Pattern.compile(regex);
        //     Matcher matcher = pattern.matcher(message);

        //     while (matcher.find()) {
        //         buttons.setImageUrl(matcher.group());
        //         buttons.setImageText(((Message) messageChain.get(messageChain.size() - 1)).toString());
        //     }

        //     StringBuilder buttonBuilder = new StringBuilder();
        //     buttonBuilder.append(buttons.getImageText());
        //     buttonBuilder.append("\n");
        //     buttonBuilder.append(result);
        //     MessageChain messageChain1 = new MessageChain();
        //     messageChain1.text("\n").image(buttons.getImageUrl()).text(buttonBuilder.toString());
        //     bot.sendPrivateMessage(bot.getBotId(), messageChain1);
        // }

    }

    public void saveErrorImage(String url, String question, String answer) {

        if ("http://113.45.9.127:8000/".equals(shituApiUrl)) {
            // 下载网络图片
            byte[] imageBytes = null;
            try {
                imageBytes = downloadImageBytes(url);
                // 上传到服务器
                String hash = md5(url);
                String returnMsg = uploadMultipart(shituApiUrl + "report_error", imageBytes, "error_" + hash + ".jpg", question, answer,url);
                RecognitionResult result = JSON.parseObject(returnMsg, RecognitionResult.class);
                if (result.msg.contains("已保存")) {
                    groupManager.verifyCount.addError();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            try {
                // 生成 URL 的 MD5 作为唯一文件名
                String hash = md5(url);
                // 确保目录存在
                File dir = new File("errorPic");
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                // 如果已经存在同名文件，则直接返回
                File file = new File(dir, "error_" + hash + ".jpg");
                if (file.exists()) {
                    return;
                }

                // 下载图片
                BufferedImage img = downloadImage(url);

                // 检查文件数量，超过 500 则删除最旧的文件
                File[] files = dir.listFiles();
                if (files != null && files.length > 500) {
                    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                    files[0].delete();
                }

                // 保存图片
                ImageIO.write(img, "jpg", file);
                groupManager.verifyCount.addError();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    // URL 生成 MD5
    private String md5(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 从 URL 下载图片并返回 BufferedImage
     */
    private BufferedImage downloadImage(String imageUrl) throws IOException {
        URL url = new URL(imageUrl);
        try (InputStream in = url.openStream()) {
            return ImageIO.read(in);
        }
    }

    public static Character extractTargetChar(String q) {
        int s = q.indexOf("“"), e = q.indexOf("”");
        if (s == -1 || e == -1) {
            s = q.indexOf("\"");
            e = q.lastIndexOf("\"");
        }
        return (s != -1 && e > s + 1) ? q.charAt(s + 1) : null;
    }

    public static int countCharOccurrences(String t, char c) {
        int cnt = 0;
        for (char ch : t.toCharArray()) if (ch == c) cnt++;
        return cnt;
    }

    public static int calculate(String expr, String op) {
//        Pattern tokenPattern = Pattern.compile("(\\d+|[" + String.join("", CHINESE_NUMBERS.keySet()) + "]+|加|减|乘)");
        Pattern tokenPattern = Pattern.compile("(\\d+|[" + String.join("", CHINESE_NUMBERS.keySet()) + "])");
        Matcher matcher = tokenPattern.matcher(expr);

        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        if (tokens.isEmpty()) return -1;

        List<Integer> numbers = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        for (String token : tokens) {
            if (token.equals("加") || token.equals("减") || token.equals("乘")) {
                ops.add(token);
            } else {
                numbers.add(parseNumber(token));
            }
        }
        if (numbers.isEmpty()) return -1;


        int result = numbers.get(0);
        for (int i = 0; i < numbers.size() - 1; i++) {
            result = compute(result, op, numbers.get(i + 1));
        }
        return result;
    }

    private static int compute(int a, String op, int b) {
        switch (op) {
            case "加":
                return a + b;
            case "减":
                return a - b;
            case "乘":
                return a * b;
            default:
                throw new RuntimeException("不支持的运算符: " + op);
        }
    }

    private static int parseNumber(String s) {
        return s.matches("\\d+") ? Integer.parseInt(s) : CHINESE_NUMBERS.getOrDefault(s, -1);
    }


    public RecognitionResult callShituAPI(String shituApiUrl, String imageUrl, String titleText, String annu, String mode) {
        HttpURLConnection conn = null;
        annu = GuessIdiom.replaceEmojis(annu);
        try {
            String params = "URL=" + URLEncoder.encode(imageUrl, "UTF-8") + "&TEXT=" + URLEncoder.encode(titleText, "UTF-8") + "&Button=" + URLEncoder.encode(annu, "UTF-8") + "&Mode=" + URLEncoder.encode(mode, "UTF-8");
            URL url = new URL(shituApiUrl + "shitu");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/50.0.2661.87 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.8,en-US;q=0.5,en;q=0.3");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            OutputStream os = conn.getOutputStream();

            try {
                os.write(params.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable var24) {
                if (os != null) {
                    try {
                        os.close();
                    } catch (Throwable var23) {
                        var24.addSuppressed(var23);
                    }
                }

                throw var24;
            }

            if (os != null) {
                os.close();
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                logger.warn("API返回非200状态: {}", status);
                String[] var32 = new String[]{"服务错误", String.valueOf(status)};
                return new RecognitionResult(new ArrayList<>(), "服务错误");
            }

            StringBuilder responseBuilder = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));

            String line;
            try {
                while ((line = br.readLine()) != null) {
                    responseBuilder.append(line);
                }
            } catch (Throwable var25) {
                try {
                    br.close();
                } catch (Throwable var22) {
                    var25.addSuppressed(var22);
                }

                throw var25;
            }

            br.close();
            String var33 = responseBuilder.toString();
            logger.info("API响应: {}", var33);
            JSONObject jsonResponse = JSONObject.parseObject(var33);
            String message = jsonResponse.getString("message");
            String data = jsonResponse.getString("data");
//            return new String[] { message, data };
            List<String> emojiList = new ArrayList();
            if (data != null && !data.equals("空")) {
                if (data.length() >= 6) {
                    String segment1 = data.substring(0, 2);
                    String segment2 = data.substring(2, 4);
                    String segment3 = data.substring(4, 6);
                    emojiList.add(segment1);
                    emojiList.add(segment2);
                    emojiList.add(segment3);
                }
            }
            return new RecognitionResult(emojiList, message);
        } catch (SocketTimeoutException ste) {
            logger.warn("API读取超时: {}", ste.getMessage());
            String[] var29 = new String[]{"请求超时", "0"};
            return new RecognitionResult(new ArrayList<>(), "请求超时");
        } catch (Exception e) {
            logger.error("API调用异常: {}", e.getMessage());
            String[] url = new String[]{"请求异常", "0"};
            return new RecognitionResult(new ArrayList<>(), "请求异常");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }

        }

    }

    public RecognitionResult callShituAPI(String shituApiUrl, String imageUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(shituApiUrl + "recognize");
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);

            String jsonInputString = "{\"url\":\"" + imageUrl + "\"}";

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int code = conn.getResponseCode();
            InputStream inputStream = (code >= 200 && code < 300) ?
                    conn.getInputStream() : conn.getErrorStream();

            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, "utf-8"))) {
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
            }
            logger.info("API响应: {}", response);
            RecognitionResult result = JSON.parseObject(response.toString(), RecognitionResult.class);
            return result;
        } catch (SocketTimeoutException ste) {
            logger.warn("API读取超时: {}", ste.getMessage());
            String[] var29 = new String[]{"请求超时", "0"};
            return new RecognitionResult(new ArrayList<>(), "请求超时");
        } catch (Exception e) {
            logger.error("API调用异常: {}", e.getMessage());
            String[] url = new String[]{"请求异常", "0"};
            return new RecognitionResult(new ArrayList<>(), "请求异常");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }

        }

    }

    // 从网络下载图片字节
    private static byte[] downloadImageBytes(String imageUrl) throws IOException {
        try (InputStream in = new URL(imageUrl).openStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    // 发送 multipart/form-data 请求
    private static String uploadMultipart(String serverUrl, byte[] imageBytes, String fileName, String question, String answer,String imageUrl) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(serverUrl);

            HttpEntity entity = MultipartEntityBuilder.create()
                    .addBinaryBody("image", imageBytes, ContentType.IMAGE_JPEG, fileName)
                    .addTextBody("question", question, ContentType.TEXT_PLAIN.withCharset("UTF-8"))
                    .addTextBody("answer", answer, ContentType.TEXT_PLAIN.withCharset("UTF-8"))
                    .addTextBody("imageUrl", imageUrl, ContentType.TEXT_PLAIN.withCharset("UTF-8"))
                    .build();

            post.setEntity(entity);

            try (CloseableHttpResponse response = client.execute(post)) {
                return EntityUtils.toString(response.getEntity(), "UTF-8");
            }
        }
    }

    private static String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
}
