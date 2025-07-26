package top.sshh.qqbot.service.utils;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.Message;
import com.zhuangxv.bot.message.MessageChain;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Utils {
    public static boolean isAtSelf(Bot bot, Group group) {

        return  group.getGroupId() == bot.getBotConfig().getGroupId() ;
    }
    public static boolean isAtSelf(Bot bot, Group group, String message) {

        return  group.getGroupId() == bot.getBotConfig().getGroupId() || message.contains(""+bot.getBotId()) ;
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

    public static void forwardMessage(Bot bot,long xxGroupId, String message){
        if(bot.getBotConfig().isEnableForwardMessage()){
            getRemindGroup(bot,xxGroupId).sendMessage(new MessageChain().text(cleanMessage(message)));
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
}
