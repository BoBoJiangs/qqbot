//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSONObject;
import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Button;
import com.zhuangxv.bot.core.Buttons;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.Message;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.constant.Constant;
import top.sshh.qqbot.data.GuessIdiom;
import top.sshh.qqbot.data.ProductLowPrice;
import top.sshh.qqbot.data.ProductPrice;
import top.sshh.qqbot.service.utils.Utils;

@Component
public class TestService {
    private static final Logger log = LoggerFactory.getLogger(TestService.class);
    @Autowired
    private ProductPriceResponse productPriceResponse;
    private static final ForkJoinPool customPool = new ForkJoinPool(20);
    public Map<Long, Buttons> botButtonMap = new ConcurrentHashMap();
    private boolean isStartAutoTalent = false;
    @Autowired
    private GroupManager groupManager;
    @Value("${xxGroupId:0}")
    private Long xxGroupId;
    //    @Value("${captcha.shitu-url:#{null}}")
//    private String shituApiUrl;
    @Value("${custom.shitu-api-url:#{null}}")
    private String shituApiUrl;
    private boolean isFirst = true;
    private static final List<String> KEYWORDS = Arrays.asList("烟雾缭绕", "在秘境最深处", "道友在秘境", "道友进入秘境后", "秘境内竟然", "道友大战一番成功", "道友大战一番不敌", "星河光芒神q", "秘境将闭时忽闻异香", "见玉榻白骨手持", "终在秘境核心", "白须老者笑赠", "掌心莫名多出", "秘境中遭迷阵所困", "历经心魔劫与雷狱考验，天道赐下", "言吾创太虚乾元诀将遇传人于此", "秘境将崩之际", "昏迷中似有仙人耳语", "道友破开秘境禁制闯入上古兵冢", "云中仙鹤衔来玉匣", "于祭坛顶端取得", "从腐朽道袍中滑落");
    private static final List<String> commandWords = Arrays.asList("悬赏令", "秘境", "宗门任务", "宗门丹药", "灵田", "灵石");
    private static final List<String> forwardWords = Arrays.asList("稍等一会", "宗门系统繁忙", "宗门闭关室", "当前灵石", "探索需要花费时间", "探索耗时", "道友成功领取到丹药", "道友已经领取过了", "不需要验证", "验证码已过期", "道友今天已经很努力了");

    public TestService() {
    }

    public static void proccessCultivation(Group group) {
        BotConfig botConfig = group.getBot().getBotConfig();
        int cultivationMode = botConfig.getCultivationMode();
        if (cultivationMode == 0) {
            botConfig.setStartScheduled(false);
        } else if (cultivationMode == 1) {
            botConfig.setStartScheduled(true);
            group.sendMessage((new MessageChain()).at("3889001741").text("修炼"));
        } else if (cultivationMode == 2) {
            botConfig.setStartScheduled(false);
            group.sendMessage((new MessageChain()).at("3889001741").text("闭关"));
        } else if (cultivationMode == 3) {
            botConfig.setStartScheduled(false);
            group.sendMessage((new MessageChain()).at("3889001741").text("宗门闭关"));
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void enableScheduled(final Bot bot, final Group group, Member member, final MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        long groupId = botConfig.getGroupId();
        message = message.trim();
        int cultivationMode = botConfig.getCultivationMode();
        if (!StringUtils.isEmpty(message) && !message.contains("可用命令")) {
            if ("命令".equals(message)) {
                group.sendMessage((new MessageChain()).reply(messageId).text(this.showReplyMessage(message, botConfig, bot)));
            }

            if ("当前设置".equals(message)) {
                group.sendMessage((new MessageChain()).reply(messageId).text(this.showReplyMessage(message, botConfig, bot)));
            }

            if ("停止执行".equals(message)) {
                botConfig.setStop(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("停止执行成功"));
                bot.getBotConfig().setCommand("");
            }

            if ("开始自动悬赏".equals(message)) {
                botConfig.setCommand("开始自动悬赏");
                this.startAutoTask(bot, botConfig, cultivationMode, groupId, message);
            }

            if ("查看悬赏令".equals(message)) {
                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("悬赏令"));
            }

            if ("开始自动秘境".equals(message)) {
                botConfig.setCommand("开始自动秘境");
                this.startAutoTask(bot, botConfig, cultivationMode, groupId, message);
            }

            if ("开始自动宗门任务".equals(message)) {
                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("宗门任务接取"));
            }

            if ("开始自动刷天赋".equals(message)) {
                this.isStartAutoTalent = true;
                group.sendMessage((new MessageChain()).at("3889001741").text("道具使用涅槃造化丹"));
            }

            if ("开始自动刷天赋".equals(message)) {
                this.isStartAutoTalent = true;
                bot.getGroup(botConfig.getGroupId()).sendMessage((new MessageChain()).at("3889001741").text("道具使用涅槃造化丹"));
            }

            if ("停止自动刷天赋".equals(message)) {
                this.isStartAutoTalent = false;
                group.sendMessage((new MessageChain()).reply(messageId).text("停止执行成功"));
            }

            if ("启用悬赏令价格查询".equals(message)) {
                botConfig.setEnableXslPriceQuery(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("启用悬赏令价格查询成功"));
            }

            if ("启用自动秘境".equals(message)) {
                botConfig.setEnableAutoSecret(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("启用自动秘境成功"));
            }

            if ("关闭自动秘境".equals(message)) {
                botConfig.setEnableAutoSecret(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("关闭自动秘境"));
            }

            if ("关闭悬赏令价格查询".equals(message)) {
                botConfig.setEnableXslPriceQuery(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("关闭悬赏令价格查询成功"));
            }

            if ("启用无偿双修".equals(message)) {
                botConfig.setEnableAutoRepair(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("启用无偿双修成功"));
            }

            if ("关闭无偿双修".equals(message)) {
                botConfig.setEnableAutoRepair(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("关闭无偿双修成功"));
            }


            if ("确认一键丹药炼金".equals(message)) {
                botConfig.setCommand("确认一键丹药炼金");
                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("丹药背包"));
            }

            if ("确认一键装备炼金".equals(message)) {
                botConfig.setCommand("确认一键装备炼金");
                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("我的背包"));
            }

            if ("确认一键药材上架".equals(message)) {
                botConfig.setCommand("确认一键药材上架");
                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
            }

            if ("开始一键刷灵根".equals(message)) {
                botConfig.setStartAutoLingG(true);
                bot.getGroup(botConfig.getGroupId()).sendMessage((new MessageChain()).at("3889001741").text("重入仙途"));
            }

            if ("停止一键刷灵根".equals(message)) {
                botConfig.setStartAutoLingG(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("停止成功"));
            }

            if ("悬赏优先时长最短".equals(message)) {
                botConfig.setRewardMode(4);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            if ("悬赏优先价值".equals(message)) {
                botConfig.setRewardMode(3);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            if ("悬赏优先修为".equals(message)) {
                botConfig.setRewardMode(5);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            if (message.startsWith("悬赏价格限制")) {
                String limitPrice = message.substring(message.indexOf("悬赏价格限制") + 6).trim();
                if (limitPrice.matches("[0-9]+")) {
                    botConfig.setXslPriceLimit(Integer.parseInt(limitPrice));
                    group.sendMessage((new MessageChain()).reply(messageId).text("悬赏令物品高于" + limitPrice + "万的物品优先接取"));
                } else {
                    group.sendMessage((new MessageChain()).reply(messageId).text("格式错误，示例：悬赏价格限制 1000"));
                }
            }

            if (message.startsWith("设置主号")) {
                String typeString = message.substring(message.indexOf("设置主号") + 4).trim();
                botConfig.setControlQQ(typeString);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            if (message.startsWith("添加主号")) {
                String typeString = message.substring(message.indexOf("添加主号") + 4).trim();
                botConfig.setControlQQ(botConfig.getControlQQ() + "&" + typeString);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            if (message.startsWith("设置提醒群号")) {
                String groupString = message.substring(message.indexOf("设置提醒群号") + 6).trim();
                botConfig.setGroupQQ(groupString);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            if (message.startsWith("添加提醒群号")) {
                String groupString = message.substring(message.indexOf("添加提醒群号") + 6).trim();
                botConfig.setGroupQQ(botConfig.getGroupQQ() + "&" + groupString);
                group.sendMessage((new MessageChain()).reply(messageId).text("添加成功"));
            }

            if ("一键使用追捕令".equals(message)) {
                if (botConfig.getRewardMode() == 1 && botConfig.getRewardMode() == 2) {
                    String typeString = "";
                    if (botConfig.getRewardMode() == 1) {
                        typeString = "手动模式";
                    } else if (botConfig.getRewardMode() == 2) {
                        typeString = "半自动模式";
                    }

                    group.sendMessage((new MessageChain()).reply(messageId).text("道友当前悬赏令模式为" + typeString + "! 请切换为自动模式在使用该功能吧！"));
                } else {
                    botConfig.setCommand("一键使用追捕令");
                    group.sendMessage((new MessageChain()).at("3889001741").text(" 道具使用追捕令"));
                }
            }

            if ("一键使用次元之钥".equals(message)) {
                if (botConfig.isEnableAutoSecret()) {
                    botConfig.setCommand("一键使用次元之钥");
                    group.sendMessage((new MessageChain()).at("3889001741").text(" 道具使用次元之钥"));
                } else {
                    group.sendMessage((new MessageChain()).reply(messageId).text("道友当前没有开启自动秘境结算哦！请发送 启用自动秘境 在使用该功能吧！"));
                }
            }

            if (message.startsWith("取消一键使用")) {
                botConfig.setCommand("");
                group.sendMessage((new MessageChain()).reply(messageId).text("取消成功"));
            }

            if ("听令帮助".equals(message)) {
                String typeString = "@指定账号  \n执行  = @小小\n执行命令 = 不@小小\n\n@指定账号循环执行 = @小小\n@指定账号循环执行命令 = 不@小小\n\n弟子听令\n控制所有账号执行\n弟子听令执行  = @小小\n弟子听令执行命令 = 不@小小\n弟子听令5执行 = @小小 并且按照设置的编号-1*5秒延迟执行\n弟子听令5执行命令 = 按照设置的编号-1*5秒延迟执行\n\n编号/爱称\n听令1= 不@小小\n听令2=  @小小\n听令3=  @小北\n\n编号听令1循环执行  内容\n次数\n秒速\n\n编号听令2循环执行  @小小执行内容\n次数\n秒速\n\n弟子编号.编号.编号**听令\n\n指定编号的弟子执行\n比如弟子1.3听令 \n就是控制1号和3号执行";
                group.sendMessage((new MessageChain()).reply(messageId).text(typeString));
            }

            if ("开启群管提醒".equals(message)) {
                botConfig.setEnableAutomaticReply(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }
            if ("开启妖塔挑战".equals(message) && (
                    botConfig.getChallengeMode() == 1 || botConfig.getChallengeMode() == 2)) {
                bot.sendGroupMessage(botConfig.getGroupId(), (new MessageChain()).at("3889001741").text("我的状态"));
                if (botConfig.getChallengeMode() == 1) {
                    botConfig.setChallengeMode(11);
                } else {
                    botConfig.setChallengeMode(21);
                }
            }
            if ("停止妖塔挑战".equals(message)) {
                if (botConfig.getChallengeMode() == 11 || botConfig.getChallengeMode() == 12 || botConfig.getChallengeMode() == 13) {
                    botConfig.setChallengeMode(1);
                } else if (botConfig.getChallengeMode() == 21 || botConfig.getChallengeMode() == 22 || botConfig.getChallengeMode() == 23) {
                    botConfig.setChallengeMode(2);
                }
            }


            if ("关闭群管提醒".equals(message)) {
                botConfig.setEnableAutomaticReply(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            if ("开启自助头衔".equals(message)) {
                botConfig.setEnableSelfTitle(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            if ("关闭自助头衔".equals(message)) {
                botConfig.setEnableSelfTitle(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            if ("开始自动修炼".equals(message)) {
                botConfig.setStartScheduled(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("开始自动修炼成功"));
                group.sendMessage((new MessageChain()).at("3889001741").text("修炼"));
            } else if ("停止自动修炼".equals(message)) {
                botConfig.setStartScheduled(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("停止自动修炼成功"));
            } else if (message.startsWith("修炼模式")) {
                String typeString = message.substring(message.indexOf("修炼模式") + 4).trim();
                if (StringUtils.isNotBlank(typeString)) {
                    int type = Integer.parseInt(typeString);
                    if (type == 0) {
                        if (botConfig.getCultivationMode() == 2) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("出关"));
                        } else if (botConfig.getCultivationMode() == 3) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("宗门出关"));
                        }
                    } else if (type == 1) {
                        if (botConfig.getCultivationMode() == 2) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("出关"));
                        } else if (botConfig.getCultivationMode() == 3) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("宗门出关"));
                        }
                    } else if (type == 2) {
                        if (botConfig.getCultivationMode() == 3) {
                            group.sendMessage((new MessageChain()).at("3889001741").text("宗门出关"));
                        }
                    } else if (type == 3 && botConfig.getCultivationMode() == 2) {
                        group.sendMessage((new MessageChain()).at("3889001741").text("出关"));
                    }

                    botConfig.setCultivationMode(type);
                    proccessCultivation(group);
                }
            } else if (message.startsWith("悬赏令模式")) {
                String typeString = message.substring(message.indexOf("悬赏令模式") + 5).trim();
                if (StringUtils.isNotBlank(typeString)) {
                    int type = Integer.parseInt(typeString);
                    botConfig.setRewardMode(type);
                    if (type == 1) {
                        group.sendMessage((new MessageChain()).reply(messageId).text("已开启手动悬赏"));
                    }

                    if (type == 2) {
                        group.sendMessage((new MessageChain()).reply(messageId).text("已开启半自动悬赏"));
                    }

                    if (type == 3) {
                        group.sendMessage((new MessageChain()).reply(messageId).text("已开启全自动悬赏"));
                    }
                }
            } else if (message.startsWith("设置宗门任务")) {
                String typeString = message.substring(message.indexOf("设置宗门任务") + 6).trim();
                if (StringUtils.isNotBlank(typeString)) {
                    int typex = Integer.parseInt(typeString);
                    botConfig.setSectMode(typex);
                    if (typex == 1) {
                        group.sendMessage((new MessageChain()).reply(messageId).text("启用查抄邪修宗门任务成功"));
                    } else {
                        group.sendMessage((new MessageChain()).reply(messageId).text("启用所有宗门任务成功"));
                    }
                }
            } else if ("停止自动宗门任务".equals(message)) {
                botConfig.setFamilyTaskStatus(0);
                group.sendMessage((new MessageChain()).reply(messageId).text("停止宗门任务成功"));
            } else if ("启用自动宗门任务".equals(message)) {
                botConfig.setFamilyTaskStatus(0);
                botConfig.setEnableSectMission(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("启用自动宗门任务成功"));
            } else if ("关闭自动宗门任务".equals(message)) {
                botConfig.setFamilyTaskStatus(0);
                botConfig.setEnableSectMission(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("关闭自动宗门任务成功"));
            } else if ("启用价格查询".equals(message)) {
                botConfig.setEnableCheckPrice(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("启用价格查询成功"));
            } else if ("关闭价格查询".equals(message)) {
                botConfig.setEnableCheckPrice(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("停止价格查询成功"));
            } else if ("启用猜成语查询".equals(message)) {
                botConfig.setEnableGuessTheIdiom(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("启用猜成语成功"));
            } else if ("关闭猜成语查询".equals(message)) {
                botConfig.setEnableGuessTheIdiom(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("停止猜成语成功"));
            } else if ("开启查行情".equals(message)) {
                botConfig.setEnableCheckMarket(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("启用查行情成功"));
            } else if ("停止查行情".equals(message)) {
                botConfig.setEnableCheckMarket(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("停止查行情成功"));
            } else if ("开始更新坊市".equals(message)) {
                botConfig.setStartScheduledMarket(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("开始更新坊市成功"));
            } else if ("更新坊市装备".equals(message)) {
                botConfig.setStartScheduledEquip(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("开始更新坊市成功"));
            } else if ("更新坊市技能".equals(message)) {
                botConfig.setStartScheduledSkills(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("开始更新坊市成功"));
            } else if ("更新坊市药材".equals(message)) {
                botConfig.setStartScheduledHerbs(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("开始更新坊市成功"));
            } else if ("停止更新坊市".equals(message)) {
                botConfig.setStartScheduledMarket(false);
                botConfig.setStartScheduledEquip(false);
                botConfig.setStartScheduledSkills(false);
                botConfig.setStartScheduledHerbs(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("停止更新坊市成功"));
            } else if (!message.startsWith("查询自动购买")) {
                if (message.startsWith("取消自动购买")) {
                    Map productMap = (Map) this.groupManager.autoBuyProductMap.getOrDefault(bot.getBotId() + "", new ConcurrentHashMap());
                    this.groupManager.autoBuyProductMap.put(bot.getBotId() + "", productMap);
                    message = message.substring(message.indexOf("取消自动购买") + 6).trim();
                    productMap.remove(message);
                    group.sendMessage((new MessageChain()).reply(messageId).text(message + "取消成功"));
                } else if (message.startsWith("批量取消自动购买")) {
                    Map productMap = (Map) this.groupManager.autoBuyProductMap.getOrDefault(bot.getBotId() + "", new ConcurrentHashMap());
                    this.groupManager.autoBuyProductMap.put(bot.getBotId() + "", productMap);
                    productMap.clear();
                    group.sendMessage((new MessageChain()).reply(messageId).text(message + "取消成功"));
                } else if (message.startsWith("自动购买") && message.contains(" ")) {
                    try {
                        String[] lines = message.split("\n");

                        for (String line : lines) {
                            String[] parts = line.split(" ");
                            if (parts.length >= 2) {
                                ProductPrice productPrice = new ProductPrice();
                                productPrice.setName(parts[0].substring(4).trim());
                                productPrice.setPrice(Integer.parseInt(parts[1].trim()));
                                productPrice.setTime(LocalDateTime.now());
                                Map productMap = (Map) this.groupManager.autoBuyProductMap.getOrDefault(bot.getBotId() + "", new ConcurrentHashMap());
                                productMap.put(productPrice.getName(), productPrice);
                                this.groupManager.autoBuyProductMap.put(bot.getBotId() + "", productMap);
                            }
                        }

                        group.sendMessage((new MessageChain()).reply(messageId).text("自动购买批量添加成功"));
                    } catch (Exception var19) {
                    }
                }
            } else {
                Map productMap = (Map) this.groupManager.autoBuyProductMap.getOrDefault(bot.getBotId() + "", new ConcurrentHashMap());
                this.groupManager.autoBuyProductMap.put(bot.getBotId() + "", productMap);
                StringBuilder s = new StringBuilder();

                for (Object obj : productMap.values()) {
                    ProductPrice value = (ProductPrice) obj;
                    s.append("名称：").append(value.getName()).append(" 价格:").append(value.getPrice()).append("万").append("\n");
                }

                if (s.length() > 0) {
                    group.sendMessage((new MessageChain()).reply(messageId).text(s.toString()));
                }
            }

            if ("切换提醒模式".equals(message)) {
                if (this.groupManager.taskReminder) {
                    this.groupManager.taskReminder = false;
                    group.sendMessage((new MessageChain()).reply(messageId).text("已切提醒模式为手动模式。\n注：需要复制小小消息触发！"));
                } else {
                    this.groupManager.taskReminder = true;
                    group.sendMessage((new MessageChain()).reply(messageId).text("已切提醒模式为自动模式。\n注：多人同时触发可能会误判！"));
                }

                this.groupManager.saveTasksToFile();
            }

            if (message.endsWith("一键上架") || message.endsWith("一键炼金")) {
                String finalMessage = message;
                customPool.submit(new Runnable() {
                    public void run() {
                        TestService.this.alchemyAndListed(messageChain, bot, finalMessage, group);
                    }
                });
            }
        }

    }

    private void startAutoTask(Bot bot, BotConfig botConfig, int cultivationMode, Long groupId, String command) {
        if (cultivationMode == 2) {
            bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("出关"));
        } else if (cultivationMode == 1) {
            botConfig.setStartScheduled(false);
        } else if (cultivationMode == 3) {
            if (this.isFirst) {
                this.isFirst = false;
                customPool.submit(() -> {
                    try {
                        for (Bot bot1 : BotFactory.getBots().values()) {
                            if (bot1.getBotConfig().getCultivationMode() == 3) {
                                Group group = bot1.getGroup(bot1.getBotConfig().getGroupId());
                                group.sendMessage((new MessageChain()).at("3889001741").text("宗门出关"));
                                TimeUnit.SECONDS.sleep(3L);
                            }
                        }

                        this.isFirst = true;
                    } catch (Exception var5) {
                        this.isFirst = true;
                        log.error("处理bot时出错", var5);
                    }

                });
            }
        } else if (cultivationMode == 0) {
            if ("开始自动悬赏".equals(command)) {
                botConfig.setCommand("");
                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("悬赏令刷新"));
            }

            if ("开始自动秘境".equals(command)) {
                botConfig.setCommand("");
                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("探索秘境"));
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 开始自动秘境悬赏(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        boolean isBiGuan = message.contains("闭关时长") && (message.contains("修为提升") || message.contains("修为突破")) || message.contains("闭关结束");
        boolean isXiuLian = message.contains("本次修炼增加");
        if (Utils.isAtSelf(bot, group, message, xxGroupId) && (message.contains("道友现在什么都没干呢") || isBiGuan || isXiuLian) && StringUtils.isNotBlank(botConfig.getCommand())) {
            if ("开始自动悬赏".equals(botConfig.getCommand())) {
                botConfig.setCommand("");

                try {
                    TimeUnit.SECONDS.sleep(2L);
                    bot.getGroup(botConfig.getGroupId()).sendMessage((new MessageChain()).at("3889001741").text("悬赏令刷新"));
                } catch (InterruptedException var12) {
                    throw new RuntimeException(var12);
                }
            }

            if ("开始自动秘境".equals(botConfig.getCommand())) {
                botConfig.setCommand("");

                try {
                    TimeUnit.SECONDS.sleep(2L);
                    bot.getGroup(botConfig.getGroupId()).sendMessage((new MessageChain()).at("3889001741").text("探索秘境"));
                } catch (InterruptedException var11) {
                    throw new RuntimeException(var11);
                }
            }
        }

    }

    public void alchemyAndListed(MessageChain messageChain, Bot bot, String message, Group group) {
        BotConfig botConfig = bot.getBotConfig();
        List<ReplyMessage> replyMessageList = messageChain.getMessageByType(ReplyMessage.class);
        if (replyMessageList != null && !replyMessageList.isEmpty()) {
            ReplyMessage replyMessage = (ReplyMessage) replyMessageList.get(0);
            MessageChain replyMessageChain = replyMessage.getChain();
            if (replyMessageChain != null) {
                List<TextMessage> textMessageList = replyMessageChain.getMessageByType(TextMessage.class);
                if (textMessageList != null && !textMessageList.isEmpty()) {
                    TextMessage textMessage = (TextMessage) textMessageList.get(textMessageList.size() - 1);
                    String herbsInfo = textMessage.getText();
                    String[] lines = herbsInfo.split("\n");

                    for (int i = 0; i < lines.length - 1; ++i) {
                        String line = lines[i];
                        if (line.contains("道具")) {
                            break;
                        }

                        if (line.startsWith("名字：") || line.startsWith("上品") || line.startsWith("下品") || line.endsWith("功法") || line.endsWith("神通")) {
                            String name = "";
                            if (!line.contains("极品神通") && !line.contains("辅修") || !message.endsWith("一键炼金")) {
                                if (line.startsWith("名字：")) {
                                    name = line.substring(3).trim();
                                } else if (!line.endsWith("功法") && !line.endsWith("神通")) {
                                    if (line.startsWith("上品") || line.startsWith("下品") || line.startsWith("极品") || line.startsWith("无上仙器")) {
                                        name = line.substring(4).trim();
                                    }
                                } else if (line.contains("辅修")) {
                                    name = line.substring(0, line.length() - 8).trim();
                                } else if (!line.startsWith("极品神通")) {
                                    name = line.substring(0, line.length() - 6).trim();
                                }

                                if (name.startsWith("法器")) {
                                    name = name.substring(2);
                                }

                                lines[i + 1] = lines[i + 1].replace("已装备", "");
                                int quantity = 1;
                                if (lines[i + 1].contains("拥有数量")) {
                                    Pattern pattern = Pattern.compile("\\d+");
                                    Matcher matcher = pattern.matcher(lines[i + 1]);
                                    if (matcher.find()) {
                                        String numberStr = matcher.group();
                                        quantity = Integer.parseInt(numberStr);
                                    }
                                }

                                name = name.replaceAll("\\s", "");
                                if (StringUtils.isNotBlank(name)) {
                                    boolean b = !"渡厄丹,寒铁铸心炉,陨铁炉,雕花紫铜炉".contains(name);
                                    boolean isMakeDan = !Constant.MAKE_DAN_SET.contains(name);
                                    if (message.endsWith("一键炼金") && b && isMakeDan && !this.groupManager.isAlchemyExcluded(bot.getBotId(), name)) {
                                        if (botConfig.isStop()) {
                                            botConfig.setStop(false);
                                            return;
                                        }

                                        group.sendMessage((new MessageChain()).at("3889001741").text("炼金 " + name + " " + quantity));

                                        try {
                                            Thread.sleep(3000L);
                                        } catch (InterruptedException var24) {
                                            var24.printStackTrace();
                                        }
                                    } else {
                                        ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(name.trim());
                                        if (first != null) {
                                            if ((double) first.getPrice() < (double) ProductLowPrice.getLowPrice(name) * 1.1) {
                                                group.sendMessage((new MessageChain()).text("物品：" + first.getName() + "市场价：" + first.getPrice() + "万，炼金：" + ProductLowPrice.getLowPrice(name) + "万，不上架。"));
                                            } else if (message.endsWith("一键上架") && !this.groupManager.isSellExcluded(bot.getBotId(), name)) {
                                                int remaining = quantity;

                                                while (remaining > 0) {
                                                    if (botConfig.isStop()) {
                                                        botConfig.setStop(false);
                                                        return;
                                                    }

                                                    int batchSize = Math.min(10, remaining);
                                                    if (first.getPrice() > 1000 && (double) (first.getPrice() - 10) * 0.85 < (double) 900.0F) {
                                                        group.sendMessage((new MessageChain()).at("3889001741").text("确认坊市上架 " + first.getName() + " " + 10000000 + " " + batchSize));
                                                    } else {
                                                        group.sendMessage((new MessageChain()).at("3889001741").text("确认坊市上架 " + first.getName() + " " + (first.getPrice() - 10) * 10000 + " " + batchSize));
                                                    }

                                                    remaining -= batchSize;

                                                    try {
                                                        Thread.sleep(4000L);
                                                    } catch (InterruptedException var23) {
                                                        var23.printStackTrace();
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    botConfig.setCommand("");
                }
            }
        }

    }

    private String showReplyMessage(String message, BotConfig botConfig, Bot bot) {
        StringBuilder sb = new StringBuilder();
        if (message.equals("命令")) {
            sb.append("－－－－－功能设置－－－－－\n");
            sb.append("启用/关闭悬赏令价格查询\n");
            sb.append("启用/关闭自动秘境\n");
            sb.append("启用/关闭无偿双修\n");
            sb.append("开始/停止捡漏\n");
            sb.append("开始/停止一键刷灵根\n");
            sb.append("开始/停止自动修炼\n");
            sb.append("修炼模式(0无1修炼2闭关3宗门闭关)\n");
            sb.append("启用/关闭/停止自动宗门任务\n");
            sb.append("设置宗门任务(1邪修查抄2所有)\n");
            sb.append("悬赏令模式(1手动2半自动3自动)\n");
            sb.append("悬赏优先价值/修为/时长最短\n");
            sb.append("启用/关闭价格查询\n");
            sb.append("启用/关闭猜成语查询\n");
            sb.append("开始/停止更新坊市\n");
            sb.append("自动购买××(物品 价格单位：万)\n");
            sb.append("取消自动购买××\n");
            sb.append("批量取消自动购买\n");
            sb.append("查询自动购买\n");
            sb.append("循环执行××\n");
            sb.append("循环执行命令××\n");
            sb.append("引用背包 一键上架/炼金\n");
            sb.append("悬赏价格限制 ××\n");
            sb.append("设置定时任务\n");
            sb.append("任务统计\n");
            sb.append("－－－－－快捷命令－－－－－\n");
            sb.append("确认一键丹药炼金\n");
            sb.append("确认一键装备炼金\n");
            sb.append("批量上架药材\n");
            sb.append("一键使用次元之钥\n");
            sb.append("一键使用追捕令\n");
            sb.append("开始自动悬赏/秘境/宗门任务\n");

            sb.append("－－－－－掌门命令－－－－－\n");
            sb.append("编号/爱称听令1(不@)\n");
            sb.append("编号/爱称听令2(@小小)\n");
            sb.append("编号/爱称听令3(@小北)\n");
            sb.append("弟子听令执行××\n");
            sb.append("弟子听令执行命令××\n");
            sb.append("弟子听令循环执行××\n");
            sb.append("弟子听令循环执行命令××\n");
            sb.append("查看/添加/移除炼金排除物品××&××\n");
            sb.append("－－－－－其它设置－－－－－\n");
            sb.append("设置主号 QQ号&QQ号\n");
            sb.append("设置提醒群号 QQ群号&QQ群号\n");
            return sb.toString();
        } else {
            if (message.equals("当前设置")) {
                sb.append("－－－－－当前设置－－－－－\n");
                sb.append(Constant.padRight("自动秘境", 11) + ": " + (botConfig.isEnableAutoSecret() ? "启用" : "关闭") + "\n");
                sb.append(Constant.padRight("无偿双修", 11) + ": " + (botConfig.isEnableAutoRepair() ? "启用" : "关闭") + "\n");
                sb.append(Constant.padRight("捡漏模式", 11) + ": " + (botConfig.isEnableAutoBuyLowPrice() ? "启用" : "关闭") + "\n");
                sb.append(Constant.padRight("价格查询", 11) + ": " + (botConfig.isEnableCheckPrice() ? "启用" : "关闭") + "\n");
                int cultivationMode = botConfig.getCultivationMode();
                String cultivation = "";
                if (cultivationMode == 0) {
                    cultivation = "无";
                } else if (cultivationMode == 1) {
                    cultivation = "修炼";
                } else if (cultivationMode == 2) {
                    cultivation = "闭关";
                } else if (cultivationMode == 3) {
                    cultivation = "宗门闭关";
                }

                sb.append(Constant.padRight("修炼模式", 11) + ": " + cultivation + "\n");
                sb.append(Constant.padRight("自动修炼", 11) + ": " + (botConfig.isStartScheduled() ? "启用" : "关闭") + "\n");
                int rewardMode = botConfig.getRewardMode();
                if (rewardMode == 1) {
                    sb.append(Constant.padRight("自动悬赏令", 9) + ": 关闭\n");
                    sb.append(Constant.padRight("悬赏令模式", 9) + ": 手动\n");
                } else if (rewardMode == 2) {
                    sb.append(Constant.padRight("自动悬赏令", 9) + ": 关闭\n");
                    sb.append(Constant.padRight("悬赏令模式", 9) + ": 半自动\n");
                } else if (rewardMode == 3) {
                    sb.append(Constant.padRight("自动悬赏令", 9) + ": 自动\n");
                    sb.append(Constant.padRight("悬赏令模式", 9) + ": 优先价值\n");
                } else if (rewardMode == 4) {
                    sb.append(Constant.padRight("自动悬赏令", 9) + ": 自动\n");
                    sb.append(Constant.padRight("悬赏令模式", 9) + ": 优先时长最短\n");
                } else if (rewardMode == 5) {
                    sb.append(Constant.padRight("自动悬赏令", 9) + ": 自动\n");
                    sb.append(Constant.padRight("悬赏令模式", 9) + ": 优先修为\n");
                }

                sb.append(Constant.padRight("自动宗门任务", 0) + ": " + (botConfig.isEnableSectMission() ? "启用" : "关闭") + "\n");
                sb.append(Constant.padRight("宗门任务模式", 0) + ": " + (botConfig.getSectMode() == 1 ? "邪修查抄" : "所有") + "\n");
                sb.append(Constant.padRight("悬赏令价格查询", 0) + ": " + (botConfig.isEnableXslPriceQuery() ? "启用" : "关闭") + "\n");
                sb.append(Constant.padRight("猜成语查询", 9) + ": " + (botConfig.isEnableGuessTheIdiom() ? "启用" : "关闭") + "\n");
                if (botConfig.getLastExecuteTime() == 0L) {
                    sb.append(Constant.padRight("灵田收取时间", 9) + ": 无\n");
                } else {
                    sb.append(Constant.padRight("灵田收取时间", 9) + ": " + FamilyTask.sdf.format(new Date(botConfig.getLastExecuteTime() + 172800000L)) + "\n");
                }

                sb.append("－－－－－其它设置－－－－－\n");
                if (StringUtils.isNotBlank(botConfig.getControlQQ())) {
                    sb.append(Constant.padRight("主号", 3) + ": " + botConfig.getControlQQ() + "\n");
                } else if (botConfig.getMasterQQ() != 0L) {
                    sb.append(Constant.padRight("主号", 3) + ": " + botConfig.getMasterQQ() + "\n");
                }

                if (bot.getBotId() == 3860863656L) {
                    sb.append(Constant.padRight("提醒群号", 3) + ": " + botConfig.getGroupQQ() + "\n");
                }
            }

            return sb.toString();
        }
    }

    @GroupMessageHandler
    public void autoSend修炼(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        if (Utils.isAtSelf(bot, group, message, xxGroupId) && message.contains("" + bot.getBotId())) {
            if ((message.contains("本次修炼增加") || message.contains("本次挖矿获取")) && botConfig.getCultivationMode() == 1 && botConfig.isStartScheduled()) {
                botConfig.setXslTime(-1L);
                botConfig.setMjTime(-1L);
                botConfig.setLastSendTime(System.currentTimeMillis());
                bot.getGroup(botConfig.getGroupId()).sendMessage((new MessageChain()).at("3889001741").text("修炼"));
            } else if (message.contains("本次修炼增加")) {
                LocalTime now = LocalTime.now();
                if ((now.getHour() != 12 || now.getMinute() != 30 && now.getMinute() != 31 && now.getMinute() != 32) && botConfig.getCultivationMode() != 1) {
                    proccessCultivation(group);
                }
            }

            if (message.contains("正在宗门闭关室") || message.contains("现在在闭关")) {
                botConfig.setXslTime(-1L);
                botConfig.setMjTime(-1L);
                if (botConfig.getCultivationMode() == 1 && message.contains("现在在闭关")) {
                    group.sendMessage((new MessageChain()).at("3889001741").text("出关"));
                    Thread.sleep(1000L);
                    group.sendMessage((new MessageChain()).at("3889001741").text("修炼"));
                } else if (botConfig.getCultivationMode() == 1 && message.contains("正在宗门闭关室")) {
                    group.sendMessage((new MessageChain()).at("3889001741").text("宗门出关"));
                    Thread.sleep(1000L);
                    group.sendMessage((new MessageChain()).at("3889001741").text("修炼"));
                } else {
                    botConfig.setStartScheduled(false);
                }
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 一键炼金上架(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isAtSelf = Utils.isAtSelf(bot, group, message, xxGroupId);
        if (isAtSelf && message.contains("的丹药背包")) {
            BotConfig botConfig = bot.getBotConfig();
            if (StringUtils.isNotBlank(botConfig.getCommand()) && botConfig.getCommand().equals("确认一键丹药炼金")) {
                botConfig.setCommand("");
                group.sendMessage((new MessageChain()).reply(messageId).text("一键炼金"));
            }
        }

        if (isAtSelf && message.contains("的背包")) {
            BotConfig botConfig = bot.getBotConfig();
            if (StringUtils.isNotBlank(botConfig.getCommand()) && botConfig.getCommand().equals("确认一键装备炼金")) {
                botConfig.setCommand("");
                group.sendMessage((new MessageChain()).reply(messageId).text("一键炼金"));
            }
        }

        if (isAtSelf && message.contains("的药材背包")) {
            BotConfig botConfig = bot.getBotConfig();
            if (StringUtils.isNotBlank(botConfig.getCommand()) && botConfig.getCommand().equals("确认一键药材上架")) {
                group.sendMessage((new MessageChain()).reply(messageId).text("一键上架"));
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 自动刷天赋(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isAtSelf = Utils.isAtSelf(bot, group, message, xxGroupId);
        if (this.isStartAutoTalent && isAtSelf && message.contains("保留24h，超时则无法选择")) {
            List<TextMessage> messageList = messageChain.getMessageByType(TextMessage.class);
            String text = ((TextMessage) messageList.get(messageList.size() - 1)).getText();
            if (this.checkStats(text)) {
                this.isStartAutoTalent = false;
            } else {
                group.sendMessage((new MessageChain()).at("3889001741").text("确认天赋保留左边"));
            }
        }

        if (this.isStartAutoTalent && isAtSelf && message.contains("成功保留")) {
            group.sendMessage((new MessageChain()).at("3889001741").text("道具使用涅槃造化丹"));
        }

    }

    public boolean checkStats(String input) {
        Pattern pattern = Pattern.compile("(贪狼|巨门|禄存|文曲|廉贞|武曲|破军)\\（[^）]+\\）：(\\d+)(%?) -> (\\d+)(%?)");
        boolean luCunValid = false;
        boolean wuQuValid = false;
        boolean poJunValid = false;
        String[] lines = input.split("\n");

        for (String line : lines) {
            Matcher matcher = pattern.matcher(line.trim());
            if (matcher.find()) {
                String statName = matcher.group(1);
                int leftValue = Integer.parseInt(matcher.group(2));
                int rightValue = Integer.parseInt(matcher.group(4));
                switch (statName) {
                    case "禄存":
                        luCunValid = rightValue >= leftValue;
                        break;
                    case "武曲":
                        wuQuValid = rightValue >= leftValue;
                        break;
                    case "破军":
                        poJunValid = rightValue >= leftValue;
                }
            }
        }

        return luCunValid && wuQuValid && poJunValid;
    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 艾特小号执行(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        if (botConfig.getLingShiQQ() != null && group.getGroupId() == botConfig.getLingShiQQ()) {
            messageChain = this.getMessageText(messageChain);
            message = ((TextMessage) messageChain.get(0)).getText().trim();
            if (group.getGroupId() == botConfig.getLingShiQQ() || this.checkControlQQ(bot, member)) {
                this.setLingShiNum(bot, group, member, messageChain, message);
            }
        }

        if (this.checkControlQQ(bot, member)) {
            messageChain = this.getMessageText(messageChain);
            message = ((TextMessage) messageChain.get(0)).getText().trim();
            if (this.checkControlQQ(bot, member)) {
                this.setLingShiNum(bot, group, member, messageChain, message);
            }

            if (this.checkControlQQ(bot, member)) {
                this.clickButton(bot, group, member, messageChain, message);
            }
        }

    }

    private MessageChain getMessageText(MessageChain messageChain) {
        for (Iterator iterator = messageChain.iterator(); iterator.hasNext(); iterator.remove()) {
            Message timeMessage = (Message) iterator.next();
            if (timeMessage instanceof TextMessage) {
                String text = ((TextMessage) timeMessage).getText().trim();
                if (StringUtils.isNotBlank(text)) {
                    break;
                }
            }
        }

        return messageChain;
    }

    private void setLingShiNum(Bot bot, Group group, Member member, MessageChain messageChain, String message) {
        if (message.contains("我要灵石")) {
            BotConfig botConfig = bot.getBotConfig();
            String num = message.substring("我要灵石".length()).trim();
            if (StringUtils.isNumeric(num)) {
                botConfig.setLingShiNum(Integer.parseInt(num));
                group.sendMessage((new MessageChain()).text(num + "万灵石已经准备好了，请发送你的接收码吧!"));
            } else {
                group.sendMessage((new MessageChain()).text("请输入正确的数量"));
            }
        }

    }

    private void clickButton(Bot bot, Group group, Member member, MessageChain messageChain, String message) {
        if (message.contains("点击")) {
            try {
                if (!message.contains("点击文本") && !message.contains("点击按钮")) {
                    if (message.contains("点击序号")) {
                        String position = message.substring("点击序号".length()).trim();
                        if (!StringUtils.isNumeric(position)) {
                            return;
                        }

                        Buttons buttons = (Buttons) this.botButtonMap.get(bot.getBotId());
                        if (buttons != null && !buttons.getButtonList().isEmpty() && Integer.parseInt(position) <= buttons.getButtonList().size()) {
                            Button button = (Button) buttons.getButtonList().get(Integer.parseInt(position) - 1);
                            bot.clickKeyboardButton(buttons.getGroupId(), buttons.getBotAppid(), button.getId(), button.getData(), buttons.getMsgSeq());
                            return;
                        }
                    } else if (message.contains("点击")) {
                        String text = message.substring("点击".length()).trim();
                        if (GuessIdiom.getEmoji(text) != null) {
                            text = GuessIdiom.getEmoji(text);
                        }

                        if (StringUtils.isNotBlank(text)) {
                            Buttons buttons = (Buttons) this.botButtonMap.get(bot.getBotId());
                            if (buttons != null && !buttons.getButtonList().isEmpty()) {
                                for (Button button : buttons.getButtonList()) {
                                    if (text.equals(button.getLabel())) {
                                        bot.clickKeyboardButton(buttons.getGroupId(), buttons.getBotAppid(), button.getId(), button.getData(), buttons.getMsgSeq());
                                        return;
                                    }
                                }
                            }
                        }
                    }
                } else {
                    String textx = message.substring("点击文本".length()).trim();
                    if (GuessIdiom.getEmoji(textx) != null) {
                        textx = GuessIdiom.getEmoji(textx);
                    }

                    if (StringUtils.isNotBlank(textx)) {
                        Buttons buttons = (Buttons) this.botButtonMap.get(bot.getBotId());
                        if (buttons != null && !buttons.getButtonList().isEmpty()) {
                            for (Button buttonx : buttons.getButtonList()) {
                                if (textx.equals(buttonx.getLabel())) {
                                    bot.clickKeyboardButton(buttons.getGroupId(), buttons.getBotAppid(), buttonx.getId(), buttonx.getData(), buttons.getMsgSeq());
                                    return;
                                }
                            }
                        }
                    }
                }

                group.sendMessage((new MessageChain()).text("未找到对应按钮信息"));
            } catch (Exception var11) {
                group.sendMessage((new MessageChain()).text("点击失败，格式错误"));
                var11.printStackTrace();
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 识别大号接收码(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        if (message.contains("您的接收码为") && botConfig.getLingShiNum() > 0 && botConfig.getLingShiQQ() == group.getGroupId()) {
            String code = message.split("您的接收码为：| ")[1];
            group.sendMessage((new MessageChain()).at("3889001741").text("赠送灵石 ").text(code + " ").text(botConfig.getLingShiNum() * 10000 + ""));
        }

    }

    private String getAtMessageQQ(MessageChain messageChain) {
        for (Message timeMessage : messageChain) {
            if (timeMessage instanceof AtMessage) {
                String text = ((AtMessage) timeMessage).getQq();
                if (StringUtils.isNotBlank(text)) {
                    return text;
                }
            }
        }

        return "";
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 自动点击按钮(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId, Buttons buttons) {
        if (buttons != null && !buttons.getButtonList().isEmpty() && message.contains("请确认是否") && message.contains("灵石")) {
            String pattern = "at_tinyid=(\\d+)";
            Pattern regex = Pattern.compile(pattern);
            Matcher matcher = regex.matcher(message);
            if (matcher.find() && (bot.getBotId() + "").equals(matcher.group(1)) && !buttons.getButtonList().isEmpty()) {
                for (Button button : buttons.getButtonList()) {
                    if ("确认".equals(button.getLabel())) {
                        bot.clickKeyboardButton(group.getGroupId(), buttons.getBotAppid(), button.getId(), button.getData(), buttons.getMsgSeq());
                        return;
                    }
                }
            }
        }

    }

    public long getRemindGroupId(Bot bot) {
        long groupId = bot.getBotConfig().getGroupId();
        long taskId = bot.getBotConfig().getTaskId();
        if (taskId > 0L) {
            return taskId;
        } else {
            return this.xxGroupId != 0L ? this.xxGroupId : groupId;
        }
    }

    public void showButtonMsg(Bot bot, Group group, Integer messageId, String message, Buttons buttons, MessageChain messageChain) {
        if (xxGroupId == 0) {
            return;
        }
        long groupId = this.getRemindGroupId(bot);
        Bot remindBot = this.getRemindAtQQ(bot);
        if (remindBot != null) {
            if (buttons != null && !buttons.getButtonList().isEmpty() && buttons.getButtonList().size() > 13) {
                String regex = "https?://[^\\s\\)]+";
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(message);

                while (matcher.find()) {
                    buttons.setImageUrl(matcher.group());
                    buttons.setImageText(((Message) messageChain.get(messageChain.size() - 1)).toString());
                }

                List<Button> buttonList = buttons.getButtonList();
                StringBuilder buttonBuilder = new StringBuilder();
                boolean isSelfGroup = group.getGroupId() == bot.getBotConfig().getGroupId() || this.xxGroupId == group.getGroupId();
                if (isSelfGroup) {
                    buttonBuilder.append(buttons.getImageText());
                    buttonBuilder.append("\n");
                    buttonBuilder.append("\n");
                    buttonBuilder.append("@我+点击序号+对应答案前面的序号");
                    buttonBuilder.append("\n");
                    buttonBuilder.append("\n");
                    buttonBuilder.append("【");
                    if (buttonList.size() == 16 || buttonList.size() == 15) {
                        int index = 0;

                        for (int i = 0; i < 5 && index < buttonList.size(); ++index) {
                            Button button = (Button) buttonList.get(index);
                            buttonBuilder.append(" ").append(index + 1).append(" ").append(button.getLabel()).append(" ");
                            ++i;
                        }

                        buttonBuilder.append("】");
                        buttonBuilder.append("\n");

                        for (int i = 0; i < 4 && index < buttonList.size(); ++index) {
                            Button button = (Button) buttonList.get(index);
                            buttonBuilder.append("【").append(index + 1).append("】").append(button.getLabel()).append(" ");
                            ++i;
                        }

                        buttonBuilder.append("\n");

                        for (int i = 0; i < 3 && index < buttonList.size(); ++index) {
                            Button button = (Button) buttonList.get(index);
                            buttonBuilder.append("【").append(index + 1).append("】").append(button.getLabel()).append("  ");
                            ++i;
                        }

                        buttonBuilder.append("\n");
                        if (buttonList.size() == 15) {
                            for (int i = 0; i < 3 && index < buttonList.size(); ++index) {
                                Button button = (Button) buttonList.get(index);
                                buttonBuilder.append("【").append(index + 1).append("】").append(button.getLabel()).append(" ");
                                ++i;
                            }
                        } else {
                            for (int i = 0; i < 4 && index < buttonList.size(); ++index) {
                                Button button = (Button) buttonList.get(index);
                                buttonBuilder.append("【").append(index + 1).append("】").append(button.getLabel()).append(" ");
                                ++i;
                            }
                        }
                    }
                    MessageChain messageChain1 = new MessageChain();
                    messageChain1.at(remindBot.getBotConfig().getMasterQQ() + "").text("\n").image(buttons.getImageUrl()).text(buttonBuilder.toString());
                    bot.getGroup(groupId).sendMessage(messageChain1);
                }


            } else {
                remindBot.getGroup(groupId).sendMessage((new MessageChain()).at(String.valueOf(remindBot.getBotConfig().getMasterQQ())).text(bot.getBotName() + "的" + group.getGroupName() + "出现验证码！！！！"));
            }
        }

    }

    public String[] callShituAPI(String shituApiUrl, String imageUrl, String titleText, String annu, String mode) {
        HttpURLConnection conn = null;
        annu = GuessIdiom.replaceEmojis(annu);
        try {
            String params = "URL=" + URLEncoder.encode(imageUrl, "UTF-8") + "&TEXT=" + URLEncoder.encode(titleText, "UTF-8") + "&Button=" + URLEncoder.encode(annu, "UTF-8");
            URL url = new URL(shituApiUrl);
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
                log.warn("API返回非200状态: {}", status);
                String[] var32 = new String[]{"服务错误", String.valueOf(status)};
                return var32;
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
            log.info("API响应: {}", var33);
            JSONObject jsonResponse = JSONObject.parseObject(var33);
            String message = jsonResponse.getString("message");
            String data = jsonResponse.getString("data");
            return new String[]{message, data};
        } catch (SocketTimeoutException ste) {
            log.warn("API读取超时: {}", ste.getMessage());
            String[] var29 = new String[]{"请求超时", "0"};
            return var29;
        } catch (Exception e) {
            log.error("API调用异常: {}", e.getMessage());
            String[] url = new String[]{"请求异常", "0"};
            return url;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }

        }

//        return var13;
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 验证码判断(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId, Buttons buttons) {
        if (message.contains("解除限制") && bot.getBotConfig().getGroupId() == group.getGroupId()) {
            bot.getBotConfig().setStop(true);
            bot.getBotConfig().setLastRefreshTime(System.currentTimeMillis() + 300000L);
            bot.getBotConfig().setFamilyTaskStatus(0);
            bot.getBotConfig().setCultivationMode(0);
            bot.getBotConfig().setStartScheduledMarket(false);
            bot.getBotConfig().setStartScheduledEquip(false);
            bot.getBotConfig().setStartScheduledSkills(false);
            bot.getBotConfig().setStartScheduledHerbs(false);
            bot.getBotConfig().setLastExecuteTime(9223372036854175807L);
            bot.getBotConfig().setVerificationStatus("失败");
            bot.getBotConfig().setEnableAutoBuyLowPrice(false);
            bot.getBotConfig().setAutoBuyHerbsMode(0);
        }

        if (message.contains("https") && message.contains("qqbot") && (!message.contains("修仙信息") || !message.contains("统计信息") || !message.contains("道号")) && message.contains("" + bot.getBotId()) && (!message.contains("方向要求") || !message.contains("随机事件"))) {
            BotConfig botConfig = bot.getBotConfig();
            botConfig.setStop(true);
            botConfig.setLastRefreshTime(System.currentTimeMillis() + 300000L);
            botConfig.setStartScheduledMarket(false);
            botConfig.setStartScheduledEquip(false);
            botConfig.setStartScheduledSkills(false);
            botConfig.setStartScheduledHerbs(false);
            if (buttons != null && !buttons.getButtonList().isEmpty()) {
                botButtonMap.put(bot.getBotId(), buttons);
                buttons.setGroupId(group.getGroupId());

                if (bot.getBotConfig().getAutoVerifyModel() == 0) {
                    showButtonMsg(bot, group, messageId, message, buttons, messageChain);
                }
                String regex = "https?://[^\\s\\)]+";
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(message);

                while (matcher.find()) {
                    buttons.setImageUrl(matcher.group());
                    buttons.setImageText(((Message) messageChain.get(messageChain.size() - 1)).toString());
                }
                if (StringUtils.isNotBlank(shituApiUrl)) {
                    customPool.submit(new Runnable() {
                        public void run() {
                            getPictureText(bot, botConfig, buttons, message, messageChain);
                        }
                    });

                }


            }
        }

        if ((message.contains("双修次数已经到达上限") || message.contains("请输入你道侣的道号,与其一起双修！") || message.contains("不屑一顾，扬长而去！")) && message.contains("" + bot.getBotId())) {
            bot.getBotConfig().setStop(true);

            try {
                Thread.sleep(5000L);
                bot.getBotConfig().setStop(false);
            } catch (InterruptedException var22) {
            }
        }

        if ((message.contains("奖励") && message.contains("灵石") || message.contains("不需要验证") || message.contains("验证码已过期")) && message.contains("" + bot.getBotId())) {
            bot.getBotConfig().setStop(false);
            if (message.contains("奖励") && message.contains("灵石") && StringUtils.isNotBlank(bot.getBotConfig().getCommand())) {
                bot.getBotConfig().setVerificationStatus("");
                if (!"批量上架药材".equals(bot.getBotConfig().getCommand())
                        && !"一键使用追捕令".equals(bot.getBotConfig().getCommand()) && !"一键使用次元之钥".equals(bot.getBotConfig().getCommand())) {
                    if (!bot.getBotConfig().isEnableAutoBuyLowPrice()) {
                        group.sendMessage((new MessageChain()).text(bot.getBotConfig().getCommand()));
                    }
                }
            } else if (StringUtils.isNotBlank(bot.getBotConfig().getCommand())) {
                if (!bot.getBotConfig().isEnableAutoBuyLowPrice()) {
                    bot.getBotConfig().setCommand("");
                }
                bot.getBotConfig().setVerificationStatus("失败");
            }
        }

    }

    private void getPictureText(Bot bot, BotConfig botConfig, Buttons buttons, String message, MessageChain messageChain) {

        botConfig.setLastVerificationContent(buttons.getImageUrl());
        List<Button> buttonList = buttons.getButtonList();
        StringBuilder buttontextBuilder = new StringBuilder();

        for (Button button : buttonList) {
            buttontextBuilder.append(button.getLabel()).append("|");
        }

        String buttontext = buttontextBuilder.length() > 0 ? buttontextBuilder.substring(0, buttontextBuilder.length() - 1) : "";
        String[] shituResult = this.callShituAPI(shituApiUrl, buttons.getImageUrl(), buttons.getImageText(), buttontext, "2");
        String resultText = shituResult[0];
        String statusCode = shituResult[1];
        System.out.println("结果1：" + resultText + "\n结果2：" + statusCode);
        if ("点击按键".equals(resultText)) {
            String textx = statusCode;
            if (GuessIdiom.getEmoji(statusCode) != null) {
                textx = GuessIdiom.getEmoji(statusCode);
            }

            if (StringUtils.isNotBlank(textx)) {
                Buttons buttonsS = (Buttons) this.botButtonMap.get(bot.getBotId());
                if (buttonsS != null && !buttonsS.getButtonList().isEmpty()) {
                    for (Button buttonx : buttonsS.getButtonList()) {
                        if (textx.equals(buttonx.getLabel())) {
                            bot.clickKeyboardButton(buttonsS.getGroupId(), buttonsS.getBotAppid(), buttonx.getId(), buttonx.getData(), buttonsS.getMsgSeq());
                            return;
                        }
                    }
                }
            }
        } else if ("点击表情".equals(resultText)) {
            if (!StringUtils.isNumeric(statusCode)) {
                return;
            }

            Buttons buttons1 = (Buttons) this.botButtonMap.get(bot.getBotId());
            if (buttons1 != null && !buttons1.getButtonList().isEmpty() && Integer.parseInt(statusCode) <= buttons1.getButtonList().size()) {
                Button button = (Button) buttons1.getButtonList().get(Integer.parseInt(statusCode) - 1);
                bot.clickKeyboardButton(buttons1.getGroupId(), buttons1.getBotAppid(), button.getId(), button.getData(), buttons1.getMsgSeq());
                return;
            }
        } else if ("无".equals(resultText)) {
            Utils.downLoadImage(buttons.getImageUrl(), "errorPic");
            bot.getGroup(xxGroupId).sendMessage((new MessageChain()).text("识别失败请手动点击！"));
            showButtonMsg(bot, bot.getGroup(xxGroupId), 0, message, buttons, messageChain);
        }
    }

    private Bot getRemindAtQQ(Bot bot) {
        if (bot.getBotConfig().getMasterQQ() != 0L) {
            if (bot.getBotConfig().getMasterQQ() != bot.getBotId()) {
                return bot;
            }

            for (Bot bot1 : BotFactory.getBots().values()) {
                try {
                    if (bot1.getBotConfig().getGroupId() == bot.getBotConfig().getGroupId() && bot1.getBotId() != bot.getBotId()) {
                        return bot1;
                    }
                } catch (Exception var5) {
                }
            }
        }

        return null;
    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 执行命令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (this.checkControlQQ(bot, member) && (message.contains("执行") || message.contains("听令"))) {
            for (Iterator iterator = messageChain.iterator(); iterator.hasNext(); iterator.remove()) {
                Message timeMessage = (Message) iterator.next();
                if (timeMessage instanceof TextMessage) {
                    String text = ((TextMessage) timeMessage).getText().trim();
                    if (StringUtils.isNotBlank(text)) {
                        break;
                    }
                }
            }

            message = ((TextMessage) messageChain.get(0)).getText().trim();
            Message timeMessage = (Message) messageChain.get(messageChain.size() - 1);
            int count;
            int time;
            if (timeMessage instanceof TextMessage && message.contains("循环")) {
                String textKeyword = ((TextMessage) timeMessage).getText().trim();
                String[] split = textKeyword.split("\n");
                if (split.length >= 2) {
                    int countTemp = 0;
                    int numberKeyword = 5;

                    try {
                        countTemp = Integer.parseInt(split[split.length - 2]);
                        numberKeyword = Integer.parseInt(split[split.length - 1]);
                        message = textKeyword.substring(0, textKeyword.length() - ("\n" + countTemp + "\n" + numberKeyword).length()).trim();
                        ((TextMessage) timeMessage).setText(message);
                    } catch (Exception var15) {
                    }

                    count = countTemp;
                    time = numberKeyword;
                } else {
                    count = 0;
                    time = 0;
                }
            } else {
                count = 0;
                time = 0;
            }

            message = ((TextMessage) messageChain.get(0)).getText().trim();
            if (message.startsWith("循环执行命令")) {
                messageChain.set(0, new TextMessage(message.substring(message.indexOf("循环执行命令") + 6)));
                this.forSendMessage(bot, group, messageChain, count, time, 2);
            } else if (message.startsWith("循环执行") && !message.startsWith("循环执行1")) {
                messageChain.set(0, new TextMessage(message.substring(message.indexOf("循环执行") + 4)));
                messageChain.add(0, new AtMessage("3889001741"));
                this.forSendMessage(bot, group, messageChain, count, time, 1);
            } else if (message.startsWith("循环执行1")) {
                messageChain.set(0, new TextMessage(message.substring(message.indexOf("循环执行1") + 5)));
                messageChain.add(0, new AtMessage("3889001741"));
                this.forSendMessage(bot, bot.getGroup(bot.getBotConfig().getGroupId()), messageChain, count, time, 1);
            } else if (message.startsWith("弟子听令循环执行命令")) {
                messageChain.set(0, new TextMessage(message.substring(message.indexOf("弟子听令循环执行命令") + 10)));
                this.executeSendAllMessage(group, messageChain, count, time);
            } else if (message.startsWith("弟子听令循环执行")) {
                messageChain.set(0, new TextMessage(message.substring(message.indexOf("弟子听令循环执行") + 8)));
                messageChain.add(0, new AtMessage("3889001741"));
                this.executeSendAllMessage(group, messageChain, count, time);
            } else if (message.startsWith("执行命令")) {
                messageChain.set(0, new TextMessage(message.substring(message.indexOf("执行命令") + 4)));
                group.sendMessage(messageChain);
            } else if (message.startsWith("执行")) {
                String textMessage = message.substring(message.indexOf("执行") + 2);
                messageChain.set(0, new TextMessage(textMessage));
                messageChain.add(0, new AtMessage("3889001741"));
                Stream<String> stream = commandWords.stream();
                Objects.requireNonNull(textMessage);
                if (stream.anyMatch(textMessage::contains)) {
                    bot.getGroup(bot.getBotConfig().getGroupId()).sendMessage(messageChain);
                } else {
                    group.sendMessage(messageChain);
                }
            } else if (message.startsWith("听令1")) {
                messageChain.set(0, new TextMessage(message.substring("听令1".length()).trim()));
                group.sendMessage(messageChain);
            } else if (message.startsWith("听令2")) {
                messageChain.set(0, new TextMessage(message.substring("听令2".length()).trim()));
                messageChain.add(0, new AtMessage("3889001741"));
                group.sendMessage(messageChain);
            } else if (message.startsWith("听令3")) {
                messageChain.set(0, new TextMessage(message.substring("听令3".length()).trim()));
                messageChain.add(0, new AtMessage("3889029313"));
                group.sendMessage(messageChain);
            }
        }

    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 设置爱称(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (!messageChain.stream().anyMatch((msgx) -> msgx instanceof ReplyMessage) && this.checkControlQQ(bot, member)) {
            Iterator<Message> iterator = messageChain.iterator();

            while (iterator.hasNext()) {
                Message msg = (Message) iterator.next();
                if (!(msg instanceof AtMessage)) {
                    break;
                }

                iterator.remove();
            }

            String rawMessage = ((TextMessage) messageChain.get(0)).getText();
            String processedMessage = rawMessage.trim().replaceAll("\\n", "");
            if (processedMessage.startsWith("你的编号是")) {
                String aichengStr = processedMessage.substring("你的编号是".length()).trim();
                if (!StringUtils.isNumeric(aichengStr)) {
                    group.sendMessage((new MessageChain()).text("编号必须是整数哦！"));
                    return;
                }

                int Nid = Integer.parseInt(aichengStr);
                bot.getBotConfig().setBotNumber(Nid);
                group.sendMessage((new MessageChain()).text("好耶！我的编号是" + String.valueOf(Nid)));
            }

            if (processedMessage.startsWith("我命你为我的")) {
                String aichengStr = processedMessage.substring("我命你为我的".length()).trim();
                String newaicheng = bot.getBotConfig().getAiCheng() + "&" + aichengStr;
                bot.getBotConfig().setAiCheng(newaicheng);
                group.sendMessage((new MessageChain()).text("好耶！现在起我就是你的" + aichengStr));
            }
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 听令执行(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (!messageChain.stream().anyMatch((msg) -> msg instanceof ReplyMessage)) {
            String botNumber = String.valueOf(bot.getBotConfig().getBotNumber());
            String aiCheng = bot.getBotConfig().getAiCheng();
            boolean isControlQQ = this.checkControlQQ(bot, member);
            List<String> prefixes = new ArrayList();
            if (StringUtils.isNotBlank(botNumber)) {
                prefixes.add(botNumber);
            }

            if (StringUtils.isNotBlank(aiCheng)) {
                String[] aiChengList = aiCheng.split("&");

                for (String name : aiChengList) {
                    if (StringUtils.isNotBlank(name)) {
                        prefixes.add(name.trim());
                    }
                }
            }

            String commandPrefix = null;

            for (String prefix : prefixes) {
                if (message.startsWith(prefix)) {
                    commandPrefix = prefix;
                    break;
                }
            }

            if (isControlQQ && commandPrefix != null) {
                String command = message.substring(commandPrefix.length()).trim();
                messageChain.set(0, new TextMessage(command));
                this.processCommand(bot, group, messageChain, command);
            }
        }

    }

    private boolean checkControlQQ(Bot bot, Member member) {
        return StringUtils.isNotBlank(bot.getBotConfig().getControlQQ()) ? ("&" + bot.getBotConfig().getControlQQ() + "&").contains("&" + member.getUserId() + "&") : bot.getBotConfig().getMasterQQ() == member.getUserId();
    }

    private void processCommand(Bot bot, Group group, MessageChain messageChain, String command) {
        if (command.contains("循环")) {
            String[] lines = command.split("\\R");
            String commandHeader = lines[0].trim();

            int count;
            int time;
            try {
                count = Integer.parseInt(lines[lines.length - 2].trim());
                time = Integer.parseInt(lines[lines.length - 1].trim());
            } catch (NumberFormatException var11) {
                group.sendMessage((new MessageChain()).text("参数解析错误！循环次数和间隔秒数必须是整数"));
                return;
            }

            StringBuilder cleanCommand = new StringBuilder();

            for (int i = 0; i < lines.length - 2; ++i) {
                if (i > 0) {
                    cleanCommand.append("\n");
                }

                cleanCommand.append(lines[i]);
            }

            command = cleanCommand.toString();
            if (commandHeader.startsWith("听令1循环执行")) {
                messageChain.set(0, new TextMessage(command.substring(7)));
                this.forSendMessage(bot, group, messageChain, count, time, 2);
            } else if (commandHeader.startsWith("听令2循环执行")) {
                messageChain.set(0, new TextMessage(command.substring(7)));
                messageChain.add(0, new AtMessage("3889001741"));
                this.forSendMessage(bot, group, messageChain, count, time, 1);
            } else if (commandHeader.startsWith("听令3循环执行")) {
                messageChain.set(0, new TextMessage(command.substring(7)));
                messageChain.add(0, new AtMessage("3889029313"));
                this.forSendMessage(bot, group, messageChain, count, time, 1);
            }
        } else if (command.startsWith("听令1")) {
            messageChain.set(0, new TextMessage(command.substring(3)));
            group.sendMessage(messageChain);
        } else if (command.startsWith("听令2")) {
            messageChain.set(0, new TextMessage(command.substring(3)));
            messageChain.add(0, new AtMessage("3889001741"));
            group.sendMessage(messageChain);
        } else if (command.startsWith("听令3")) {
            messageChain.set(0, new TextMessage(command.substring(3)));
            messageChain.add(0, new AtMessage("3889029313"));
            group.sendMessage(messageChain);
        } else if (command.startsWith("执行命令")) {
            messageChain.set(0, new TextMessage(command.substring(4)));
            group.sendMessage(messageChain);
        } else if (command.startsWith("执行")) {
            messageChain.set(0, new TextMessage(command.substring(2)));
            messageChain.add(0, new AtMessage("3889001741"));
            group.sendMessage(messageChain);
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 弟子听令执行(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (!messageChain.stream().anyMatch((msg) -> msg instanceof ReplyMessage)) {
            String botNumber = String.valueOf(bot.getBotConfig().getBotNumber());
            String aiCheng = bot.getBotConfig().getAiCheng();
            boolean isControlQQ = this.checkControlQQ(bot, member);
            if (isControlQQ && message.startsWith("弟子") && message.contains("听令") && !message.contains("@0")) {
                String numbersPart = message.substring(2).split("听令")[0];
                String command = message.substring(message.indexOf("听令") + 2).trim();
                Set<String> validNumbers = new HashSet();
                String[] numberArray = numbersPart.split("\\.");
                boolean allNumbersValid = true;

                for (String num : numberArray) {
                    if (StringUtils.isNumeric(num)) {
                        validNumbers.add(num.trim());
                    }
                }

                if (allNumbersValid && !validNumbers.isEmpty() && validNumbers.contains(botNumber)) {
                    command = "听令" + command;
                    messageChain.set(0, new TextMessage(command));
                    this.processCommand(bot, group, messageChain, command);
                }
            }
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 弟子执行命令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean hasReplyMessage = messageChain.stream().anyMatch((msgx) -> msgx instanceof ReplyMessage);
        if (!hasReplyMessage) {
            boolean isControlQQ = StringUtils.isNotBlank(bot.getBotConfig().getControlQQ()) ? ("&" + bot.getBotConfig().getControlQQ() + "&").contains("&" + member.getUserId() + "&") : bot.getBotConfig().getMasterQQ() == member.getUserId();
            if (isControlQQ) {
                Pattern pattern = Pattern.compile("弟子听令(\\d*)执行");
                Matcher matcher = pattern.matcher(message);
                boolean found = matcher.find();
                boolean isAtXx;
                if ((message.contains("弟子听令") && found && message.contains("执行") || message.contains("弟子听令执行")) && !message.contains("@0")) {
                    int delaySeconds = 0;
                    if (found) {
                        String delayStr = matcher.group(1);
                        delaySeconds = StringUtils.isNumeric(delayStr) ? Integer.parseInt(delayStr) : 0;
                    }

                    int botNumber = bot.getBotConfig().getBotNumber();
                    int actualDelay = (botNumber - 1) * delaySeconds;
                    Iterator<Message> iterator = messageChain.iterator();

                    while (iterator.hasNext()) {
                        Message msg = (Message) iterator.next();
                        if (!(msg instanceof AtMessage)) {
                            break;
                        }

                        iterator.remove();
                    }

                    String processedMsg = ((TextMessage) messageChain.get(0)).getText().trim();
                    if (delaySeconds != 0) {
                        isAtXx = false;
                        String prefixWithDelay = "弟子听令" + delaySeconds + "执行";
                        System.out.println(delaySeconds);
                        String prefixWithDelayCommand = "弟子听令" + delaySeconds + "执行命令";
                        if (processedMsg.startsWith(prefixWithDelayCommand)) {
                            processedMsg = processedMsg.substring(prefixWithDelayCommand.length()).trim();
                            messageChain.set(0, new TextMessage(processedMsg));
                        } else if (processedMsg.startsWith(prefixWithDelay)) {
                            processedMsg = processedMsg.substring(prefixWithDelay.length()).trim();
                            messageChain.set(0, new TextMessage(processedMsg));
                            messageChain.add(0, new AtMessage("3889001741"));
                        }
                    } else if (processedMsg.startsWith("弟子听令执行命令")) {
                        isAtXx = false;
                        processedMsg = processedMsg.substring("弟子听令执行命令".length()).trim();
                        messageChain.set(0, new TextMessage(processedMsg));
                    } else {
                        if (!processedMsg.startsWith("弟子听令执行")) {
                            return;
                        }

                        isAtXx = true;
                        processedMsg = processedMsg.substring("弟子听令执行".length()).trim();
                        messageChain.set(0, new TextMessage(processedMsg));
                        messageChain.add(0, new AtMessage("3889001741"));
                    }

                    String finalProcessedMsg = processedMsg;
                    CompletableFuture.runAsync(() -> {
                        try {
                            if (actualDelay > 0) {
                                TimeUnit.SECONDS.sleep((long) actualDelay);
                            }

                            if (isAtXx) {
                                Stream<String> stream = commandWords.stream();
                                Objects.requireNonNull(finalProcessedMsg);
                                if (stream.anyMatch(finalProcessedMsg::contains)) {
                                    bot.getGroup(bot.getBotConfig().getGroupId()).sendMessage(messageChain);
                                } else {
                                    group.sendMessage(messageChain);
                                }
                            } else {
                                group.sendMessage(messageChain);
                            }
                        } catch (InterruptedException var7) {
                            Thread.currentThread().interrupt();
                        }

                    });
                } else {
                    isAtXx = false;
                }
            }
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void 仅执行自己命令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (message.contains("循环执行") && !message.startsWith("@") && !message.contains("功能设置")) {
            Iterator<Message> iterator = messageChain.iterator();

            while (iterator.hasNext()) {
                Message timeMessage = (Message) iterator.next();
                if (!(timeMessage instanceof AtMessage)) {
                    break;
                }

                iterator.remove();
            }

            message = ((TextMessage) messageChain.get(0)).getText().trim();
            Message timeMessage = (Message) messageChain.get(messageChain.size() - 1);
            int time;
            int count;
            if (timeMessage instanceof TextMessage && message.contains("循环执行")) {
                String s = ((TextMessage) timeMessage).getText().trim();
                if (message.contains("坊市查看")) {
                    bot.getBotConfig().setCommand(s);
                }

                String[] split = s.split("\n");
                if (split.length >= 2) {
                    int countTemp = 0;
                    int timeTemp = 5;

                    try {
                        countTemp = Integer.parseInt(split[split.length - 2]);
                        timeTemp = Integer.parseInt(split[split.length - 1]);
                        message = s.substring(0, s.length() - ("\n" + countTemp + "\n" + timeTemp).length()).trim();
                        ((TextMessage) timeMessage).setText(message);
                    } catch (Exception var16) {
                    }

                    count = countTemp;
                    time = timeTemp;
                } else {
                    count = 0;
                    time = 0;
                }
            } else {
                count = 0;
                time = 0;
            }

            message = ((TextMessage) messageChain.get(0)).getText().trim();
            if (bot.getBotId() == member.getUserId()) {
                if (message.startsWith("循环执行命令")) {
                    messageChain.set(0, new TextMessage(message.substring(message.indexOf("循环执行命令") + 6)));
                    this.forSendMessage(bot, group, messageChain, count, time, 2);
                } else if (message.startsWith("循环执行")) {
                    messageChain.set(0, new TextMessage(message.substring(message.indexOf("循环执行") + 4)));
                    messageChain.add(0, new AtMessage("3889001741"));
                    this.forSendMessage(bot, group, messageChain, count, time, 1);
                }
            }

            if (this.checkControlQQ(bot, member)) {
                if (message.startsWith("弟子听令循环执行命令")) {
                    messageChain.set(0, new TextMessage(message.substring(message.indexOf("弟子听令循环执行命令") + 10)));
                    this.executeSendAllMessage(group, messageChain, count, time);
                } else if (message.startsWith("弟子听令循环执行")) {
                    messageChain.set(0, new TextMessage(message.substring(message.indexOf("弟子听令循环执行") + 8)));
                    messageChain.add(0, new AtMessage("3889001741"));
                    this.executeSendAllMessage(group, messageChain, count, time);
                }
            }
        }

    }

    @Scheduled(
            cron = "0 0 5 * * ?"
    )
    public void 重置双修() {
        for (Bot bot : BotFactory.getBots().values()) {
            BotConfig botConfig = bot.getBotConfig();
            botConfig.setRemainingSxNumber(botConfig.getShuangXuNumber());
            if (bot.getBotId() != bot.getBotConfig().getMasterQQ() && bot.getGroup(682220759L) != null) {
                bot.setGroupCard(682220759L, bot.getBotId(), "A无偿双修");
            }
        }

    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 开启无偿双修(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if ((bot.getBotConfig().isEnableAutoRepair() || group.getGroupId() == 682220759L) && message.contains("双修")) {
            Iterator iterator = messageChain.iterator();

            while (iterator.hasNext()) {
                Message timeMessage = (Message) iterator.next();
                if (!(timeMessage instanceof AtMessage)) {
                    break;
                }

                iterator.remove();
            }

            if (messageChain.get(0) instanceof TextMessage) {
                message = ((TextMessage) messageChain.get(0)).getText().trim();
                if (message.startsWith("请双修")) {
                    Pattern textPattern = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
                    Matcher textMatcher = textPattern.matcher(message);
                    String textKeyword = "";
                    if (textMatcher.find()) {
                        textKeyword = textMatcher.group().substring(1);
                    }

                    Pattern numberPattern = Pattern.compile("\\d+");
                    Matcher numberMatcher = numberPattern.matcher(message);
                    int numberKeyword = 0;
                    int shuangxu = bot.getBotConfig().getRemainingSxNumber();
                    if (numberMatcher.find()) {
                        numberKeyword = Integer.parseInt(numberMatcher.group());
                        if (shuangxu == 0) {
                            shuangxu = bot.getBotConfig().getShuangXuNumber();
                        }

                        if (shuangxu <= 0) {
                            if (group.getGroupId() == 682220759L) {
                                bot.setGroupCard(group.getGroupId(), bot.getBotId(), "A无偿双修(剩余0次)");
                            }

                            bot.getBotConfig().setRemainingSxNumber(-1);
                            group.sendMessage((new MessageChain()).text("我已经被道友们榨干了，请找其他道友双修吧！"));
                            return;
                        }

                        bot.getBotConfig().setRemainingSxNumber(shuangxu - numberKeyword);
                    }

                    if (numberKeyword > shuangxu) {
                        numberKeyword = shuangxu;
                    }

                    messageChain.set(0, new TextMessage(textKeyword));
                    messageChain.add(0, new AtMessage("3889001741"));
                    this.forSendMessage(bot, group, messageChain, numberKeyword, 3, 1);
                }

                if (message.startsWith("我的双修次数")) {
                    messageChain.set(0, new TextMessage("我的双修次数"));
                    messageChain.add(0, new AtMessage("3889001741"));
                    this.forSendMessage(bot, group, messageChain, 1, 1, 1);
                }
            }
        }

    }

    private void forSendMessage2(Bot bot, Group group, MessageChain messageChain, int count, int time) {
        for (int i = 0; i < count; ++i) {
            BotConfig botConfig = bot.getBotConfig();
            if (botConfig.isStop()) {
                botConfig.setStop(false);
                return;
            }

            try {
                group.sendMessage(messageChain);
                Thread.sleep((long) time * 1000L);
            } catch (Exception var9) {
            }
        }

    }

    private void forSendMessage(final Bot bot, final Group group, final MessageChain messageChain, final int count, final int time, final int mode) {
        customPool.submit(new Runnable() {
            public void run() {
                for (int i = 0; i < count; ++i) {
                    BotConfig botConfig = bot.getBotConfig();
                    if (botConfig.isStop()) {
//                        String text = "循环执行";
//                        if (mode == 2) {
//                            text = "循环执行命令";
//                        } else {
//                            messageChain.remove(0);
//                        }
//
//                        text = text + messageChain + "\n" + (count - i) + "\n" + time;
//                        if (count - i > 1) {
//                            botConfig.setCommand(text);
//                        }

                        botConfig.setStop(false);
                        return;
                    }

                    try {
                        group.sendMessage(messageChain);
                        Thread.sleep((long) time * 1000L);
                    } catch (Exception var4) {
                    }
                }

            }
        });
    }

    private void executeSendAllMessage(final Group group, final MessageChain messageChain, final int count, final int time) {
        customPool.submit(new Runnable() {
            public void run() {
                for (Bot bot1 : BotFactory.getBots().values()) {
                    try {
                        Group bot1Group = bot1.getGroup(group.getGroupId());
                        TestService.this.forSendMessage2(bot1, bot1Group, messageChain, count, time);
                    } catch (Exception var4) {
                    }
                }

            }
        });
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 秘境(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isAtSelf = Utils.isAtSelf(bot, group, message, xxGroupId);
        if (botConfig.isEnableAutoSecret() && isAtSelf) {
            LocalDateTime now = LocalDateTime.now();
            if (message.contains("正在秘境中") && message.contains("分身乏术")) {
                long groupId = botConfig.getGroupId();
                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("秘境结算"));
            } else if (message.contains("道友现在什么都没干")) {
                botConfig.setXslTime(-1L);
                botConfig.setMjTime(-1L);
            } else if (message.contains("道友已经参加过本次秘境")) {
                botConfig.setXslTime(-1L);
                botConfig.setMjTime(-1L);
                proccessCultivation(group);
            } else if (message.contains("进行中的：") && message.contains("可结束") && message.contains("探索")) {
                String[] parts;
                if (message.contains("(原")) {
                    parts = message.split("预计|\\(原");
                } else {
                    parts = message.split("预计|分钟");
                }

                botConfig.setMjTime((long) (Double.parseDouble(parts[1]) * (double) 60.0F * (double) 1000.0F + (double) System.currentTimeMillis()));
                botConfig.setStartScheduled(false);
            } else if (message.contains("进入秘境") && message.contains("探索需要花费")) {
                String[] parts;
                if (message.contains("(原")) {
                    parts = message.split("花费时间：|\\(原");
                } else {
                    parts = message.split("花费时间：|分钟");
                }

                botConfig.setMjTime((long) (Double.parseDouble(parts[1]) * (double) 60.0F * (double) 1000.0F + (double) System.currentTimeMillis()));
                botConfig.setStartScheduled(false);
            } else if (message.contains("秘境昭告") && message.contains("勘历天时")) {
                String[] parts = message.split("勘历天时：|分钟");
                botConfig.setMjTime((long) (Double.parseDouble(parts[1]) * (double) 60.0F * (double) 1000.0F + (double) System.currentTimeMillis()));
                log.info("秘境时间：{}", parts[1]);
                botConfig.setStartScheduled(false);
            } else if (message.contains("秘境降临") && message.contains("时轮压缩")) {
                String[] parts = message.split("时轮压缩：|分钟");
                botConfig.setMjTime((long) (Double.parseDouble(parts[1]) * (double) 60.0F * (double) 1000.0F + (double) System.currentTimeMillis()));
                log.info("秘境时间：{}", parts[1]);
                botConfig.setStartScheduled(false);
            } else if (((message.contains("秘境") || message.contains("妖域")) && message.contains("道友已") && message.contains("分钟")) || ((message
                    .contains("秘境") || message.contains("妖域")) && message.contains("时轮压缩") && message.contains("分钟"))) {
                Pattern timePattern = Pattern.compile("\\s*[^:]*：\\s*(\\d+\\.?\\d*)\\s*(分钟|小时)");
                Matcher matcher = timePattern.matcher(message);
                if (matcher.find()){
                    try {
                        double timeValue = Double.parseDouble(matcher.group(1));
                        String unit = matcher.group(2);
                        long durationMs = (long) (unit.equals("小时") ? (timeValue * 60.0D * 60.0D * 1000.0D) : (timeValue * 60.0D * 1000.0D));
                        botConfig.setMjTime(System.currentTimeMillis() + durationMs);
                        botConfig.setStartScheduled(false);
                        System.out.println("成功设置秘境时间：" + timeValue + unit);
                    } catch (NumberFormatException numberFormatException) {
                    }
                }

            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 悬赏令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isAtSelf = Utils.isAtSelf(bot, group, message, xxGroupId);
        if (isAtSelf && botConfig.getRewardMode() != 1) {
            if (message.contains("在做悬赏令呢") && message.contains("分身乏术")) {
                botConfig.setStartScheduled(false);
                bot.getBotConfig().setMjTime(-1L);
                group.sendMessage((new MessageChain()).at("3889001741").text("悬赏令结算"));
            }

            if (message.contains("没有查到你的悬赏令信息")) {
                botConfig.setStartScheduled(true);
                botConfig.setXslTime(-1L);
                if (botConfig.getRewardMode() == 3 || botConfig.getRewardMode() == 4 || botConfig.getRewardMode() == 5) {
                    group.sendMessage((new MessageChain()).at("3889001741").text("悬赏令刷新"));
                }
            }

            if (message.contains("悬赏令刷新次数已用尽")) {
                bot.getBotConfig().setXslTime(-1L);
                Thread.sleep(2000L);
                proccessCultivation(group);
                this.groupManager.setXslTaskFinished(bot);
            }

            if (message.contains("进行中的悬赏令") && message.contains("可结束") || message.contains("悬赏令进行中") && message.contains("预计剩余时间") || message.contains("悬赏令接取成功") && message.contains("预计时间")) {
                Pattern pattern = Pattern.compile("预计[^\\d]*(\\d+\\.?\\d*)(?:\\([^)]*\\))?\\s*分钟");
                Matcher matcher = pattern.matcher(message);
                if (matcher.find()) {
                    try {
                        double minutes = Double.parseDouble(matcher.group(1));
                        long timeMillis = (long) (Math.ceil(minutes) * (double) 60.0F * (double) 1000.0F);
                        botConfig.setXslTime(System.currentTimeMillis() + timeMillis);
                    } catch (NumberFormatException var15) {
                        var15.printStackTrace();
                    }
                }
            }

            if (message.contains("进行中的悬赏令") && message.contains("已结束") || message.contains("悬赏令完成通知") && message.contains("已完成")) {
                botConfig.setXslTime(-1L);
                botConfig.setMjTime(-1L);
                botConfig.setStartScheduled(true);
                group.sendMessage((new MessageChain()).at("3889001741").text("悬赏令结算"));
            }

            if (message.contains("接取任务") && message.contains("成功")) {
                group.sendMessage((new MessageChain()).at("3889001741").text("悬赏令结算"));
            }

            if (message.contains("获得一次悬赏令刷新次数") && "一键使用追捕令".equals(botConfig.getCommand())) {
                group.sendMessage((new MessageChain()).at("3889001741").text(" 悬赏令刷新"));
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 悬赏令接取(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isAtSelf = Utils.isAtSelf(bot, group, message, xxGroupId);
        if ((botConfig.getRewardMode() == 3 || botConfig.getRewardMode() == 4 || botConfig.getRewardMode() == 5) && isAtSelf && (message.contains("道友的个人悬赏令") || message.contains("天机悬赏令"))) {
            List<String> prioritySkills = Arrays.asList("五指拳心剑", "袖里乾坤", "真龙九变", "灭剑血胧", "万剑归宗", "千慄鬼噬", "华光猎影");
            Set<String> specialSkills = new HashSet(prioritySkills);
            boolean isSpecialSkill = false;
            int highestPriorityIndex = -1;
            int currentReceIndex = 0;
            String highestPrioritySkill = null;

            for (TextMessage textMessage : messageChain.getMessageByType(TextMessage.class)) {
                String currentMessage = textMessage.getText();
                if (currentMessage.contains("道友的个人悬赏令") || currentMessage.contains("天机悬赏令")) {
                    Pattern pattern = Pattern.compile("(?:可能额外获得|额外机缘)[：:](.*?)(?=[!」])");
                    Matcher matcher = pattern.matcher(currentMessage);
                    int count = 0;
                    int index = 0;
                    int maxPrice = 0;
                    int receIndex = 0;

                    while (matcher.find()) {
                        ++count;
                        String name = matcher.group(1).replaceAll("\\s", "");
                        String[] parts = name.split("[:「]");
                        name = parts.length > 1 ? parts[1].replaceAll("[」]", "").trim() : parts[0].trim();
                        if (StringUtils.isNotBlank(name)) {
                            ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(name.trim());
                            if (specialSkills.contains(name)) {
                                int currentPriority = prioritySkills.indexOf(name);
                                if (highestPriorityIndex == -1 || currentPriority < highestPriorityIndex) {
                                    highestPriorityIndex = currentPriority;
                                    currentReceIndex = count;
                                    highestPrioritySkill = name;
                                }

                                isSpecialSkill = true;
                            }

                            if (first != null) {
                                ++index;
                                if (first.getPrice() > maxPrice) {
                                    maxPrice = first.getPrice();
                                    receIndex = count;
                                }
                            }
                        }
                    }

                    if (isSpecialSkill && highestPrioritySkill != null) {
                        bot.sendPrivateMessage(botConfig.getMasterQQ(), (new MessageChain()).text("恭喜道友" + bot.getBotName() + "的悬赏令在【" + group.getGroupName() + "】群里获得：" + highestPrioritySkill));
                        long groupId = botConfig.getGroupId();
                        bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("悬赏令接取" + currentReceIndex));
                        return;
                    }

                    if (index == 3) {
                        long groupId = botConfig.getGroupId();
                        if (botConfig.getRewardMode() == 4 && maxPrice < botConfig.getXslPriceLimit()) {
                            List<Double> durations = extractDurations(currentMessage);
                            receIndex = findShortestDurationIndex(durations);
                        } else if (botConfig.getRewardMode() == 5 && maxPrice < botConfig.getXslPriceLimit()) {
                            List<Long> rewards = extractRewards(currentMessage);
                            List successRates = extractSuccessRates(message);

                            for (int i = 0; i < rewards.size(); ++i) {
                                if (i < successRates.size() && (Integer) successRates.get(i) == 100) {
                                    rewards.set(i, (Long) rewards.get(i) * 3L);
                                }
                            }

                            receIndex = findLongRewardsIndex(rewards);
                        }

                        bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("悬赏令接取" + receIndex));
                    }
                }
            }
        }

    }

    public static List<Integer> extractSuccessRates(String input) {
        List<Integer> successRates = new ArrayList();
        Pattern pattern = Pattern.compile("(?:完成几率|✅?成功率[：:]\\s*)(\\d+)(?:%?)");
        Matcher matcher = pattern.matcher(input);

        while (matcher.find()) {
            successRates.add(Integer.parseInt(matcher.group(1)));
        }

        return successRates;
    }

    public static List<Long> extractRewards(String input) {
        List<Long> rewards = new ArrayList();
        Pattern pattern = Pattern.compile("基础(?:报酬|奖励)(\\d+)修为");
        Matcher matcher = pattern.matcher(input);

        while (matcher.find()) {
            rewards.add(Long.parseLong(matcher.group(1)));
        }

        return rewards;
    }

    public static int findLongRewardsIndex(List<Long> rewardss) {
        if (rewardss.isEmpty()) {
            return 0;
        } else {
            int longIndex = 0;
            long longDuration = (Long) rewardss.get(0);

            for (int i = 1; i < rewardss.size(); ++i) {
                if ((Long) rewardss.get(i) > longDuration) {
                    longDuration = (Long) rewardss.get(i);
                    longIndex = i;
                }
            }

            return longIndex + 1;
        }
    }

    public static List<Double> extractDurations(String input) {
        List<Double> durations = new ArrayList();
        Pattern pattern = Pattern.compile("预计[^\\d]*(\\d+\\.?\\d*)\\s*分钟|预计耗时：(\\d+\\.?\\d*)\\s*分钟");
        Matcher matcher = pattern.matcher(input);

        while (matcher.find()) {
            String numberStr = matcher.group(1);
            double number = Double.parseDouble(numberStr);
            durations.add(number);
        }

        return durations;
    }

    public static int findShortestDurationIndex(List<Double> durations) {
        if (durations.isEmpty()) {
            return 0;
        } else {
            int shortestIndex = 0;
            double shortestDuration = (Double) durations.get(0);

            for (int i = 1; i < durations.size(); ++i) {
                if ((Double) durations.get(i) < shortestDuration) {
                    shortestDuration = (Double) durations.get(i);
                    shortestIndex = i;
                }
            }

            return shortestIndex + 1;
        }
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 结算(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        long groupId = botConfig.getGroupId();
        boolean isAtSelf = Utils.isAtSelf(bot, group, message, xxGroupId);
        if (botConfig.getRewardMode() != 1 && isAtSelf) {
            if (message.contains("悬赏令结算") && message.contains("增加修为")) {
                bot.getBotConfig().setXslTime(-1L);
                if ("一键使用追捕令".equals(botConfig.getCommand())) {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text(" 道具使用追捕令"));
                } else if (botConfig.getRewardMode() != 3 && botConfig.getRewardMode() != 4 && botConfig.getRewardMode() != 5) {
                    proccessCultivation(group);
                } else {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text(" 悬赏令刷新"));
                }
            }

            if (message.contains("道友没有追捕令")) {
                botConfig.setCommand("");
                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text(" 悬赏令刷新"));
            }

            if (message.contains("道友没有次元之钥")) {
                botConfig.setCommand("");
                proccessCultivation(group);
            }

            Stream<String> stream = KEYWORDS.stream();
            Objects.requireNonNull(message);
            if (stream.anyMatch(message::contains) && !message.contains("时间：")) {
                bot.getBotConfig().setMjTime(-1L);
                if ("一键使用次元之钥".equals(botConfig.getCommand())) {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text(" 道具使用次元之钥"));
                } else {
                    this.groupManager.setMjTaskFinished(bot);
                    proccessCultivation(group);
                }
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 转发小小消息到控制群(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        BotConfig botConfig = bot.getBotConfig();
        if (Utils.isAtSelf(bot, group, message, xxGroupId) && !message.contains("本次修炼增加") && !message.contains("挖矿") && !message.contains("第三方") && !message.contains("点击") && !message.contains("开始\ud83d\ude4f修炼") && !message.contains("稻草人") && botConfig.getForwardMode() == 1) {
            Stream<String> stream = forwardWords.stream();
            Objects.requireNonNull(message);
            if (stream.anyMatch(message::contains)) {
//                Utils.forwardMessage(bot, this.xxGroupId, message);
                Utils.forwardMessage(bot, this.xxGroupId, messageChain);
            }

            stream = KEYWORDS.stream();
            Objects.requireNonNull(message);
            if (stream.anyMatch(message::contains) && !message.contains("时间：")) {
//                Utils.forwardMessage(bot, this.xxGroupId, message);
                Utils.forwardMessage(bot, this.xxGroupId, messageChain);
            }

            if (message.contains("道友成功领取到丹药") || message.contains("道友已经领取过了")) {
                this.groupManager.setDanYaoFinished(bot);
            }

            if (message.contains("奖励") && message.contains("灵石")) {
//                Utils.forwardMessage(bot, this.xxGroupId, message);
                Utils.forwardMessage(bot, this.xxGroupId, messageChain);
            }
        }

    }

    @Scheduled(
            fixedDelay = 60000L,
            initialDelay = 30000L
    )
    public void 定时修炼() {
        BotFactory.getBots().values().forEach((bot) -> {
            int cultivationMode = bot.getBotConfig().getCultivationMode();
            if (cultivationMode == 1 && bot.getBotConfig().isStartScheduled() && bot.getBotConfig().getLastSendTime() + 65000L < System.currentTimeMillis()) {
                bot.getGroup(bot.getBotConfig().getGroupId()).sendMessage((new MessageChain()).at("3889001741").text("修炼"));
            }

        });
    }

    @Scheduled(
            fixedDelay = 3000L,
            initialDelay = 30000L
    )
    public void 定时查询坊市() {
        BotFactory.getBots().values().forEach((bot) -> {
            BotConfig botConfig = bot.getBotConfig();
            long groupId = botConfig.getGroupId();
            if (botConfig.isStartScheduledMarket()) {
                if (botConfig.getTaskStatusEquip() == 5 && botConfig.getTaskStatusSkills() == 10 && botConfig.getTaskStatusHerbs() == 8) {
                    botConfig.setTaskStatusEquip(1);
                    botConfig.setTaskStatusSkills(1);
                    botConfig.setTaskStatusHerbs(1);
                }

                if (botConfig.getTaskStatusEquip() < 5) {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("查看坊市装备" + botConfig.getTaskStatusEquip()));
                    botConfig.setTaskStatusEquip(botConfig.getTaskStatusEquip() + 1);
                } else if (botConfig.getTaskStatusSkills() < 10) {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("查看坊市技能" + botConfig.getTaskStatusSkills()));
                    botConfig.setTaskStatusSkills(botConfig.getTaskStatusSkills() + 1);
                } else if (botConfig.getTaskStatusHerbs() < 8) {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("查看坊市药材" + botConfig.getTaskStatusHerbs()));
                    botConfig.setTaskStatusHerbs(botConfig.getTaskStatusHerbs() + 1);
                }
            }

            if (botConfig.isStartScheduledEquip()) {
                if (botConfig.getTaskStatusEquip() == 5) {
                    botConfig.setTaskStatusEquip(1);
                }

                if (botConfig.getTaskStatusEquip() < 5) {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("查看坊市装备" + botConfig.getTaskStatusEquip()));
                    botConfig.setTaskStatusEquip(botConfig.getTaskStatusEquip() + 1);
                }
            }

            if (botConfig.isStartScheduledSkills()) {
                if (botConfig.getTaskStatusSkills() == 10) {
                    botConfig.setTaskStatusSkills(1);
                }

                if (botConfig.getTaskStatusSkills() < 10) {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("查看坊市技能" + botConfig.getTaskStatusSkills()));
                    botConfig.setTaskStatusSkills(botConfig.getTaskStatusSkills() + 1);
                }
            }

            if (botConfig.isStartScheduledHerbs()) {
                if (botConfig.getTaskStatusHerbs() == 8) {
                    botConfig.setTaskStatusHerbs(1);
                }

                if (botConfig.getTaskStatusHerbs() < 8) {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("查看坊市药材" + botConfig.getTaskStatusHerbs()));
                    botConfig.setTaskStatusHerbs(botConfig.getTaskStatusHerbs() + 1);
                }
            }

        });
    }

    @Scheduled(
            fixedDelay = 60000L,
            initialDelay = 3000L
    )
    public void 结算() {
        BotFactory.getBots().values().forEach((bot) -> {
            BotConfig botConfig = bot.getBotConfig();
            if (botConfig.isEnableAutoSecret() && botConfig.getMjTime() > 0L && botConfig.getMjTime() < System.currentTimeMillis()) {
                if (botConfig.isStop() && botConfig.getAutoVerifyModel() == 0) {
                    botConfig.setStop(false);
                    botConfig.setMjTime(-1L);
                    Bot remindBot = this.getRemindAtQQ(bot);
                    if (remindBot == null) {
                        return;
                    }

                    bot.getGroup(this.getRemindGroupId(bot)).sendMessage((new MessageChain()).at(remindBot.getBotConfig().getMasterQQ() + "").text("秘境结算异常，请手动结算！"));
                    return;
                }

                long groupId = botConfig.getGroupId();
                try {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("秘境结算"));
                    Thread.sleep(3000L);
                } catch (InterruptedException var6) {
                }
            }

        });
        BotFactory.getBots().values().forEach((bot) -> {
            BotConfig botConfig = bot.getBotConfig();
            if (botConfig.getRewardMode() != 1 && botConfig.getXslTime() > 0L && botConfig.getXslTime() < System.currentTimeMillis()) {
                if (botConfig.isStop() && botConfig.getAutoVerifyModel() == 0) {
                    botConfig.setStop(false);
                    botConfig.setXslTime(-1L);
                    Bot remindBot = this.getRemindAtQQ(bot);
                    if (remindBot == null) {
                        return;
                    }
                    bot.getGroup(this.getRemindGroupId(bot)).sendMessage((new MessageChain()).at(remindBot.getBotConfig().getMasterQQ() + "").text("悬赏令结算异常，请手动结算！"));
                    return;
                }

                long groupId = botConfig.getGroupId();
                try {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("悬赏令结算"));
                    Thread.sleep(3000L);
                } catch (InterruptedException var6) {
                }
            }

        });
    }
}
