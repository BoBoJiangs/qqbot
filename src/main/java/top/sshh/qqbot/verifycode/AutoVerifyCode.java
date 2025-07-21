package top.sshh.qqbot.verifycode;


import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.core.*;
import com.zhuangxv.bot.message.MessageChain;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.GuessIdiom;
import top.sshh.qqbot.service.GroupManager;
import top.sshh.qqbot.service.TestService;
import top.sshh.qqbot.service.utils.Utils;
import top.sshh.qqbot.verifycode.YoloCaptchaRecognizer;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static top.sshh.qqbot.service.GroupManager.customPool;

@Component
//@ConditionalOnProperty(name = "captcha.enabled", havingValue = "true", matchIfMissing = false)
public class AutoVerifyCode {
    @Value("${xxGroupId:0}")
    private Long xxGroupId;
    @Autowired
    public YoloCaptchaRecognizer yoloCaptchaRecognizer;
    public Map<Long, String> codeUrlMap = new ConcurrentHashMap();
    //用来判断其他QQ是否出现相同验证码，用来判断是否验证失败
    public Map<Long, String> codeUrlMap2 = new ConcurrentHashMap();
    @Autowired
    private GroupManager groupManager;
    @Autowired
    private TestService testService;


    // 示例使用方法
    @PostConstruct
    public void init() {

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void autoVerifyCode(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId, Buttons buttons) {
        boolean isSelfGroup = Utils.isAtSelf(bot, group, message);

        if (bot.getBotConfig().getAutoVerifyModel() !=0 && isSelfGroup && buttons != null && !buttons.getButtonList().isEmpty() && buttons.getButtonList().size() > 13) {
            String regex = "https?://[^\\s\\)]+";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                buttons.setImageUrl(matcher.group());
                buttons.setImageText(messageChain.get(messageChain.size() - 1).toString());
            }
            if (codeUrlMap.get(bot.getBotId()) != null) {
                String codeUrl = codeUrlMap.get(bot.getBotId());
                if (buttons.getImageUrl().equals(codeUrl)) {
                    yoloCaptchaRecognizer.saveErrorImage(codeUrl);
                    bot.getGroup(xxGroupId).sendMessage((new MessageChain()).at(bot.getBotConfig().getMasterQQ() + "").text("自动验证失败，请手动验证"));
                    return;
                }
            }

            codeUrlMap.put(bot.getBotId(), buttons.getImageUrl());
            List<Button> buttonList = buttons.getButtonList();
            StringBuilder buttonBuilder = new StringBuilder();
            for (int i = 0; i < buttonList.size(); i++) {
                Button button = buttonList.get(i);
                if (GuessIdiom.getEmoji(button.getLabel()) != null) {
                    buttonBuilder.append(" ").append(GuessIdiom.getEmoji(button.getLabel())).append(" ");
                } else {
                    buttonBuilder.append(" ").append(button.getLabel()).append(" ");
                }

            }
//            autoVerifyCode(bot, group, messageId, buttons,"");
            autoVerifyCode(bot, group,messageChain,message, messageId, buttons,"");
//            customPool.submit(new Runnable() {
//                public void run() {
//                    autoVerifyCode(bot, group, messageId, buttons,"");
//
//                }
//            });


        }
    }


    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 辅助识别验证码(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId, Buttons buttons) {

        if (bot.getBotConfig().isEnableAutoVerify() && buttons != null && !buttons.getButtonList().isEmpty() && buttons.getButtonList().size() > 13) {
            String qq = message.split("at_tinyid=")[1].split("\\)")[0];
            String verifyQQ = groupManager.autoVerifyQQ.get(qq);
            if(verifyQQ!=null){
                String regex = "https?://[^\\s\\)]+";
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(message);
                while (matcher.find()) {
                    buttons.setImageUrl(matcher.group());
                    buttons.setImageText(messageChain.get(messageChain.size() - 1).toString());
                }
                if (codeUrlMap2.get(Long.parseLong(verifyQQ)) != null) {
                    String codeUrl = codeUrlMap2.get(Long.parseLong(verifyQQ));
                    if (buttons.getImageUrl().equals(codeUrl)) {
                        groupManager.verifyCount.errorCount++;
                        yoloCaptchaRecognizer.saveErrorImage(codeUrl);
                        bot.getGroup(group.getGroupId()).sendMessage((new MessageChain()).at(verifyQQ).text("自动验证失败，请手动验证"));
                        return;
                    }
                }

                codeUrlMap2.put(Long.parseLong(verifyQQ), buttons.getImageUrl());
                List<Button> buttonList = buttons.getButtonList();
                StringBuilder buttonBuilder = new StringBuilder();
                for (int i = 0; i < buttonList.size(); i++) {
                    Button button = buttonList.get(i);
                    if (GuessIdiom.getEmoji(button.getLabel()) != null) {
                        buttonBuilder.append(" ").append(GuessIdiom.getEmoji(button.getLabel())).append(" ");
                    } else {
                        buttonBuilder.append(" ").append(button.getLabel()).append(" ");
                    }

                }
                autoVerifyCode(bot, group,messageChain,message, messageId, buttons,verifyQQ);
//                customPool.submit(new Runnable() {
//                    public void run() {
//
//                    }
//                });
            }



        }
    }


    private void autoVerifyCode(Bot bot, Group group, MessageChain messageChain, String message,  Integer messageId, Buttons buttons,String verifyQQ) {
        try {
            String result = yoloCaptchaRecognizer.recognizeVerifyCode(buttons.getImageUrl(), buttons.getImageText());
            result = "识别结果: " + result;
            result = "识别成功率：" + groupManager.verifyCount.getAccuracy() + "%"+ "\n" + result ;
            if(bot.getBotConfig().getAutoVerifyModel() == 2){
                group.sendMessage((new MessageChain()).text( result));
            }
            if(result.contains("识别失败")){

                groupManager.verifyCount.errorCount++;
                yoloCaptchaRecognizer.saveErrorImage(buttons.getImageUrl());
                if(StringUtils.isEmpty(verifyQQ)){
                    testService.showButtonMsg(bot, group, messageId, message, buttons, messageChain);
                    bot.getGroup(xxGroupId).sendMessage((new MessageChain()).at(bot.getBotConfig().getMasterQQ() + "").text("自动验证失败，请手动验证"));
                }else{
                    bot.getGroup(group.getGroupId()).sendMessage((new MessageChain()).at(verifyQQ).text("自动验证失败，请手动验证"));
                }
                return;
            }
            boolean isSuccess = false;

            if (result.contains("正确答案：")) {
                String text = "";
                String[] parts = result.split("正确答案：", 2); // 限制最多分两段
                if (parts.length > 1) {
                    text = parts[1];
                }
                if (text.contains("序号")) {
                    text = text.replaceAll("序号", "");
                    if(StringUtils.isEmpty(verifyQQ)){
                        if (StringUtils.isNumeric(text)) {
                            if (!buttons.getButtonList().isEmpty()) {
                                if (Integer.parseInt(text) <= buttons.getButtonList().size()) {
                                    Button button = buttons.getButtonList().get(Integer.parseInt(text) - 1);
                                    isSuccess = true;
                                    bot.clickKeyboardButton(group.getGroupId(), buttons.getBotAppid(), button.getId(), button.getData(), buttons.getMsgSeq());
                                }
                            }
                        }
                    }else{
                        isSuccess = true;
                        bot.getGroup(group.getGroupId()).sendMessage((new MessageChain()).at(verifyQQ).text("点击序号"+text));
                    }

                } else {
                    if(StringUtils.isEmpty(verifyQQ)){
                        if (GuessIdiom.getEmoji(text) != null) {
                            text = GuessIdiom.getEmoji(text);
                        }
                        for (Button button : buttons.getButtonList()) {
                            if (text.equals(button.getLabel())) {
                                isSuccess = true;
                                bot.clickKeyboardButton(group.getGroupId(), buttons.getBotAppid(), button.getId(), button.getData(), buttons.getMsgSeq());
                                break;
                            }
                        }
                    }else{
                        isSuccess = true;
                        bot.getGroup(group.getGroupId()).sendMessage((new MessageChain()).at(verifyQQ).text("点击"+text));
                    }

                }

            }
            if (!isSuccess) {
                groupManager.verifyCount.errorCount++;
                yoloCaptchaRecognizer.saveErrorImage(buttons.getImageUrl());
                testService.showButtonMsg(bot, group, messageId, message, buttons, messageChain);
                if(StringUtils.isEmpty(verifyQQ)){
                    bot.getGroup(xxGroupId).sendMessage((new MessageChain()).at(bot.getBotConfig().getMasterQQ() + "").text("自动验证失败，请手动验证"));
                }else{
                    bot.getGroup(group.getGroupId()).sendMessage((new MessageChain()).at(verifyQQ).text("自动验证失败，请手动验证"));
                }
            }else{

                groupManager.verifyCount.correctCount++;
            }
        } catch (Exception e) {
            groupManager.verifyCount.errorCount++;
            yoloCaptchaRecognizer.saveErrorImage(buttons.getImageUrl());
            testService.showButtonMsg(bot, group, messageId, message, buttons, messageChain);
            e.printStackTrace();
        }
    }
}
