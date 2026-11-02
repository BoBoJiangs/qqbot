package top.sshh.qqbot.verifycode;

import top.sshh.qqbot.service.storage.LocalDataFiles;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.*;
import com.zhuangxv.bot.message.Message;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
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
import top.sshh.qqbot.data.VerifyCount;
import top.sshh.qqbot.service.GroupManager;
import top.sshh.qqbot.service.TestService;
import top.sshh.qqbot.service.utils.CaptchaReminderSender;
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
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static top.sshh.qqbot.service.GroupManager.customPool;

@Component
// @ConditionalOnProperty(name = "captcha.enabled", havingValue = "true",
// matchIfMissing = false)
public class RemoteVerifyCode {
    private static final Logger logger = LoggerFactory.getLogger(RemoteVerifyCode.class);
    private static final long CLICK_RETRY_DELAY_MS = 1000L;
    private static final long VERIFICATION_FEEDBACK_TIMEOUT_MS = 5 * 60 * 1000L;
    @Value("${xxGroupId:0}")
    private Long xxGroupId;
    // 用来判断其他QQ是否出现相同验证码，用来判断是否验证失败
    public Map<Long, RecognitionResult> codeUrlMap = new ConcurrentHashMap();
    @Autowired
    private GroupManager groupManager;
    @Autowired
    private TestService testService;
    @Value("${captcha.shitu-api-url:#{null}}")
    private String shituApiUrl;
    @Value("${captcha.knn-retry-enabled:true}")
    private boolean knnRetryEnabled;
    private final Map<Long, String> knnRetriedImages = new ConcurrentHashMap<>();
    private final Set<Long> knnRetryInProgress = ConcurrentHashMap.newKeySet();
    private final Map<Long, VerificationAttempt> verificationAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> verificationFeedbackMessages = new ConcurrentHashMap<>();
    public Map<Long, Buttons> botButtonMap = new ConcurrentHashMap();
    public Map<Long, Integer> lastClickIndex = new ConcurrentHashMap();
    private final Map<Long, Long> lastServiceWarnAt = new ConcurrentHashMap<>();
    private final Random fallbackButtonRandom = new Random();
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

    @GroupMessageHandler(senderIds = { 3889001741L })
    public void autoVerifyCode(Bot bot, Group group, Member member, MessageChain messageChain, String message,
            Integer messageId, Buttons buttons) {
        recordVerificationFeedback(bot, group, messageChain, message, messageId);
        boolean isSelfGroup = Utils.isAtSelf(bot, group, message, xxGroupId);
        BotConfig botConfig = bot.getBotConfig();
        if (buttons == null || buttons.getButtonList() == null || buttons.getButtonList().isEmpty()) {
            // SnowLuma 等协议端不携带 NapCat 的 elements/msgSeq 结构，bot-core 注入不出按钮，
            // 此时从消息里的 inline_keyboard 段自行解析
            buttons = Utils.parseButtonsFromMessage(bot, message, messageId);
        }
        if (message.contains("请点击") && message.contains("表情")) {
            if (botConfig.isEnableSavePic() && buttons != null && !buttons.getButtonList().isEmpty()
                    && buttons.getButtonList().size() > 5) {
                getImageInfo(message, buttons, messageChain);
                saveErrorImage(buttons.getImageUrl());
                saveEmojiToFile(buttons.getButtonList());
            }

            if (bot.getBotConfig().getAutoVerifyModel() != 0 && isSelfGroup && buttons != null
                    && !buttons.getButtonList().isEmpty() && buttons.getButtonList().size() > 5) {
                String verifyQQ = message.split("at_tinyid=")[1].split("\\)")[0];
                if (bot.getBotId() == Long.parseLong(verifyQQ)) {
                    getImageInfo(message, buttons, messageChain);
                    botButtonMap.put(bot.getBotId(), buttons);
                    List<Button> buttonList = buttons.getButtonList();
                    if (codeUrlMap.get(Long.parseLong(verifyQQ)) != null) {
                        RecognitionResult codeData = codeUrlMap.get(Long.parseLong(verifyQQ));
                        if (buttons.getImageUrl().equals(codeData.getUrl())) {
                            final Buttons repeatedButtons = buttons;
                            customPool.submit(() -> retryRepeatedCaptcha(bot, group, messageChain, message,
                                    messageId, repeatedButtons, codeData));
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

                    final Buttons buttonsForVerify = buttons;
                    customPool.submit(new Runnable() {
                        public void run() {
                            if (StringUtils.isNotBlank(shituApiUrl)) {
                                autoVerifyCode(bot, group, messageChain, message, messageId, buttonsForVerify);
                            }
                        }
                    });
                }

            }

        }

    }

    private synchronized void saveEmojiToFile(List<Button> buttonList) {
        File emojiFile = new File("emoji_records.txt");
        Set<String> existingEmojis = new HashSet();
        if (LocalDataFiles.exists(emojiFile.toPath())) {
            String line;
            try (BufferedReader reader = LocalDataFiles.newBufferedReader(emojiFile.toPath())) {
                while ((line = reader.readLine()) != null) {
                    existingEmojis.add(line.trim());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try (BufferedWriter writer = LocalDataFiles.newBufferedWriter(emojiFile.toPath(), java.nio.file.StandardOpenOption.APPEND)) {
            // System.out.println("分割后标签数量: " + emojiCandidates.length);

            for (Button button : buttonList) {
                String trimmed = button.getLabel().trim();
                boolean isEmoji = this.isEmoji(trimmed);
                if (isEmoji) {
                    if (!existingEmojis.contains(trimmed)) {
                        writer.write(trimmed + "\n");
                        writer.flush();
                        existingEmojis.add(trimmed);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private boolean isEmoji(String str) {
        if (str.matches("\\d+")) {
            return false;
        } else if (str.length() != 1) {
            return true;
        } else {
            char c = str.charAt(0);
            return c < 19968 || c > '龥';
        }
    }

    private void autoVerifyCode(Bot bot, Group group, MessageChain messageChain, String message, Integer messageId,
            Buttons buttons) {
        autoVerifyCode(bot, group, messageChain, message, messageId, buttons, null);
    }

    private void autoVerifyCode(Bot bot, Group group, MessageChain messageChain, String message, Integer messageId,
            Buttons buttons, RecognitionResult suppliedResult) {
        VerificationAttempt attempt = null;
        try {
            if (!isCurrentCaptcha(bot, buttons)) { return; }
            List<Button> buttonList = buttons.getButtonList();
            StringBuilder buttontextBuilder = new StringBuilder();

            for (Button button : buttonList) {
                buttontextBuilder.append(button.getLabel()).append("|");
            }

            attempt = suppliedResult == null ? startVerificationAttempt(bot, group, buttons, messageId, "primary")
                    : currentVerificationAttempt(bot, buttons, "knn");
            RecognitionResult recognitionResult = suppliedResult != null ? suppliedResult
                    : recognizeVerifyCode(buttons.getImageUrl(), buttons.getImageText(), bot);
            if (attempt != null) { attempt.result = recognitionResult; }
            if (!isCurrentCaptcha(bot, buttons)) { abandonVerificationAttempt(attempt); return; }
            if (isCaptchaServiceBlocked(recognitionResult)) {
                abandonVerificationAttempt(attempt);
                sendCaptchaServiceBlockedMessage(bot, group, recognitionResult);
                return;
            }
            codeUrlMap.put(bot.getBotId(), recognitionResult);
            if (findAnswerButton(buttons, recognitionResult) == null) {
                recordUnusableRecognition(attempt, recognitionResult);
                RecognitionResult retried = tryKnnRetry(bot, group, buttons, messageId, recognitionResult);
                if (!isCurrentCaptcha(bot, buttons)) { return; }
                if (retried != null) {
                    attempt = currentVerificationAttempt(bot, buttons, "knn");
                    recognitionResult = retried;
                    if (attempt != null) { attempt.result = recognitionResult; }
                    if (isCaptchaServiceBlocked(recognitionResult)) {
                        abandonVerificationAttempt(attempt);
                        sendCaptchaServiceBlockedMessage(bot, group, recognitionResult);
                        return;
                    }
                    codeUrlMap.put(bot.getBotId(), recognitionResult);
                } else if (knnRetryInProgress.contains(bot.getBotId())) {
                    return;
                }
            }
            if (StringUtils.defaultString(recognitionResult.result).contains("识别失败")) {
                recordUnusableRecognition(attempt, recognitionResult);
                verifyFailSendMessage(bot, group, messageChain, message, messageId, buttons, recognitionResult);
                return;
            }
            boolean isSuccess = false;

            Button answerButton = findAnswerButton(buttons, recognitionResult);
            if (answerButton != null) {
                markVerificationSubmitted(attempt);
                clickCaptchaButtonWithRetry(bot, group, buttons, answerButton);
                isSuccess = true;
            }
            if (!isSuccess) {
                recordUnusableRecognition(attempt, recognitionResult);
                verifyFailSendMessage(bot, group, messageChain, message, messageId, buttons, recognitionResult);
            }
        } catch (Exception e) {
            abandonVerificationAttempt(attempt);
            if (Thread.currentThread().isInterrupted()) {
                logger.warn("验证码点击已中断，停止本次验证 botId={} groupId={}", bot.getBotId(), group.getGroupId());
                return;
            }
            logger.error("自动验证异常 botId={} groupId={}", bot.getBotId(), group.getGroupId(), e);
            RecognitionResult codeData = codeUrlMap.get(bot.getBotId());
            if (codeData != null && StringUtils.isBlank(codeData.getKnnReviewId())) {
                codeData.setClickFailed(true);
                saveErrorImage(codeData.getUrl(), codeData.getTitle(), codeData.getResult(), bot.getBotId());
            }
            testService.showButtonMsg(bot, group, messageId, message, buttons, messageChain);
        }
    }

    private Button findAnswerButton(Buttons buttons, RecognitionResult result) {
        if (result == null || StringUtils.defaultString(result.getResult()).contains("识别失败")
                || StringUtils.isBlank(result.getAnswer())) {
            return null;
        }
        String text = result.getAnswer();
        if (text.contains("序号")) {
            String number = text.replace("序号", "");
            if (StringUtils.isNumeric(number)) {
                try {
                    int index = Integer.parseInt(number) - 1;
                    return index >= 0 && index < buttons.getButtonList().size() ? buttons.getButtonList().get(index) : null;
                } catch (NumberFormatException ignored) { return null; }
            }
            return null;
        }
        String emoji = GuessIdiom.getEmoji(text);
        String match = StringUtils.isNotBlank(emoji) ? emoji : text;
        for (Button button : buttons.getButtonList()) {
            if (match.equals(button.getLabel()) || text.equals(button.getLabel())) { return button; }
        }
        return null;
    }

    private boolean isCurrentCaptcha(Bot bot, Buttons buttons) {
        if (bot.getBotConfig().getAutoVerifyModel() == 0) { return false; }
        Buttons latest = botButtonMap.get(bot.getBotId());
        return latest == null || Objects.equals(latest.getImageUrl(), buttons.getImageUrl());
    }

    private RecognitionResult tryKnnRetry(Bot bot, Group group, Buttons buttons, Integer messageId, RecognitionResult first) {
        if (!knnRetryEnabled || bot.getBotConfig().getAutoVerifyModel() == 0 || first == null
                || first.isKnnAttempted() || first.isClickFailed() || isCaptchaServiceBlocked(first)
                || requestedEmojiIndex(buttons.getImageText()) < 0) { return null; }
        String status = StringUtils.defaultString(first.getResult());
        if (status.contains("请求超时") || status.contains("请求异常") || status.contains("服务返回异常")) { return null; }
        long botId = bot.getBotId();
        if (!knnRetryInProgress.add(botId)) { return null; }
        try {
            first.setKnnAttempted(true);
            if (Objects.equals(knnRetriedImages.put(botId, buttons.getImageUrl()), buttons.getImageUrl())) { return null; }
            logger.info("首次验证码识别失败，使用KNN重试一次 botId={}", botId);
            VerificationAttempt attempt = startVerificationAttempt(bot, group, buttons, messageId, "knn");
            RecognitionResult retried = recognizeVerifyCodeWithKnn(buttons.getImageUrl(), buttons.getImageText(), bot);
            attempt.result = retried;
            retried.setKnnAttempted(true);
            if (!isCurrentCaptcha(bot, buttons)) { abandonVerificationAttempt(attempt); return null; }
            // Publish before releasing the in-progress flag, so a duplicate
            // callback cannot start the random fallback using the old result.
            codeUrlMap.compute(botId, (key, current) -> current != null
                    && Objects.equals(current.getUrl(), buttons.getImageUrl()) ? retried : current);
            return retried;
        } finally {
            knnRetryInProgress.remove(botId);
        }
    }

    private void retryRepeatedCaptcha(Bot bot, Group group, MessageChain chain, String message, Integer messageId,
            Buttons buttons, RecognitionResult first) {
        try {
            if (!isCurrentCaptcha(bot, buttons)) { return; }
            VerificationAttempt previous = verificationAttempts.get(bot.getBotId());
            if (previous != null && Objects.equals(previous.imageUrl, buttons.getImageUrl())) {
                synchronized (previous) {
                    // 同一条验证码消息重复投递不能视为新的失败回执。
                    if (messageId != null && Objects.equals(previous.messageId, messageId)
                            || Boolean.TRUE.equals(previous.outcome)) { return; }
                    if (previous.submitted) {
                        if (System.currentTimeMillis() - previous.submittedAt > VERIFICATION_FEEDBACK_TIMEOUT_MS) {
                            abandonVerificationAttempt(previous);
                        } else { finishVerificationAttempt(previous, false, "same_image_again"); }
                    }
                }
            }
            RecognitionResult retried = tryKnnRetry(bot, group, buttons, messageId, first);
            if (!isCurrentCaptcha(bot, buttons)) { return; }
            if (retried != null) {
                if (StringUtils.isNotBlank(first.getAnswer()) && first.getAnswer().equals(retried.getAnswer())) {
                    retried.setAnswer("");
                    retried.setResult("识别失败：KNN与已失败答案相同");
                }
                autoVerifyCode(bot, group, chain, message, messageId, buttons, retried);
            } else if (!knnRetryInProgress.contains(bot.getBotId()) && codeUrlMap.get(bot.getBotId()) == first) {
                verifyFailSendMessage(bot, group, chain, message, messageId, buttons, first);
                sendFailMessage(bot, message, buttons, chain, first);
            }
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) { return; }
            first.setClickFailed(true);
            logger.warn("验证码重试未完成，转人工处理 botId={} groupId={}", bot.getBotId(), group.getGroupId(), e);
            testService.showButtonMsg(bot, group, messageId, message, buttons, chain);
        }
    }

    private int requestedEmojiIndex(String title) {
        Matcher matcher = Pattern.compile("第(\\d+|[一二三四五六七八九十]+)个").matcher(StringUtils.defaultString(title));
        if (!matcher.find()) { return -1; }
        String token = matcher.group(1);
        try {
            if (StringUtils.isNumeric(token)) { return Integer.parseInt(token) - 1; }
            if (!token.contains("十")) { return CHINESE_NUMBERS.getOrDefault(token, 0) - 1; }
            String[] parts = token.split("十", -1);
            int tens = parts[0].isEmpty() ? 1 : CHINESE_NUMBERS.getOrDefault(parts[0], 0);
            int units = parts[1].isEmpty() ? 0 : CHINESE_NUMBERS.getOrDefault(parts[1], 0);
            return tens * 10 + units - 1;
        } catch (NumberFormatException ignored) { return -1; }
    }

    public RecognitionResult recognizeVerifyCodeWithKnn(String imageUrl, String title, Bot bot) {
        RecognitionResult result = callRecognitionAPI(shituApiUrl, imageUrl, bot.getBotId(), "knn");
        result.setUrl(imageUrl);
        result.setTitle(title);
        result.setKnnAttempted(true);
        if (isRecognitionServiceFailure(result)) { return result; }
        int index = requestedEmojiIndex(title);
        if (!"knn".equals(result.getRecognizer()) || result.getKnnAppliedIndices() == null
                || !result.getKnnAppliedIndices().contains(index) || index < 0
                || result.getEmojiList() == null || index >= result.getEmojiList().size()) {
            result.setAnswer("");
            result.setResult("识别失败：KNN未覆盖目标题目或结果置信不足，请检查服务及样本索引");
            logger.info("KNN未产出目标题目的可用答案 botId={} targetIndex={} recognizer={}",
                    bot.getBotId(), index, result.getRecognizer());
            return result;
        }
        result.setLastClickIndex(index);
        result.setAnswer(result.getEmojiList().get(index));
        logger.info("KNN重试得到可用答案 botId={} targetIndex={} label={}", bot.getBotId(), index, result.getAnswer());
        return result;
    }

    private VerificationAttempt startVerificationAttempt(Bot bot, Group group, Buttons buttons,
            Integer messageId, String recognizer) {
        VerifyCount counter = groupManager.verifyCount;
        VerificationAttempt attempt = new VerificationAttempt(bot.getBotId(), counter, recognizer, buttons.getImageUrl(),
                group.getGroupId(), messageId, counter.recordAttempt(recognizer));
        verificationAttempts.put(bot.getBotId(), attempt);
        return attempt;
    }

    private VerificationAttempt currentVerificationAttempt(Bot bot, Buttons buttons, String recognizer) {
        VerificationAttempt attempt = verificationAttempts.get(bot.getBotId());
        return attempt != null && Objects.equals(attempt.imageUrl, buttons.getImageUrl())
                && recognizer.equals(attempt.recognizer) ? attempt : null;
    }

    private boolean isRecognitionServiceFailure(RecognitionResult result) {
        String status = result == null ? "" : StringUtils.defaultString(result.getResult());
        return result == null || isCaptchaServiceBlocked(result) || status.contains("请求超时")
                || status.contains("请求异常") || status.contains("服务返回异常");
    }

    private void recordUnusableRecognition(VerificationAttempt attempt, RecognitionResult result) {
        if (isRecognitionServiceFailure(result)) { abandonVerificationAttempt(attempt); }
        else { finishVerificationAttempt(attempt, false, StringUtils.defaultString(result.getResult()).contains("KNN与已失败答案相同")
                ? "same_failed_answer" : "no_usable_answer"); }
    }

    private void markVerificationSubmitted(VerificationAttempt attempt) {
        if (attempt == null) { return; }
        synchronized (attempt) {
            if (!attempt.settled) {
                attempt.submitted = true;
                attempt.submittedAt = System.currentTimeMillis();
            }
        }
    }

    private void finishVerificationAttempt(VerificationAttempt attempt, boolean success, String reason) {
        if (attempt == null) { return; }
        synchronized (attempt) {
            if (attempt.settled) { return; }
            attempt.settled = true;
            attempt.outcome = success;
            attempt.counter.recordOutcome(attempt.recognizer, success, attempt.attemptedOn);
            logger.info("验证码识别结果已确认 botId={} groupId={} recognizer={} success={} reason={}",
                    attempt.botId, attempt.groupId, attempt.recognizer, success, reason);
            RecognitionResult result = attempt.result;
            if (!success && "knn".equals(attempt.recognizer) && result != null
                    && !isRecognitionServiceFailure(result) && StringUtils.isNotBlank(result.getKnnReviewId())) {
                customPool.submit(() -> reportKnnFailure(result.getKnnReviewId(), result.getTitle(),
                        result.getAnswer(), attempt.botId, reason));
            }
        }
    }

    private void abandonVerificationAttempt(VerificationAttempt attempt) {
        if (attempt == null) { return; }
        synchronized (attempt) { attempt.settled = true; }
    }

    /** 人工控制命令或旧识别流程接管后，不再把后续成功归因给本次模型。 */
    public void excludeExternalVerification(long botId, String imageUrl) {
        VerificationAttempt attempt = verificationAttempts.get(botId);
        if (attempt != null && Objects.equals(attempt.imageUrl, imageUrl)) { abandonVerificationAttempt(attempt); }
    }

    /** 只接受游戏机器人发给当前账号、当前群的验证回执；点击接口成功不代表答对。 */
    private void recordVerificationFeedback(Bot bot, Group group, MessageChain messageChain,
            String message, Integer messageId) {
        String chainText = Utils.getMessageText(messageChain);
        String text = StringUtils.defaultString(message) + "\n" + chainText;
        boolean mentioned = messageChain != null && messageChain.getMessageByType(AtMessage.class).stream()
                .anyMatch(at -> String.valueOf(bot.getBotId()).equals(at.getQq()));
        if (group == null || text.contains("请点击")
                || !mentioned && !Pattern.compile("(?:@|at_tinyid=|\\[CQ:at,qq=)" + bot.getBotId() + "(?!\\d)")
                        .matcher(text).find()) { return; }
        boolean failed = text.contains("验证码不正确") || text.contains("验证失败")
                || text.contains("验证未通过") || text.contains("未验证通过");
        boolean expired = text.contains("验证码已过期");
        boolean success = !failed && !expired && (text.contains("验证成功") || text.contains("验证通过")
                || text.contains("验证奖励") && text.contains("灵石")
                || isBareVerificationReward(message) || isBareVerificationReward(chainText));
        if (!failed && !success && !expired) { return; }
        VerificationAttempt attempt = verificationAttempts.get(bot.getBotId());
        if (attempt == null || attempt.groupId != group.getGroupId()) { return; }
        if (bot.getBotConfig().getAutoVerifyModel() == 0) { abandonVerificationAttempt(attempt); return; }
        synchronized (attempt) {
            if (attempt.settled || !attempt.submitted) { return; }
            long now = System.currentTimeMillis();
            if (now - attempt.submittedAt > VERIFICATION_FEEDBACK_TIMEOUT_MS) {
                abandonVerificationAttempt(attempt);
                return;
            }
            if (messageId != null) {
                verificationFeedbackMessages.entrySet().removeIf(entry -> now - entry.getValue() > VERIFICATION_FEEDBACK_TIMEOUT_MS);
                String key = bot.getBotId() + ":" + group.getGroupId() + ":" + messageId;
                if (verificationFeedbackMessages.putIfAbsent(key, now) != null) { return; }
            }
            if (expired) { abandonVerificationAttempt(attempt); }
            else { finishVerificationAttempt(attempt, success, success ? "game_accepted" : "game_rejected"); }
        }
    }

    /** 游戏的验证码成功回执只有“奖励5574灵石”；限制整段内容，排除悬赏等普通奖励。 */
    private boolean isBareVerificationReward(String message) {
        String body = StringUtils.defaultString(message)
                .replaceAll("(?m)^\\s*\\[\\]\\([^\\r\\n)]*\\)\\s*", "")
                .replaceAll("\\[@[^\\r\\n]*?\\]\\(mqqapi://markdown/mention\\?[^\\r\\n)]*\\)", "")
                .replaceAll("\\[CQ:at,qq=\\d+[^\\]]*\\]", "")
                .replaceAll("(?<!\\S)@\\d+(?!\\d)\\s*", "").trim();
        return body.matches("奖励\\s*\\d+(?:\\.\\d+)?\\s*灵石[！!。]?");
    }

    private static final class VerificationAttempt {
        private final long botId;
        private final VerifyCount counter;
        private final String recognizer;
        private final String imageUrl;
        private final long groupId;
        private final Integer messageId;
        private final LocalDate attemptedOn;
        private boolean submitted;
        private long submittedAt;
        private boolean settled;
        private Boolean outcome;
        private volatile RecognitionResult result;

        private VerificationAttempt(long botId, VerifyCount counter, String recognizer, String imageUrl, long groupId,
                Integer messageId, LocalDate attemptedOn) {
            this.botId = botId;
            this.counter = counter;
            this.recognizer = recognizer;
            this.imageUrl = imageUrl;
            this.groupId = groupId;
            this.messageId = messageId;
            this.attemptedOn = attemptedOn;
        }
    }

    /** 模式2的点击接口异常最多重试一次，始终复用原按钮和原消息参数。 */
    private void clickCaptchaButtonWithRetry(Bot bot, Group group, Buttons buttons, Button button) {
        long groupId = group.getGroupId();
        String botAppid = buttons.getBotAppid();
        String buttonId = button.getId();
        String callbackData = button.getData();
        String msgSeq = buttons.getMsgSeq();
        try {
            bot.clickKeyboardButton(groupId, botAppid, buttonId, callbackData, msgSeq);
        } catch (Exception firstFailure) {
            if (bot.getBotConfig().getAutoVerifyModel() != 2) {
                throw firstFailure;
            }
            logger.warn("验证码点击失败，1秒后重试一次 botId={} groupId={} msgSeq={} buttonId={}",
                    bot.getBotId(), groupId, msgSeq, buttonId, firstFailure);
            try {
                Thread.sleep(CLICK_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("验证码点击重试等待被中断", e);
            }
            // 等待期间切换到手动或半自动模式后，不再自动重试。
            if (bot.getBotConfig().getAutoVerifyModel() != 2) {
                throw new IllegalStateException("验证模式已变更，取消验证码点击重试", firstFailure);
            }
            try {
                bot.clickKeyboardButton(groupId, botAppid, buttonId, callbackData, msgSeq);
            } catch (Exception retryFailure) {
                if (retryFailure != firstFailure) {
                    retryFailure.addSuppressed(firstFailure);
                }
                throw retryFailure;
            }
            logger.info("验证码点击重试成功 botId={} groupId={} msgSeq={} buttonId={}",
                    bot.getBotId(), groupId, msgSeq, buttonId);
        }
    }

    /**
     * 识别错误后尝试随机点击一个按钮
     */
    private void errorClickButton(Buttons buttons, Bot bot, Group group, RecognitionResult result) {
        if (bot.getBotConfig().getAutoVerifyModel() != 2 || buttons == null
                || buttons.getButtonList() == null || buttons.getButtonList().isEmpty()) {
            return;
        }
        String resultText = result == null ? "" : StringUtils.defaultString(result.getResult());
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
                clickCaptchaButtonWithRetry(bot, group, buttons, maxNumberButton);
                return;
            }
        }
        // 模式2即使没有识别出任何表情，也随机选择实际按钮，不能固定点第一个或因空列表抛异常。
        List<Button> buttonList = buttons.getButtonList();
        int randomIndex = fallbackButtonRandom.nextInt(buttonList.size());
        Button button = buttonList.get(randomIndex);
        logger.info("自动验证未匹配答案，随机点击按钮 botId={} groupId={} index={} label={}",
                bot.getBotId(), group.getGroupId(), randomIndex + 1, button.getLabel());
        clickCaptchaButtonWithRetry(bot, group, buttons, button);
    }

    public RecognitionResult recognizeVerifyCode(String imageUrl, String title, Bot bot) {
        String answer = "";
        String resultText = "";
        RecognitionResult recognitionResult = new RecognitionResult();
        try {

            recognitionResult = callShituAPI(shituApiUrl, imageUrl, bot.getBotId());
            recognitionResult.url = imageUrl;
            recognitionResult.title = title;
            if (recognitionResult.emojiList == null) {
                recognitionResult.emojiList = new ArrayList<>();
            }
            resultText = StringUtils.defaultString(recognitionResult.result);

            resultText = Utils.extractAfterPleaseClick(resultText);
            if (StringUtils.isNotBlank(title)) {
                if (title.contains("请点击图中第") && title.contains("表情") && !recognitionResult.emojiList.isEmpty()) {
                    answer = getEmojiAnswer(title, recognitionResult);
                } else if (title.contains("请点击") && title.contains("飞行的交通工具")
                        && !recognitionResult.emojiList.isEmpty()) {
                    for (String item : recognitionResult.emojiList) {
                        if ("飞机".equals(item) || "直升机".equals(item)) {
                            answer = item;
                            break;
                        }
                    }
                    if (StringUtils.isBlank(answer)) {
                        answer = "飞机";
                    }
                }
            }
            // if (StringUtils.isNotBlank(title)) {
            // if (title.contains("请点击图中第") && title.contains("表情") &&
            // !recognitionResult.emojiList.isEmpty()) {
            // answer = getEmojiAnswer(title, recognitionResult);

            // } else if (title.contains("请问深色文字中字符") && title.contains("出现了几次")) {
            // Character targetChar = extractTargetChar(title);
            // if (targetChar != null) {
            // answer = String.valueOf(countCharOccurrences(resultText, targetChar));
            // } else {
            // System.out.println("识别失败，请手动点击验证码" + title);
            // answer = "识别失败，请手动点击验证码";
            // // recognitionResult.answer = "识别失败，请手动点击验证码";
            // // return resultText;
            // }
            // } else if (title.contains("请按照深色文字的题目点击对应的答案")) {

            // if (resultText.contains("点击") && resultText.length() < 7) {

            // if (resultText.length() == 4 || resultText.length() == 5) {
            // char secondLastChar = resultText.charAt(resultText.length() - 2);
            // char lastChar = resultText.charAt(resultText.length() - 1);
            // String secondLastStr = String.valueOf(secondLastChar);
            // String lastStr = String.valueOf(lastChar);

            // if ("沙".equals(secondLastStr) || "漏".equals(lastStr)) {
            // answer = "沙漏";
            // } else if ("葡".equals(secondLastStr) || "萄".equals(lastStr) ||
            // "萄".equals(secondLastStr) || "葡".equals(lastStr)) {
            // answer = "葡萄";
            // } else if ("书".equals(secondLastStr) || "本".equals(lastStr)) {
            // answer = "书本";
            // } else if ("图".equals(secondLastStr) || "钉".equals(lastStr)) {
            // answer = "图钉";
            // } else if ("西".equals(secondLastStr) || "瓜".equals(lastStr)) {
            // answer = "西瓜";
            // } else if ("汽".equals(secondLastStr) || "车".equals(lastStr)) {
            // answer = "汽车";
            // } else if ("鲸".equals(secondLastStr) || "鱼".equals(lastStr)) {
            // answer = "鲸鱼";
            // } else if (resultText.length() == 4 && "鸡".equals(lastStr)) {
            // answer = "鸡";
            // } else if (resultText.length() == 5 && "脑".equals(lastStr)) {
            // answer = "电脑";
            // } else if (resultText.length() == 5 && ("池".equals(lastStr) ||
            // "减".equals(lastStr))) {
            // answer = "电池";
            // } else if (resultText.length() == 5 && "电".equals(secondLastStr) &&
            // "沙".equals(lastStr)) {
            // answer = "电池";
            // } else if (resultText.length() == 5 && "电".equals(secondLastStr)) {
            // answer = "电池";
            // } else if (resultText.length() == 5 && ("苹".equals(secondLastStr) ||
            // "果".equals(lastStr))) {
            // answer = "苹果";
            // } else if (resultText.length() == 4) {
            // answer = lastStr;
            // } else {
            // answer = secondLastStr + lastStr;
            // }
            // } else {
            // answer = resultText.substring("请点击".length()).trim();
            // if (answer.equals("漏萄")) answer = "葡萄";
            // }
            // } else if (!recognitionResult.emojiList.isEmpty() &&
            // !StringUtils.isEmpty(resultText)) {
            // answer = getEmojiAnswer(resultText, recognitionResult);
            // } else if (resultText.contains("表") && resultText.contains("情")) {
            // int idx = 1;
            // Matcher matcher = Pattern.compile("(\\d+|[一二三四五六七八九])").matcher(resultText);
            // if (recognitionResult.emojiList.isEmpty()) {
            // if (matcher.find()) {
            // String match = matcher.group(1);
            // if (match.matches("\\d+")) {
            // idx = Integer.parseInt(match);
            // } else {
            // idx = CHINESE_NUMBERS.get(match); // 中文转数字
            // }
            // }
            // if (idx == 7) {
            // idx = 1;
            // }
            // answer = "序号" + idx;
            // } else if (recognitionResult.emojiList.size() == 3) {
            // if (matcher.find()) {
            // if (Integer.parseInt(matcher.group()) < 4) {
            // idx = Integer.parseInt(matcher.group()) - 1;
            // }
            // if (Integer.parseInt(matcher.group()) == 4 ||
            // Integer.parseInt(matcher.group()) == 7) {
            // idx = 0;
            // }
            // }
            // if (idx < recognitionResult.emojiList.size()) {
            // answer = recognitionResult.emojiList.get(idx);
            // }
            // }
            // } else if (resultText.contains("加") && (resultText.length() == 11 ||
            // resultText.length() == 10)) {
            // // resultText = resultText.replaceAll("点", "加");
            // answer = String.valueOf(calculate(resultText, "加"));
            // } else if (resultText.contains("减")) {
            // answer = String.valueOf(calculate(resultText, "减"));
            // } else if (resultText.contains("乘")) {
            // resultText = resultText.replaceAll("结乘", "结果");
            // resultText = resultText.replaceAll("乘点击", "请点击");
            // answer = String.valueOf(calculate(resultText, "乘"));
            // }
            // } else if (title.contains("请问图中深色文字中包含几个字符")) {
            // answer = String.valueOf(resultText.length());
            // }
            // }
            if (StringUtils.isEmpty(answer)) {
                answer = "识别失败，请手动点击验证码";
            }

            recognitionResult.url = imageUrl;
            recognitionResult.answer = answer;
            recognitionResult.result = resultText;
            return recognitionResult;
            // return resultText + "\n正确答案：" + answer;
        } catch (Exception e) {
            e.printStackTrace();
            recognitionResult.answer = "识别失败，请手动点击验证码";
            return recognitionResult;
        }
    }

    private boolean isCaptchaServiceBlocked(RecognitionResult recognitionResult) {
        if (recognitionResult == null) {
            return false;
        }
        return recognitionResult.getCode() != 0;
    }

    private void sendCaptchaServiceBlockedMessage(Bot bot, Group group, RecognitionResult recognitionResult) {
        long botId = bot.getBotId();
        long now = System.currentTimeMillis();
        Long lastAt = lastServiceWarnAt.get(botId);
        if (lastAt != null && now - lastAt < 5 * 60 * 1000L) {
            return;
        }
        lastServiceWarnAt.put(botId, now);

        int code = recognitionResult == null ? 1 : recognitionResult.getCode();
        String rawMsg = recognitionResult == null ? "" : StringUtils.defaultString(recognitionResult.getMsg());
        String tip;
        if (code == 40302) {
            tip = "验证码识别会员已到期，请联系管理续费";
        } else if (code == 42901) {
            tip = "验证码识别调用次数已达上限（今日），请稍后再试或联系管理";
        } else if (code == 42902) {
            tip = "验证码识别调用次数已达上限（本月），请联系管理处理";
        } else if (code == 40301) {
            tip = "验证码识别账号已被禁用，请联系管理";
        } else {
            tip = "验证码识别服务不可用：" + (StringUtils.isNotBlank(rawMsg) ? rawMsg : "未知原因");
        }

        try {

            Long masterQQ = bot.getBotConfig().getMasterQQ();
            if (masterQQ != null && masterQQ > 0) {
                Utils.sendGroupMessage(bot, bot.getBotConfig().getGroupId(),
                        (new MessageChain()).at(masterQQ + "").text(tip));
            } else {
                Utils.sendGroupMessage(bot, bot.getBotConfig().getGroupId(), (new MessageChain()).text(tip));
            }
        } catch (Exception e) {
            logger.warn("发送验证码识别服务提示失败: {}", e.getMessage());
        }
    }

    private String getEmojiAnswer(String title, RecognitionResult recognitionResult) {
        String answer;
        int idx = 0;
        // 只取"第N个"里的数字。题目在 markdown 渲染下带有版本号/图片尺寸等无关数字
        // （如 %3A2、1100px），不能匹配整段文本里第一个出现的数字
        Matcher matcher1 = Pattern.compile("第(\\d+|[一二三四五六七八九十]+)个").matcher(title);
        if (matcher1.find()) {
            String matched = matcher1.group(1);
            if (matched.matches("\\d+")) {
                idx = Integer.parseInt(matched) - 1;
            } else if (matched.contains("十")) {
                String[] parts = matched.split("十");
                int value = 10;
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    value = CHINESE_NUMBERS.getOrDefault(parts[0], 1) * 10;
                }
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    value += CHINESE_NUMBERS.getOrDefault(parts[1], 0);
                }
                idx = value - 1;
            } else {
                idx = CHINESE_NUMBERS.getOrDefault(matched, 1) - 1;
            }
        }
        if (idx >= recognitionResult.emojiList.size()) {
            idx = recognitionResult.emojiList.size() - 1;
        }
        recognitionResult.setLastClickIndex(idx);
        answer = recognitionResult.emojiList.get(idx);
        return answer;
    }

    private void verifyFailSendMessage(Bot bot, Group group, MessageChain messageChain, String message,
            Integer messageId, Buttons buttons, RecognitionResult recognitionResult) {
        // saveErrorImage(buttons.getImageUrl());
        if (isCaptchaServiceBlocked(recognitionResult)) {
            sendCaptchaServiceBlockedMessage(bot, group, recognitionResult);
            return;
        }
        if (bot.getBotConfig().getAutoVerifyModel() != 2) {
            if (xxGroupId > 0) {
                if (bot.getBotConfig().getMasterQQ() != 819463350L) {
                    testService.showButtonMsg(bot, group, messageId, message, buttons, messageChain);
                } else {
                    sendFailMessage(bot, message, buttons, messageChain, recognitionResult);
                }
                CaptchaReminderSender.send(bot, xxGroupId,
                        (new MessageChain()).at(bot.getBotConfig().getMasterQQ() + "").text("自动验证失败，请手动验证"));
            } else {
                CaptchaReminderSender.send(bot, bot.getBotConfig().getGroupId(),
                        (new MessageChain()).at(bot.getBotConfig().getMasterQQ() + "").text("自动验证失败，请手动验证"));
            }
        } else {
            errorClickButton(buttons, bot, group, recognitionResult);
        }

        RecognitionResult codeData = codeUrlMap.get(bot.getBotId());
        if (codeData != null && StringUtils.isBlank(codeData.getKnnReviewId())) {
            saveErrorImage(codeData.getUrl(), codeData.getTitle(), Objects.toString(codeData.getEmojiList(), "[]"),
                    bot.getBotId());
        }

    }

    public void sendFailMessage(Bot bot, String message, Buttons buttons, MessageChain messageChain,
            RecognitionResult recognitionResult) {
        BotConfig botConfig = bot.getBotConfig();
        if (buttons != null && !buttons.getButtonList().isEmpty() && botConfig.getMasterQQ() == 819463350L
                && xxGroupId > 0) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(Utils.formatButtons(buttons.getButtonList(), 4));
            if (recognitionResult.emojiList != null && !recognitionResult.emojiList.isEmpty()) {
                stringBuilder.append("识别表情：");
                stringBuilder.append(recognitionResult.emojiList);
                stringBuilder.append("\n");
            }
            stringBuilder.append("正确答案：" + recognitionResult.answer);
            stringBuilder.append("\n");
            String captchaPrompt = Utils.extractCaptchaPrompt(buttons.getImageText());
            if (StringUtils.isNotBlank(captchaPrompt)) {
                stringBuilder.append(captchaPrompt);
            }
            MessageChain messageChain1 = new MessageChain();
            messageChain1.text("\n").image(buttons.getImageUrl()).text(stringBuilder.toString());
            // bot.sendPrivateMessage(bot.getBotId(), messageChain1);
            CaptchaReminderSender.send(bot, xxGroupId, messageChain1);
        }
    }

    private void getImageInfo(String message, Buttons buttons, MessageChain messageChain) {
        String regex = "https?://[^\\s\\)]+";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(message);

        while (matcher.find()) {
            buttons.setImageUrl(matcher.group());
        }
        // 题目文本从后往前找包含"请点击"的消息段（SnowLuma 下最后一段可能是
        // inline_keyboard，不含文字；NapCat 下最后一段即为题目，行为不变）
        if (messageChain != null) {
            for (int i = messageChain.size() - 1; i >= 0; i--) {
                Message seg = messageChain.get(i);
                if (seg != null && seg.toString().contains("请点击")) {
                    buttons.setImageText(Utils.extractCaptchaPrompt(seg.toString()));
                    break;
                }
            }
        }
        if (StringUtils.isBlank(buttons.getImageText())) {
            buttons.setImageText(Utils.extractCaptchaPrompt(message));
        }
    }

    public void saveErrorImage(String url, String question, String answer, Long botId) {
        // saveErrorImage(url);
        // if (shituApiUrl.contains("113.44.42.139")) {
        // // 下载网络图片
        // byte[] imageBytes = null;
        // try {
        // imageBytes = downloadImageBytes(url);
        // // 上传到服务器
        // String hash = md5(url);
        // String returnMsg = uploadMultipart(shituApiUrl + "report_error", imageBytes,
        // "error_" + hash + ".jpg", question, answer,url);
        // RecognitionResult result = JSON.parseObject(returnMsg,
        // RecognitionResult.class);
        // if (result.msg.contains("已保存")) {
        // groupManager.verifyCount.addError();
        // }
        // } catch (Exception e) {
        // e.printStackTrace();
        // }
        // } else {
        // saveErrorImage(url);
        // }
        byte[] imageBytes = null;
        try {
            imageBytes = downloadImageBytes(url);
            // 上传到服务器
            String hash = md5(url);
            String returnMsg = uploadMultipart(shituApiUrl + "report_error", imageBytes, "error_" + hash + ".jpg",
                    question, answer, url, botId);
            RecognitionResult result = JSON.parseObject(returnMsg, RecognitionResult.class);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void saveErrorImage(String url) {
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
        } catch (Exception e) {
            e.printStackTrace();
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
        for (char ch : t.toCharArray())
            if (ch == c)
                cnt++;
        return cnt;
    }

    public static int calculate(String expr, String op) {
        // Pattern tokenPattern = Pattern.compile("(\\d+|[" + String.join("",
        // CHINESE_NUMBERS.keySet()) + "]+|加|减|乘)");
        Pattern tokenPattern = Pattern.compile("(\\d+|[" + String.join("", CHINESE_NUMBERS.keySet()) + "])");
        Matcher matcher = tokenPattern.matcher(expr);

        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        if (tokens.isEmpty())
            return -1;

        List<Integer> numbers = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        for (String token : tokens) {
            if (token.equals("加") || token.equals("减") || token.equals("乘")) {
                ops.add(token);
            } else {
                numbers.add(parseNumber(token));
            }
        }
        if (numbers.isEmpty())
            return -1;

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

    public RecognitionResult callShituAPI(String shituApiUrl, String imageUrl, Long qqNumber) {
        return callRecognitionAPI(shituApiUrl, imageUrl, qqNumber, "primary");
    }

    /** KNN 确认失败后只上报快照编号，由识别服务归档原图；不把点击异常当成错题。 */
    public void reportKnnFailure(String reviewId, String title, String answer, long botId, String reason) {
        if (StringUtils.isBlank(shituApiUrl)) { return; }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(shituApiUrl + "knn/report_failure?qq=" + URLEncoder.encode(botId + "", "UTF-8"));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            JSONObject body = new JSONObject();
            body.put("reviewId", reviewId);
            body.put("reason", reason);
            body.put("title", StringUtils.left(StringUtils.defaultString(title), 2000));
            body.put("answer", StringUtils.left(StringUtils.defaultString(answer), 1000));
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toJSONString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status == 200) { logger.info("KNN错题已提交人工复核 botId={}", botId); }
            else { logger.warn("KNN错题上报失败 botId={} status={}", botId, status); }
        } catch (Exception e) {
            logger.warn("KNN错题上报未完成 botId={} reason={}", botId, e.getClass().getSimpleName());
        } finally {
            if (connection != null) { connection.disconnect(); }
        }
    }

    public RecognitionResult callRecognitionAPI(String shituApiUrl, String imageUrl, Long qqNumber, String recognizer) {
        HttpURLConnection conn = null;
        try {
            // 在URL中添加qq参数
            String apiUrl = shituApiUrl + "recognize";
            if (qqNumber != null) {
                apiUrl += "?qq=" + URLEncoder.encode(qqNumber + "", "UTF-8");
            }

            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);

            JSONObject requestBody = new JSONObject();
            requestBody.put("url", imageUrl);
            if ("knn".equals(recognizer)) { requestBody.put("recognizer", "knn"); }
            String jsonInputString = requestBody.toJSONString();

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int code = conn.getResponseCode();
            InputStream inputStream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();

            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
            }
            try {
                RecognitionResult result = JSON.parseObject(response.toString(), RecognitionResult.class);
                if (result == null) { throw new IllegalArgumentException("空识别结果"); }
                if (result.getEmojiList() == null) {
                    result.setEmojiList(new ArrayList<>());
                }
                return result;
            } catch (Exception parseEx) {
                logger.warn("API响应解析失败: {}", parseEx.getMessage());
                RecognitionResult fallback = new RecognitionResult(new ArrayList<>(), "服务返回异常");
                fallback.setCode(code);
                fallback.setMsg(response.toString());
                fallback.setResult("识别失败：服务返回异常");
                return fallback;
            }
        } catch (SocketTimeoutException ste) {
            logger.warn("API读取超时: {}", ste.getMessage());
            return new RecognitionResult(new ArrayList<>(), "请求超时");
        } catch (Exception e) {
            logger.error("API调用异常: {}", e.getMessage());
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
    private static String uploadMultipart(String serverUrl, byte[] imageBytes, String fileName, String question,
            String answer, String imageUrl, Long qqNumer) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(serverUrl);

            HttpEntity entity = MultipartEntityBuilder.create()
                    .addBinaryBody("image", imageBytes, ContentType.IMAGE_JPEG, fileName)
                    .addTextBody("question", question, ContentType.TEXT_PLAIN.withCharset("UTF-8"))
                    .addTextBody("answer", answer, ContentType.TEXT_PLAIN.withCharset("UTF-8"))
                    .addTextBody("imageUrl", imageUrl, ContentType.TEXT_PLAIN.withCharset("UTF-8"))
                    .addTextBody("qq", qqNumer + "", ContentType.TEXT_PLAIN.withCharset("UTF-8"))
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
