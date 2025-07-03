//package top.sshh.qqbot.service;
//
//import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionContentPart;
//import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
//import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
//import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
//import com.volcengine.ark.runtime.service.ArkService;
//import com.zhuangxv.bot.annotation.GroupMessageHandler;
//import com.zhuangxv.bot.core.*;
//import com.zhuangxv.bot.message.MessageChain;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Component;
//import top.sshh.qqbot.data.GuessIdiom;
//
//import javax.annotation.PostConstruct;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//@Component
//public class AutoVerifyCode {
//    private static final Logger logger = LoggerFactory.getLogger(AutoVerifyCode.class);
//    private final String apiKey;
//    private final String model;
//    private final ArkService service;
//    @Value("${xxGroupId:0}")
//    private Long xxGroupId;
//    @Autowired
//    public TestService testService;
//    public Map<Long, String> codeUrlMap = new ConcurrentHashMap();
//
//    // 使用构造器注入替代字段注入
//    public AutoVerifyCode(
//            @Value("${apiKey:''}") String apiKey,
//            @Value("${model:''}") String model) {
//        this.apiKey = apiKey;
//        this.model = model;
//        this.service = new ArkService(apiKey);
//    }
//
//    // 将识别功能提取为独立方法
//    public String recognizeVerifyCode(Buttons buttons, String answer) {
//        System.out.println("----- image input -----");
//
//        try {
//            final List<ChatMessage> messages = new ArrayList<>();
//            final List<ChatCompletionContentPart> multiParts = new ArrayList<>();
//
//            multiParts.add(ChatCompletionContentPart.builder()
//                    .type("image_url")
//                    .imageUrl(new ChatCompletionContentPart.ChatCompletionContentPartImageURL(buttons.getImageUrl()))
//                    .build());
//            multiParts.add(ChatCompletionContentPart.builder()
//                    .type("text")
//                    .text("题目："+buttons.getImageText())
//                    .build());
//            multiParts.add(ChatCompletionContentPart.builder()
//                    .type("text")
//                    .text("结合图片以及题目信息，从以下答案中选出一个你认为正确的答案 " + answer )
//                    .text("回答结果按照 正确答案是：1 这种格式输出")
//                    .build());
//            logger.info(buttons.getImageText());
//            logger.info(buttons.getImageUrl());
//
//            logger.info(answer);
//            final ChatMessage userMessage = ChatMessage.builder()
//                    .role(ChatMessageRole.USER)
//                    .multiContent(multiParts)
//                    .build();
//
//            messages.add(userMessage);
//
//            ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
//                    .model(model)
//                    .messages(messages)
//                    .build();
//
//            StringBuilder result = new StringBuilder();
//            service.createChatCompletion(chatCompletionRequest)
//                    .getChoices()
//                    .forEach(choice -> result.append(choice.getMessage().getContent()));
//
//            return result.toString();
//        } catch (Exception e) {
//            throw new RuntimeException("验证码识别失败: " + e.getMessage(), e);
//        }finally {
//            service.shutdownExecutor();
//        }
//    }
//
//    // 示例使用方法
//    @PostConstruct
//    public void init() {
////        String testUrl = "https://qqbot.ugcimg.cn/102074059/dee967b60b087b408885838a315cfd14fdf899d6/ec1e96cda9ccf559e6e59059c7dc20e5";
////        try {
////            String result = recognizeVerifyCode(testUrl);
////            System.out.println("识别结果: " + result);
////        } catch (Exception e) {
////            System.err.println("初始化测试失败: " + e.getMessage());
////        }
//    }
//
//    @GroupMessageHandler(
//            senderIds = {3889001741L}
//    )
//    public void autoVerifyCode(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId, Buttons buttons) {
//        boolean isSelfGroup = (group.getGroupId() == bot.getBotConfig().getGroupId() || xxGroupId == group.getGroupId());
//        if (isSelfGroup && buttons != null && !buttons.getButtonList().isEmpty() && buttons.getButtonList().size() > 13) {
//            String regex = "https?://[^\\s\\)]+";
//            Pattern pattern = Pattern.compile(regex);
//            Matcher matcher = pattern.matcher(message);
//            while (matcher.find()) {
//                buttons.setImageUrl(matcher.group());
//                buttons.setImageText(messageChain.get(messageChain.size() - 1).toString());
//            }
//            if(codeUrlMap.get(bot.getBotId())!=null){
//                String codeUrl = codeUrlMap.get(bot.getBotId());
//                if(buttons.getImageUrl().equals(codeUrl) ){
//                    group.sendMessage((new MessageChain()).at(bot.getBotConfig().getMasterQQ()+"").text("自动验证失败，请手动验证"));
//                    return;
//                }
//            }
//
//            codeUrlMap.put(bot.getBotId(), buttons.getImageUrl());
//            List<Button> buttonList = buttons.getButtonList();
//            StringBuilder buttonBuilder = new StringBuilder();
//            for (int i = 0; i < buttonList.size(); i++) {
//                Button button = buttonList.get(i);
//                if (GuessIdiom.getEmoji(button.getLabel()) != null) {
//                    buttonBuilder.append(" ").append(GuessIdiom.getEmoji(button.getLabel())).append(" ");
//                } else {
//                    buttonBuilder.append(" ").append(button.getLabel()).append(" ");
//                }
//
//            }
//            try {
//                String result = recognizeVerifyCode(buttons, buttonBuilder.toString());
////                System.out.println("识别结果: " + result);
//                group.sendMessage((new MessageChain()).reply(messageId).text("识别结果: " + result));
//                if(result.startsWith("正确答案是：")){
//                    String text = result.substring("正确答案是：".length()).trim();
//                    for (Button button : buttonList) {
//                        if (text.equals(button.getLabel())) {
//                            bot.clickKeyboardButton(group.getGroupId(), buttons.getBotAppid(), button.getId(), button.getData(), buttons.getMsgSeq());
//                            break;
//                        }
//                    }
//                }
//
//            } catch (Exception e) {
//                System.err.println("初始化测试失败: " + e.getMessage());
//            }
//
//        }
//    }
//}
