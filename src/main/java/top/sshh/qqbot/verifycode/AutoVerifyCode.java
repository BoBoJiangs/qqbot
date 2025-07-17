package top.sshh.qqbot.service;


import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.core.*;
import com.zhuangxv.bot.message.MessageChain;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.GuessIdiom;
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
public class AutoVerifyCode {
    private static final Logger logger = LoggerFactory.getLogger(AutoVerifyCode.class);
    @Value("${xxGroupId:0}")
    private Long xxGroupId;
    @Autowired
    public YoloCaptchaRecognizer yoloCaptchaRecognizer;
    public Map<Long, String> codeUrlMap = new ConcurrentHashMap();





    // 示例使用方法
    @PostConstruct
    public void init() {

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void autoVerifyCode(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId, Buttons buttons) {
        boolean isSelfGroup = (group.getGroupId() == bot.getBotConfig().getGroupId() || xxGroupId == group.getGroupId());
        if (isSelfGroup && buttons != null && !buttons.getButtonList().isEmpty() && buttons.getButtonList().size() > 13) {
            String regex = "https?://[^\\s\\)]+";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                buttons.setImageUrl(matcher.group());
                buttons.setImageText(messageChain.get(messageChain.size() - 1).toString());
            }
//            buttons.setGroupId(group.getGroupId());
            if(codeUrlMap.get(bot.getBotId())!=null){
                String codeUrl = codeUrlMap.get(bot.getBotId());
                if(buttons.getImageUrl().equals(codeUrl) ){
                    yoloCaptchaRecognizer.saveErrorImage(codeUrl);
                    bot.getGroup(xxGroupId).sendMessage((new MessageChain()).at(bot.getBotConfig().getMasterQQ()+"").text("自动验证失败，请手动验证"));
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
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        String result = yoloCaptchaRecognizer.recognizeVerifyCode(buttons.getImageUrl(),buttons.getImageText());
                        group.sendMessage((new MessageChain()).reply(messageId).text("识别结果: " + result));
                        boolean isSuccess = false;
                        if(result.contains("正确答案：")){
                            String text = result.split("正确答案：")[1];
                            if (text.contains("序号")){
                                text = text.replaceAll("序号", "");
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
                                if (GuessIdiom.getEmoji(text) != null) {
                                    text = GuessIdiom.getEmoji(text);
                                }
                                for (Button button : buttonList) {
                                    if (text.equals(button.getLabel())) {
                                        isSuccess = true;
                                        bot.clickKeyboardButton(group.getGroupId(), buttons.getBotAppid(), button.getId(), button.getData(), buttons.getMsgSeq());
                                        break;
                                    }
                                }
                            }

                        }
                        if(!isSuccess){
                            yoloCaptchaRecognizer.saveErrorImage(buttons.getImageUrl());
                            bot.getGroup(xxGroupId).sendMessage((new MessageChain()).at(bot.getBotConfig().getMasterQQ()+"").text("自动验证失败，请手动验证"));
                        }
                    } catch (Exception e) {
                        yoloCaptchaRecognizer.saveErrorImage(buttons.getImageUrl());
                        e.printStackTrace();
                    }

                }
            });


        }
    }
}
