//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service;

import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import top.sshh.qqbot.data.RemindTime;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GroupManager {
    private static final Logger logger = LoggerFactory.getLogger(GroupManager.class);
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    Map<String, RemindTime> mjXslmap = new ConcurrentHashMap();
    Map<String, RemindTime> ltmap = new ConcurrentHashMap();
    private static final ForkJoinPool customPool = new ForkJoinPool(20);
    private static final List<String> MJ_TEXT_LIST = Arrays.asList(" 【秘境结算提醒】秘境大门即将关闭！请速速退出，否则将被传送到‘社畜加班’副本！",
            "【秘境结算提醒】叮！秘境探索结算中，本次收获：四库全书 1 本，戏书 1 本，以及队友的嫌弃三连～",
            "【秘境结算提醒】警告！秘境 BOSS 正在暴走，若不及时撤离，将强制触发‘与 BOSS 拼酒’支线任务！",
            "【秘境结算提醒】您的秘境评分：D 级（全靠运气），建议下次组队带个会看地图的队友！",
            "【秘境结算提醒】秘境探索结束，本次成就：迷路 10 次，摔进陷阱 3 次，荣获‘秘境路痴’称号！",
            "【秘境结算提醒】您意外触发隐藏剧情，获得‘被秘境BOSS追着跑’专属表情包！");
    private static final List<String> XSL_TEXT_LIST = Arrays.asList("【悬赏结算提醒】您的江湖信誉值 + 50！悬赏奖励已存入包裹，小心别被其他修士盯上～",
            "【悬赏结算提醒】注意！您的暗杀任务结算失败 —— 目标竟是掌门私生子，建议连夜跑路！",
            "【悬赏结算提醒】恭喜完成任务！奖励：灵石 x500，经验 x200，以及… 来自 NPC 的白眼‘下次别接这么菜的任务",
            "【悬赏结算提醒】您的悬赏任务该结算了！江湖恩怨就此两清，记得查收尾款，别让仇家赖账～",
            "【悬赏结算提醒】秘境探索结束，本次成就：迷路 10 次，摔进陷阱 3 次，荣获‘秘境路痴’称号！",
            "【悬赏结算提醒】您意外触发隐藏剧情，获得‘被秘境BOSS追着跑’专属表情包！");
    @Value("${botId}")
    private Long botId;

    public GroupManager() {
    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 我要头衔(final Bot bot, final Group group, final Member member, MessageChain messageChain, String message, Integer messageId) {
        if (bot.getBotConfig().isEnableSelfTitle() && message.contains("我要头衔")) {
            final String specialTitle = message.substring(message.indexOf("我要头衔") + 4).trim();
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        bot.setGroupSpecialTitle(member.getUserId(), specialTitle, 0, group.getGroupId());
                    } catch (Exception var2) {
                    }

                }
            });
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 修改群昵称(final Bot bot, final Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isAtSelf = message.contains("" + bot.getBotId());
        if (group.getGroupId() == 682220759L && isAtSelf && message.contains("道友今天双修次数已经到达上限")) {
            customPool.submit(new Runnable() {
                public void run() {
                    try {
                        bot.setGroupCard(group.getGroupId(), bot.getBotId(), "A无偿双修(剩余0次)");
                    } catch (Exception var2) {
                    }

                }
            });
        }

    }

    @Scheduled(
            cron = "0 0 4 * * *"
    )
    public void 重置双修次数() throws Exception {
        BotFactory.getBots().values().forEach((bot) -> {
            if (bot.getBotId() != bot.getBotConfig().getMasterQQ()) {
                bot.setGroupCard(682220759L, bot.getBotId(), "A无偿双修");
            }

        });
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 秘境结算提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        if (bot.getBotConfig().isEnableGroupManager()) {
            boolean isGroupQQ = false;
            if (StringUtils.isNotBlank(bot.getBotConfig().getGroupQQ())) {
                isGroupQQ = ("&" + bot.getBotConfig().getGroupQQ() + "&").contains("&" + member.getGroupId() + "&");
            } else {
                isGroupQQ = group.getGroupId() == 802082768L;
            }

            if (!isGroupQQ) {
                return;
            }

            if (message.contains("进行中的：") && message.contains("可结束") && message.contains("探索")) {
                this.extractInfo(message, "秘境", group);
            }

            if (message.contains("进入秘境") && message.contains("探索需要花费")) {
                this.extractInfo(message, "秘境", group);
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 悬赏令结算提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        if (bot.getBotConfig().isEnableGroupManager() && message.contains("进行中的悬赏令") && message.contains("可结束")) {
            boolean isGroupQQ = false;
            if (StringUtils.isNotBlank(bot.getBotConfig().getGroupQQ())) {
                isGroupQQ = ("&" + bot.getBotConfig().getGroupQQ() + "&").contains("&" + member.getGroupId() + "&");
            } else {
                isGroupQQ = group.getGroupId() == 802082768L;
            }

            if (!isGroupQQ) {
                return;
            }

            this.extractInfo(message, "悬赏", group);
        }

    }

    public void extractInfo(String input, String type, Group group) {
        String qqPattern = "@(\\d+)";
        String timePattern = "(\\d+\\.?\\d*)(?:\\(原\\d+\\.?\\d*\\))?(?:分钟|分钟后)";
        Pattern qqRegex = Pattern.compile(qqPattern);
        Pattern timeRegex = Pattern.compile(timePattern);
        Matcher qqMatcher = qqRegex.matcher(input);
        String qq = "";
        if (qqMatcher.find()) {
            qq = qqMatcher.group(1);
        } else {
            logger.warn("未找到QQ号");
        }

        Matcher timeMatcher = timeRegex.matcher(input);
        String time = "";
        if (timeMatcher.find()) {
            time = timeMatcher.group(1);
        } else {
            logger.warn("未找到时间");
        }

        if (StringUtils.isNotBlank(qq) && StringUtils.isNotBlank(time)) {
            RemindTime remindTime = new RemindTime();
            remindTime.setQq(Long.parseLong(qq));
            remindTime.setExpireTime((long)(Double.parseDouble(time) * 60.0 * 1000.0 + (double)System.currentTimeMillis()));
            remindTime.setText(type);
            remindTime.setGroupId(group.getGroupId());
            this.mjXslmap.put(qq, remindTime);
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 灵田领取提醒(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        if (bot.getBotConfig().isEnableGroupManager() && message.contains("灵田还不能收取")) {
            boolean isGroupQQ = false;
            if (StringUtils.isNotBlank(bot.getBotConfig().getGroupQQ())) {
                isGroupQQ = ("&" + bot.getBotConfig().getGroupQQ() + "&").contains("&" + member.getGroupId() + "&");
            } else {
                isGroupQQ = group.getGroupId() == 802082768L;
            }

            if (!isGroupQQ) {
                return;
            }

            this.handleLingTianMessage(message, group);
        }

    }

    private void handleLingTianMessage(String message, Group group) {
        String regex = "@(\\d{5,12}).*?(\\d+\\.\\d+)小时";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(message);
        String qqNumber = "";
        String time = "";
        if (matcher.find()) {
            qqNumber = matcher.group(1);
            time = matcher.group(2);
        }

        if (StringUtils.isNotBlank(qqNumber) && StringUtils.isNotBlank(time)) {
            RemindTime remindTime = new RemindTime();
            remindTime.setText("灵田");
            remindTime.setQq(Long.parseLong(qqNumber));
            remindTime.setExpireTime((long)(Double.parseDouble(time) * 60.0 * 60.0 * 1000.0 + (double)System.currentTimeMillis()));
            remindTime.setGroupId(group.getGroupId());
            this.ltmap.put(qqNumber, remindTime);
            group.sendMessage((new MessageChain()).at(qqNumber).text("灵田收取时间为：" + sdf.format(new Date(remindTime.getExpireTime()))));
        }

    }

    @Scheduled(
            fixedDelay = 30000L,
            initialDelay = 3000L
    )
    public void 结算() {
        this.checkAndNotify(this.mjXslmap, "悬赏", "秘境");
        this.checkAndNotify(this.ltmap, "灵田", "灵田");
    }

    private void checkAndNotify(Map<String, RemindTime> map, String taskType1, String taskType2) {
        if(botId != null){
            Iterator<Map.Entry<String, RemindTime>> iterator = map.entrySet().iterator();

            while(iterator.hasNext()) {
                Map.Entry<String, RemindTime> entry = (Map.Entry)iterator.next();
                RemindTime remindTime = (RemindTime)entry.getValue();
                if (remindTime.getExpireTime() > 0L && remindTime.getExpireTime() < System.currentTimeMillis()) {
                    Bot bot = (Bot)BotFactory.getBots().get(botId);
                    if (bot != null) {
                        if (remindTime.getText().equals(taskType1)) {
                            bot.getGroup(remindTime.getGroupId()).sendMessage((new MessageChain()).at(remindTime.getQq() + "").text("道友，您的" + taskType1 + "可以结算了！"));
                        } else if (remindTime.getText().equals(taskType2)) {
                            bot.getGroup(remindTime.getGroupId()).sendMessage((new MessageChain()).at(remindTime.getQq() + "").text("道友，您的" + taskType2 + "可以结算了！"));
                        }

                        iterator.remove();
                    }
                }
            }
        }


    }
}
