package top.sshh.qqbot.service;

import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionContentPart;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.service.ArkService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Component
public class AutoVerifyCode {
    private final String apiKey;
    private final String model;
    private final ArkService service;

    // 使用构造器注入替代字段注入
    public AutoVerifyCode(
            @Value("${apiKey:''}") String apiKey,
            @Value("${model:''}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.service = new ArkService(apiKey);
    }

    // 将识别功能提取为独立方法
    public String recognizeVerifyCode(String imageUrl) {
        System.out.println("----- image input -----");

        try {
            final List<ChatMessage> messages = new ArrayList<>();
            final List<ChatCompletionContentPart> multiParts = new ArrayList<>();

            multiParts.add(ChatCompletionContentPart.builder()
                    .type("text")
                    .text("识别图片中的深色文字，并且给出结果")
                    .build());

            multiParts.add(ChatCompletionContentPart.builder()
                    .type("image_url")
                    .imageUrl(new ChatCompletionContentPart.ChatCompletionContentPartImageURL(imageUrl))
                    .build());

            final ChatMessage userMessage = ChatMessage.builder()
                    .role(ChatMessageRole.USER)
                    .multiContent(multiParts)
                    .build();

            messages.add(userMessage);

            ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                    .model(model)
                    .messages(messages)
                    .build();

            StringBuilder result = new StringBuilder();
            service.createChatCompletion(chatCompletionRequest)
                    .getChoices()
                    .forEach(choice -> result.append(choice.getMessage().getContent()));

            return result.toString();
        } catch (Exception e) {
            throw new RuntimeException("验证码识别失败: " + e.getMessage(), e);
        }
    }

    // 示例使用方法
    @PostConstruct
    public void init() {
        String testUrl = "https://qqbot.ugcimg.cn/102074059/dee967b60b087b408885838a315cfd14fdf899d6/ec1e96cda9ccf559e6e59059c7dc20e5";
        try {
            String result = recognizeVerifyCode(testUrl);
            System.out.println("识别结果: " + result);
        } catch (Exception e) {
            System.err.println("初始化测试失败: " + e.getMessage());
        }
    }
}
