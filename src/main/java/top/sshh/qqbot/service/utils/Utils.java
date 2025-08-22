package top.sshh.qqbot.service.utils;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.Message;
import com.zhuangxv.bot.message.MessageChain;
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

public class Utils {
//    public static boolean isAtSelf(Bot bot, Group group) {
//
//        return  group.getGroupId() == bot.getBotConfig().getGroupId();
//    }
    public static boolean isAtSelf(Bot bot, Group group, String message,long xxGroupId) {
        if(xxGroupId == 0){
            return message.contains(""+bot.getBotId()) || message.contains("@"+bot.getBotName()) ;
        }
        return group.getGroupId() == bot.getBotConfig().getGroupId() || message.contains(""+bot.getBotId()) ;
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

//    public static void forwardMessage(Bot bot,long xxGroupId, String message){
//        if(bot.getBotConfig().isEnableForwardMessage()){
//            getRemindGroup(bot,xxGroupId).sendMessage(new MessageChain().text(cleanMessage(message)));
//        }
//    }
    public static void forwardMessage(Bot bot,long xxGroupId,  MessageChain messageChain){
        if(bot.getBotConfig().isEnableForwardMessage() && xxGroupId>0){
            String message = String.valueOf(messageChain.get(messageChain.size()-1));
            if(StringUtils.isNotBlank(message)){
                getRemindGroup(bot,xxGroupId).sendMessage(new MessageChain().text(message));
            }

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
}
