//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Iterator;
import java.util.TimeZone;

@Component
public class FamilyTask {
    private static final Logger log = LoggerFactory.getLogger(FamilyTask.class);
    public static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss") {
        {
            this.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        }
    };

    public FamilyTask() {
    }

    @Scheduled(
            cron = "*/5 * * * * *"
    )
    public void 宗门任务接取刷新() throws InterruptedException {
        BotFactory.getBots().values().forEach((bot) -> {
            BotConfig botConfig = bot.getBotConfig();
            if (!botConfig.isEnableSectMission()) {
                botConfig.setFamilyTaskStatus(0);
            } else {
                if (botConfig.isStop() && botConfig.getFamilyTaskStatus() != 0) {
                    botConfig.setStop(false);
                    botConfig.setFamilyTaskStatus(0);
                    System.out.println("宗门任务接取刷新");
                }

                long groupId = botConfig.getGroupId();
                if (botConfig.getTaskId() != 0L) {
                    groupId = botConfig.getTaskId();
                }

                Group group = bot.getGroup(groupId);
                switch (botConfig.getFamilyTaskStatus()) {
                    case 0:
                        return;
                    case 1:
                        group.sendMessage((new MessageChain()).at("3889001741").text("宗门任务接取"));
                        return;
                    case 2:
                        if (botConfig.getLastRefreshTime() + 65000L < System.currentTimeMillis()) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("宗门任务刷新"));
                        }

                        return;
                    case 3:
                        if (botConfig.getLastRefreshTime() > System.currentTimeMillis()) {
                            return;
                        }

                        if (botConfig.getCultivationMode() == 2) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("出关"));
                        } else if (botConfig.getCultivationMode() == 3) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("宗门出关"));
                        }

                        try {
                            Thread.sleep(2000L);
                        } catch (InterruptedException var7) {
                        }

                        group.sendMessage((new MessageChain()).at("3889001741").text("宗门任务完成"));
                        botConfig.setFamilyTaskStatus(1);

                        try {
                            Thread.sleep(2000L);
                        } catch (InterruptedException var6) {
                        }

                        if (botConfig.getCultivationMode() == 2) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("闭关"));
                        } else if (botConfig.getCultivationMode() == 3) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("宗门闭关"));
                        }

                        return;
                    case 4:
                        botConfig.setLastRefreshTime(System.currentTimeMillis() + 360000L);
                        if (botConfig.getCultivationMode() == 0) {
                            botConfig.setFamilyTaskStatus(0);
                        }

                        botConfig.setStartScheduled(true);
                        botConfig.setFamilyTaskStatus(3);
                        return;
                    case 5:
                        group.sendMessage((new MessageChain()).at("3889001741").text("宗门任务完成"));
                        botConfig.setFamilyTaskStatus(1);
                        return;
                }
            }

        });
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 宗门任务状态管理(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        if ((group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId()) && message.contains("" + bot.getBotId())) {
            if (message.startsWith("道友目前还没有宗门任务")) {
                botConfig.setFamilyTaskStatus(1);
            }

            if (message.contains("今日无法再获取宗门任务")) {
                botConfig.setFamilyTaskStatus(0);
                TestService.proccessCultivation(group);
            }

            if (message.contains("道友大战一番") && message.contains("获得修为") && message.contains("宗门建设度增加")) {
                botConfig.setFamilyTaskStatus(1);
            }

            if (message.contains("出门做任务") && message.contains("不扣你任务次数")) {
                botConfig.setLastRefreshTime(System.currentTimeMillis() + 360000L);
                botConfig.setFamilyTaskStatus(4);
            }

            if (botConfig.getSectMode() == 1) {
                if (message.contains("邪修抢夺灵石") || message.contains("私自架设小型窝点")) {
                    botConfig.setLastRefreshTime(System.currentTimeMillis());
                    botConfig.setFamilyTaskStatus(3);
                }

                if (message.contains("被追打催债") || message.contains("为宗门购买一些") || message.contains("请道友下山购买")) {
                    botConfig.setFamilyTaskStatus(2);
                    botConfig.setLastRefreshTime(System.currentTimeMillis());
                }
            }

            if (botConfig.getSectMode() == 2 && (message.contains("邪修抢夺灵石") || message.contains("私自架设小型窝点") || message.contains("被追打催债") || message.contains("请道友下山购买") || message.contains("为宗门购买一些"))) {
                botConfig.setLastRefreshTime(System.currentTimeMillis());
                botConfig.setFamilyTaskStatus(5);
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 灵田领取结果(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
        boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
        if (isGroup && isAtSelf) {
            if (message.contains("灵田还不能收取")) {
                String[] parts = message.split("：|小时");
                if (parts.length < 2) {
                    group.sendMessage((new MessageChain()).text("输入格式不正确，请确保格式为 '下次收取时间为：XX.XX小时"));
                    return;
                }

                double hours = Double.parseDouble(parts[1].trim());
                bot.getBotConfig().setLastExecuteTime((long)((double)System.currentTimeMillis() + hours * 60.0 * 60.0 * 1000.0 - 1.728E8));
                group.sendMessage((new MessageChain()).text("下次收取时间为：" + sdf.format(new Date(bot.getBotConfig().getLastExecuteTime() + 172800000L))));
            } else if (message.contains("还没有洞天福地")) {
                System.out.println(LocalDateTime.now() + " " + group.getGroupName() + " 收到灵田领取结果,还没有洞天福地");
                bot.getBotConfig().setLastExecuteTime(9223372036854175807L);
            } else if (message.contains("本次修炼到达上限")) {
                group.sendMessage((new MessageChain()).at("3889001741").text("直接突破"));
            } else if (message.contains("道友成功收获药材")) {
                group.sendMessage((new MessageChain()).at("3889001741").text("灵田结算"));
            }
        }

    }

    @Scheduled(
            cron = "0 */1 * * * *"
    )
    public void 灵田领取() throws InterruptedException {
        Iterator var1 = BotFactory.getBots().values().iterator();

        while(var1.hasNext()) {
            Bot bot = (Bot)var1.next();
            BotConfig botConfig = bot.getBotConfig();
            if (botConfig.isEnableAutoField() && botConfig.getLastExecuteTime() + 60000L < System.currentTimeMillis() - 172800000L) {
                long groupId = botConfig.getGroupId();
                if (botConfig.getTaskId() != 0L) {
                    groupId = botConfig.getTaskId();
                }

                if (botConfig.isStop()) {
                    botConfig.setLastExecuteTime(9223372036854175807L);
                    System.out.println("触发灵田验证");
                    return;
                }

                botConfig.setLastExecuteTime(9223372036854175807L);
                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("灵田结算"));
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 一键刷灵根(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        if (botConfig.isStartAutoLingG()) {
            if (message.contains("你的灵石还不够呢")) {
                botConfig.setStartAutoLingG(false);
            } else {
                boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
                if (isAtSelf && message.contains("逆天之行") && message.contains("新的灵根为")) {
                    if (!message.contains("异世界之力") && !message.contains("机械核心")) {
                        group.sendMessage((new MessageChain()).at("3889001741").text("重入仙途"));
                    } else {
                        botConfig.setStartAutoLingG(false);
                    }
                }
            }
        }

    }

    @Scheduled(
            cron = "15 10 4,8,18 * * *"
    )
    public void 签到() throws InterruptedException {
    }

    @Scheduled(
            cron = "0 38,39 12 * * *"
    )
    public void 准备秘境() throws InterruptedException {
    }

    @Scheduled(
            cron = "50 40,41 12 * * *"
    )
    public void 进入秘境() throws InterruptedException {
    }

    @Scheduled(
            cron = "0 0 0,1,2,3 * * *"
    )
    public void 开始宗门任务() throws InterruptedException {
    }

    @Scheduled(
            cron = "0 55 */2 * * *"
    )
    public void 定时闭关() throws InterruptedException {
    }
}
