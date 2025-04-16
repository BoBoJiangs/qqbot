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
import com.zhuangxv.bot.message.Message;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.AtMessage;
import com.zhuangxv.bot.message.support.ReplyMessage;
import com.zhuangxv.bot.message.support.TextMessage;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.ProductLowPrice;
import top.sshh.qqbot.data.ProductPrice;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TestService {
    private static final Logger log = LoggerFactory.getLogger(TestService.class);
    @Autowired
    private ProductPriceResponse productPriceResponse;
    private static final ForkJoinPool customPool = new ForkJoinPool(20);
    public static final Map<Long, Map<String, ProductPrice>> AUTO_BUY_PRODUCT = new ConcurrentHashMap();
    private boolean isStartAutoTalent = false;

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
    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        message = message.trim();
        if (!message.contains("可用命令")) {
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

            if ("开始自动刷天赋".equals(message)) {
                isStartAutoTalent = true;
                group.sendMessage((new MessageChain()).at("3889001741").text("道具使用涅槃造化丹"));
            }
            if ("停止自动刷天赋".equals(message)) {
                isStartAutoTalent = false;
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

            if ("开始捡漏".equals(message)) {
                botConfig.setEnableAutoBuyLowPrice(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("开始捡漏成功"));
            }

            if ("停止捡漏".equals(message)) {
                botConfig.setEnableAutoBuyLowPrice(false);
                group.sendMessage((new MessageChain()).reply(messageId).text("停止捡漏成功"));
            }

            if ("确认一键丹药炼金".equals(message)) {
                botConfig.setCommand("确认一键丹药炼金");
                group.sendMessage((new MessageChain()).at("3889001741").text("丹药背包"));
            }
            if ("确认一键装备炼金".equals(message)) {
                botConfig.setCommand("确认一键装备炼金");
                group.sendMessage((new MessageChain()).at("3889001741").text("我的背包"));
            }

            if ("确认一键药材上架".equals(message)) {
                botConfig.setCommand("确认一键药材上架");
                group.sendMessage((new MessageChain()).at("3889001741").text("药材背包"));
            }

            if ("开始一键刷灵根".equals(message)) {
                botConfig.setStartAutoLingG(true);
                group.sendMessage((new MessageChain()).at("3889001741").text("重入仙途"));
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

            String typeString;
            if (message.startsWith("设置主号")) {
                typeString = message.substring(message.indexOf("设置主号") + 4).trim();
                botConfig.setControlQQ(typeString);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            String groupString;
            if (message.startsWith("设置提醒群号")) {
                groupString = message.substring(message.indexOf("设置提醒群号") + 6).trim();
                botConfig.setGroupQQ(groupString);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            if (message.startsWith("添加提醒群号")) {
                groupString = message.substring(message.indexOf("添加提醒群号") + 6).trim();
                botConfig.setGroupQQ(botConfig.getGroupQQ() + "&" + groupString);
                group.sendMessage((new MessageChain()).reply(messageId).text("添加成功"));
            }

            if ("开启群管提醒".equals(message)) {
                botConfig.setEnableGroupManager(true);
                group.sendMessage((new MessageChain()).reply(messageId).text("设置成功"));
            }

            if ("关闭群管提醒".equals(message)) {
                botConfig.setEnableGroupManager(false);
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
            } else {
                int type;
                if (message.startsWith("修炼模式")) {
                    typeString = message.substring(message.indexOf("修炼模式") + 4).trim();
                    if (StringUtils.isNotBlank(typeString)) {
                        type = Integer.parseInt(typeString);
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
                    typeString = message.substring(message.indexOf("悬赏令模式") + 5).trim();
                    if (StringUtils.isNotBlank(typeString)) {
                        type = Integer.parseInt(typeString);
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
                    typeString = message.substring(message.indexOf("设置宗门任务") + 6).trim();
                    if (StringUtils.isNotBlank(typeString)) {
                        type = Integer.parseInt(typeString);
                        botConfig.setSectMode(type);
                        if (type == 1) {
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
                } else {
                    Map productMap;
                    if (!message.startsWith("查询自动购买")) {
                        if (message.startsWith("取消自动购买")) {
                            productMap = (Map)AUTO_BUY_PRODUCT.getOrDefault(bot.getBotId(), new ConcurrentHashMap());
                            AUTO_BUY_PRODUCT.put(bot.getBotId(), productMap);
                            message = message.substring(message.indexOf("取消自动购买") + 6).trim();
                            productMap.remove(message);
                            group.sendMessage((new MessageChain()).reply(messageId).text(message + "取消成功"));
                        } else if (message.startsWith("批量取消自动购买")) {
                            productMap = (Map)AUTO_BUY_PRODUCT.getOrDefault(bot.getBotId(), new ConcurrentHashMap());
                            AUTO_BUY_PRODUCT.put(bot.getBotId(), productMap);
                            productMap.clear();
                            group.sendMessage((new MessageChain()).reply(messageId).text(message + "取消成功"));
                        } else if (message.startsWith("自动购买") && message.contains(" ")) {
                            try {
                                String[] lines = message.split("\n");
                                String[] var30 = lines;
                                int var32 = lines.length;

                                for(int var14 = 0; var14 < var32; ++var14) {
                                    String line = var30[var14];
                                    String[] parts = line.split(" ");
                                    if (parts.length >= 2) {
                                        ProductPrice productPrice = new ProductPrice();
                                        productPrice.setName(parts[0].substring(4).trim());
                                        productPrice.setPrice(Integer.parseInt(parts[1].trim()));
                                        productPrice.setTime(LocalDateTime.now());
                                        productMap = (Map)AUTO_BUY_PRODUCT.getOrDefault(bot.getBotId(), new ConcurrentHashMap());
                                        productMap.put(productPrice.getName(), productPrice);
                                        AUTO_BUY_PRODUCT.put(bot.getBotId(), productMap);
                                    }
                                }

                                group.sendMessage((new MessageChain()).reply(messageId).text("自动购买批量添加成功"));
                            } catch (Exception var24) {
                            }
                        }
                    } else {
                        productMap = (Map)AUTO_BUY_PRODUCT.getOrDefault(bot.getBotId(), new ConcurrentHashMap());
                        AUTO_BUY_PRODUCT.put(bot.getBotId(), productMap);
                        StringBuilder s = new StringBuilder();
                        Iterator var10 = productMap.values().iterator();

                        while(var10.hasNext()) {
                            ProductPrice value = (ProductPrice)var10.next();
                            s.append("名称：").append(value.getName()).append(" 价格:").append(value.getPrice()).append("万").append("\n");
                        }

                        if (s.length() > 0) {
                            group.sendMessage((new MessageChain()).reply(messageId).text(s.toString()));
                        }
                    }
                }
            }

            if (message.endsWith("一键上架") || message.endsWith("一键炼金")) {
                List<ReplyMessage> replyMessageList = messageChain.getMessageByType(ReplyMessage.class);
                if (replyMessageList != null && !replyMessageList.isEmpty()) {
                    ReplyMessage replyMessage = (ReplyMessage)replyMessageList.get(0);
                    MessageChain replyMessageChain = replyMessage.getChain();
                    if (replyMessageChain != null) {
                        List<TextMessage> textMessageList = replyMessageChain.getMessageByType(TextMessage.class);
                        if (textMessageList != null && !textMessageList.isEmpty()) {
                            TextMessage textMessage = (TextMessage)textMessageList.get(textMessageList.size() - 1);
                            String herbsInfo = textMessage.getText();
                            String[] lines = herbsInfo.split("\n");

                            for(int i = 0; i < lines.length - 1; ++i) {
                                String line = lines[i];
                                if (line.contains("道具")) {
                                    break;
                                }

                                if (line.startsWith("名字：") || line.startsWith("上品") || line.startsWith("下品") || line.endsWith("功法") || line.endsWith("神通")) {
                                    String name = "";
                                    if ((line.contains("极品神通")||line.contains("辅修")) && message.endsWith("一键炼金")) {
                                        continue;
                                    }
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
                                        if (message.endsWith("一键炼金") && b) {
                                            if (botConfig.isStop()) {
                                                botConfig.setStop(false);
                                                return;
                                            }

                                            group.sendMessage((new MessageChain()).at("3889001741").text("炼金 " + name + " " + quantity));
                                            Thread.sleep(4000L);
                                        } else {
                                            ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(name.trim());
                                            if (first != null) {
                                                if ((double)first.getPrice() < (double)ProductLowPrice.getLowPrice(name) * 1.1) {
                                                    group.sendMessage((new MessageChain()).text("物品：" + first.getName() + "市场价：" + first.getPrice() + "万，炼金：" + ProductLowPrice.getLowPrice(name) + "万，不上架。"));
                                                } else if (message.endsWith("一键上架")) {
                                                    for(int j = 0; j < quantity; ++j) {
                                                        if (botConfig.isStop()) {
                                                            botConfig.setStop(false);
                                                            return;
                                                        }

                                                        if (first.getPrice() > 1000 && (double)(first.getPrice() - 10) * 0.85 < 900.0) {
                                                            group.sendMessage((new MessageChain()).at("3889001741").text("确认坊市上架 " + first.getName() + " " + 10000000));
                                                        } else {
                                                            group.sendMessage((new MessageChain()).at("3889001741").text("确认坊市上架 " + first.getName() + " " + (first.getPrice() - 10) * 10000));
                                                        }

                                                        Thread.sleep(4000L);
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
            sb.append("－－－－－快捷命令－－－－－\n");
            sb.append("确认一键丹药炼金\n");
            sb.append("确认一键装备炼金\n");
            sb.append("确认一键药材上架\n");
            sb.append("－－－－－掌门命令－－－－－\n");
            sb.append("弟子听令执行××\n");
            sb.append("弟子听令执行命令××\n");
            sb.append("弟子听令循环执行××\n");
            sb.append("弟子听令循环执行命令××\n");
            sb.append("－－－－－其它设置－－－－－\n");
            sb.append("设置主号 QQ号&QQ号\n");
            sb.append("设置提醒群号 QQ群号&QQ群号\n");
            return sb.toString();
        } else {
            if (message.equals("当前设置")) {
                sb.append("－－－－－当前设置－－－－－\n");
                sb.append(padRight("自动秘境", 11) + ": " + (botConfig.isEnableAutoSecret() ? "启用" : "关闭") + "\n");
                sb.append(padRight("无偿双修", 11) + ": " + (botConfig.isEnableAutoRepair() ? "启用" : "关闭") + "\n");
                sb.append(padRight("捡漏模式", 11) + ": " + (botConfig.isEnableAutoBuyLowPrice() ? "启用" : "关闭") + "\n");
                sb.append(padRight("价格查询", 11) + ": " + (botConfig.isEnableCheckPrice() ? "启用" : "关闭") + "\n");
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

                sb.append(padRight("修炼模式", 11) + ": " + cultivation + "\n");
                sb.append(padRight("自动修炼", 11) + ": " + (botConfig.isStartScheduled() ? "启用" : "关闭") + "\n");
                int rewardMode = botConfig.getRewardMode();
                if (rewardMode == 1) {
                    sb.append(padRight("自动悬赏令", 9) + ": 关闭\n");
                    sb.append(padRight("悬赏令模式", 9) + ": 手动\n");
                } else if (rewardMode == 2) {
                    sb.append(padRight("自动悬赏令", 9) + ": 关闭\n");
                    sb.append(padRight("悬赏令模式", 9) + ": 半自动\n");
                } else if (rewardMode == 3) {
                    sb.append(padRight("自动悬赏令", 9) + ": 自动\n");
                    sb.append(padRight("悬赏令模式", 9) + ": 优先价值\n");
                } else if (rewardMode == 4) {
                    sb.append(padRight("自动悬赏令", 9) + ": 自动\n");
                    sb.append(padRight("悬赏令模式", 9) + ": 优先时长最短\n");
                } else if (rewardMode == 5) {
                    sb.append(padRight("自动悬赏令", 9) + ": 自动\n");
                    sb.append(padRight("悬赏令模式", 9) + ": 优先修为\n");
                }

                sb.append(padRight("自动宗门任务", 0) + ": " + (botConfig.isEnableSectMission() ? "启用" : "关闭") + "\n");
                sb.append(padRight("宗门任务模式", 0) + ": " + (botConfig.getSectMode() == 1 ? "邪修查抄" : "所有") + "\n");
                sb.append(padRight("悬赏令价格查询", 0) + ": " + (botConfig.isEnableXslPriceQuery() ? "启用" : "关闭") + "\n");
                sb.append(padRight("猜成语查询", 9) + ": " + (botConfig.isEnableGuessTheIdiom() ? "启用" : "关闭") + "\n");
                if (botConfig.getLastExecuteTime() == 0L) {
                    sb.append(padRight("灵田收取时间", 9) + ": 无\n");
                } else {
                    sb.append(padRight("灵田收取时间", 9) + ": " + FamilyTask.sdf.format(new Date(botConfig.getLastExecuteTime() + 172800000L)) + "\n");
                }

                sb.append("－－－－－其它设置－－－－－\n");
                if (StringUtils.isNotBlank(botConfig.getControlQQ())) {
                    sb.append(padRight("主号", 3) + ": " + botConfig.getControlQQ() + "\n");
                } else if (botConfig.getMasterQQ() != 0L) {
                    sb.append(padRight("主号", 3) + ": " + botConfig.getMasterQQ() + "\n");
                }

                if (bot.getBotId() == 3860863656L) {
                    sb.append(padRight("提醒群号", 3) + ": " + botConfig.getGroupQQ() + "\n");
                }
            }

            return sb.toString();
        }
    }

    private static String padRight(String str, int length) {
        return str.length() >= length ? str : String.format("%-" + length + "s", str);
    }

    @GroupMessageHandler
    public void autoSend修炼(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        if (group.getGroupId() == botConfig.getGroupId() && message.contains("" + bot.getBotId())) {
            if ((message.contains("本次修炼增加") || message.contains("本次挖矿获取")) && botConfig.getCultivationMode() == 1 && botConfig.isStartScheduled()) {
                botConfig.setXslTime(-1L);
                botConfig.setMjTime(-1L);
                botConfig.setLastSendTime(System.currentTimeMillis());
                group.sendMessage((new MessageChain()).at("3889001741").text("修炼"));
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
        boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
        BotConfig botConfig;
        if (isAtSelf && message.contains("的丹药背包")) {
            botConfig = bot.getBotConfig();
            if (StringUtils.isNotBlank(botConfig.getCommand()) && botConfig.getCommand().equals("确认一键丹药炼金")) {
                botConfig.setCommand("");
                group.sendMessage((new MessageChain()).reply(messageId).text("一键炼金"));
            }
        }

        if (isAtSelf && message.contains("的背包")) {
            botConfig = bot.getBotConfig();
            if (StringUtils.isNotBlank(botConfig.getCommand()) && botConfig.getCommand().equals("确认一键装备炼金")) {
                botConfig.setCommand("");
                group.sendMessage((new MessageChain()).reply(messageId).text("一键炼金"));
            }
        }


        if (isAtSelf && message.contains("的药材背包")) {
            botConfig = bot.getBotConfig();
            if (StringUtils.isNotBlank(botConfig.getCommand()) && botConfig.getCommand().equals("确认一键药材上架")) {
                group.sendMessage((new MessageChain()).reply(messageId).text("一键上架"));
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 自动刷天赋(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
        if (isStartAutoTalent && isAtSelf && message.contains("保留24h，超时则无法选择")) {
            List<TextMessage> messageList = messageChain.getMessageByType(TextMessage.class);
            String text = messageList.get(messageList.size()-1).getText();
//            log.info(text);
            if (checkStats(text)){
                isStartAutoTalent = false;
//                group.sendMessage((new MessageChain()).at("3889001741").text("确认天赋选择右边"));
            }else{
                group.sendMessage((new MessageChain()).at("3889001741").text("确认天赋保留左边"));
            }
        }

        if (isStartAutoTalent && isAtSelf && message.contains("成功保留")) {
            group.sendMessage((new MessageChain()).at("3889001741").text("道具使用涅槃造化丹"));
        }



    }

    public boolean checkStats(String input) {
        Pattern pattern = Pattern.compile("(贪狼|巨门|禄存|文曲|廉贞|武曲|破军)\\（[^）]+\\）：(\\d+)(%?) -> (\\d+)(%?)");
        boolean luCunValid = false;
        boolean wuQuValid = false;
        boolean poJunValid = false;

        String[] lines = input.split("\n");

        for(String line : lines) {
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
                        break;
                }
            }
        }

        return luCunValid && wuQuValid && poJunValid;
    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 停止坊市自动查询(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (message.contains("解除限制") && message.contains("" + bot.getBotId())) {
            bot.getBotConfig().setStop(true);
            bot.getBotConfig().setLastRefreshTime(System.currentTimeMillis() + 300000L);
            bot.getBotConfig().setFamilyTaskStatus(0);
            bot.getBotConfig().setCultivationMode(0);
            bot.getBotConfig().setStartScheduledMarket(false);
            bot.getBotConfig().setStartScheduledEquip(false);
            bot.getBotConfig().setStartScheduledSkills(false);
            bot.getBotConfig().setStartScheduledHerbs(false);
            bot.getBotConfig().setLastExecuteTime(9223372036854175807L);
        }

        if (message.contains("https") && message.contains("qqbot") && (!message.contains("修仙信息") || !message.contains("统计信息") || !message.contains("道号")) && message.contains("" + bot.getBotId())) {
            if (message.contains("修仙信息") && message.contains("道号")) {
                System.out.println(message);
            }

            BotConfig botConfig = bot.getBotConfig();
            botConfig.setStop(true);
            botConfig.setLastRefreshTime(System.currentTimeMillis() + 300000L);
            botConfig.setStartScheduledMarket(false);
            botConfig.setStartScheduledEquip(false);
            botConfig.setStartScheduledSkills(false);
            botConfig.setStartScheduledHerbs(false);
            long groupId = botConfig.getGroupId();
            if (botConfig.getTaskId() != 0L) {
                groupId = botConfig.getTaskId();
            }

            if (botConfig.getMasterQQ() != 0L) {
                if (botConfig.getMasterQQ() != bot.getBotId()) {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at(String.valueOf(botConfig.getMasterQQ())).text(bot.getBotName() + "的" + group.getGroupName() + "出现验证码！！！！"));
                } else {
                    Iterator var1 = BotFactory.getBots().values().iterator();

                    while(var1.hasNext()) {
                        try {
                            Bot bot1 = (Bot)var1.next();
                            if ((bot1.getBotConfig().getGroupId() == bot.getBotConfig().getGroupId() || bot1.getBotConfig().getTaskId() == bot.getBotConfig().getTaskId()) && bot1.getBotId() != bot.getBotId()) {
                                groupId = botConfig.getGroupId();
                                if (botConfig.getTaskId() != 0L) {
                                    groupId = botConfig.getTaskId();
                                }

                                bot1.getGroup(groupId).sendMessage((new MessageChain()).at(String.valueOf(botConfig.getMasterQQ())).text(bot.getBotName() + "的" + group.getGroupName() + "出现验证码！！！！"));
                                break;
                            }
                        } catch (Exception var13) {
                        }
                    }
                }
            }
        }

        if ((message.contains("双修次数已经到达上限") || message.contains("请输入你道侣的道号,与其一起双修！") || message.contains("不屑一顾，扬长而去！")) && message.contains("" + bot.getBotId())) {
            bot.getBotConfig().setStop(true);

            try {
                Thread.sleep(5000L);
                bot.getBotConfig().setStop(false);
            } catch (InterruptedException var12) {
            }
        }

        if ((message.contains("奖励") && message.contains("灵石") || message.contains("不需要验证") || message.contains("验证码已过期")) && message.contains("" + bot.getBotId())) {
            bot.getBotConfig().setStop(false);
            if (message.contains("奖励") && message.contains("灵石") && StringUtils.isNotBlank(bot.getBotConfig().getCommand())) {
                group.sendMessage((new MessageChain()).text(bot.getBotConfig().getCommand()));
            } else if (StringUtils.isNotBlank(bot.getBotConfig().getCommand())) {
                bot.getBotConfig().setCommand("");
            }
        }

    }

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 执行命令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isControlQQ = false;
        if (StringUtils.isNotBlank(bot.getBotConfig().getControlQQ())) {
            isControlQQ = ("&" + bot.getBotConfig().getControlQQ() + "&").contains("&" + member.getUserId() + "&");
        } else {
            isControlQQ = bot.getBotConfig().getMasterQQ() == member.getUserId();
        }

        if (isControlQQ && message.contains("执行")) {
            Iterator iterator = messageChain.iterator();

            Message timeMessage;
            while(iterator.hasNext()) {
                timeMessage = (Message)iterator.next();
                if (!(timeMessage instanceof AtMessage)) {
                    break;
                }

                iterator.remove();
            }

            message = ((TextMessage)messageChain.get(0)).getText().trim();
            timeMessage = (Message)messageChain.get(messageChain.size() - 1);
            int time;
            int count;
            if (timeMessage instanceof TextMessage && message.contains("循环")) {
                String textKeyword = ((TextMessage)timeMessage).getText().trim();
                String[] split = textKeyword.split("\n");
                if (split.length >= 2) {
                    int countTemp = 0;
                    int numberKeyword = 5;

                    try {
                        countTemp = Integer.parseInt(split[split.length - 2]);
                        numberKeyword = Integer.parseInt(split[split.length - 1]);
                        message = textKeyword.substring(0, textKeyword.length() - ("\n" + countTemp + "\n" + numberKeyword).length()).trim();
                        ((TextMessage)timeMessage).setText(message);
                    } catch (Exception var17) {
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

            message = ((TextMessage)messageChain.get(0)).getText().trim();
            if (message.startsWith("循环执行命令")) {
                messageChain.set(0, new TextMessage(message.substring(message.indexOf("循环执行命令") + 6)));
                this.forSendMessage(bot, group, messageChain, count, time);
            } else if (message.startsWith("循环执行")) {
                messageChain.set(0, new TextMessage(message.substring(message.indexOf("循环执行") + 4)));
                messageChain.add(0, new AtMessage("3889001741"));
                this.forSendMessage(bot, group, messageChain, count, time);
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
                messageChain.set(0, new TextMessage(message.substring(message.indexOf("执行") + 2)));
                messageChain.add(0, new AtMessage("3889001741"));
                group.sendMessage(messageChain);
            }
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 弟子执行命令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        boolean isControlQQ = false;
        if (StringUtils.isNotBlank(bot.getBotConfig().getControlQQ())) {
            isControlQQ = ("&" + bot.getBotConfig().getControlQQ() + "&").contains("&" + member.getUserId() + "&");
        } else {
            isControlQQ = bot.getBotConfig().getMasterQQ() == member.getUserId();
        }

        if (isControlQQ && message.contains("弟子听令执行") && !message.contains("@0")) {
            Iterator iterator = messageChain.iterator();

            while(iterator.hasNext()) {
                Message timeMessage = (Message)iterator.next();
                if (!(timeMessage instanceof AtMessage)) {
                    break;
                }

                iterator.remove();
            }

            message = ((TextMessage)messageChain.get(0)).getText().trim();
            if (message.startsWith("弟子听令执行命令")) {
                message = message.substring(message.indexOf("弟子听令执行命令") + 8);
                messageChain.set(0, new TextMessage(message));
                group.sendMessage(messageChain);
            } else if (message.startsWith("弟子听令执行")) {
                message = message.substring(message.indexOf("弟子听令执行") + 6);
                messageChain.set(0, new TextMessage(message));
                messageChain.add(0, new AtMessage("3889001741"));
                group.sendMessage(messageChain);
            }
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
    )
    public void 仅执行自己命令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if (message.contains("循环执行") && !message.contains("@") && !message.contains("功能设置")) {
            Iterator<Message> iterator = messageChain.iterator();

            Message timeMessage;
            while(iterator.hasNext()) {
                timeMessage = (Message)iterator.next();
                if (!(timeMessage instanceof AtMessage)) {
                    break;
                }

                iterator.remove();
            }

            message = ((TextMessage)messageChain.get(0)).getText().trim();
            timeMessage = (Message)messageChain.get(messageChain.size() - 1);
            int time;
            int count;
            if (timeMessage instanceof TextMessage && message.contains("循环执行")) {
                String s = ((TextMessage)timeMessage).getText().trim();
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
                        ((TextMessage)timeMessage).setText(message);
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

            message = ((TextMessage)messageChain.get(0)).getText().trim();
            if (bot.getBotId() == member.getUserId()) {
                if (message.startsWith("循环执行命令")) {
                    messageChain.set(0, new TextMessage(message.substring(message.indexOf("循环执行命令") + 6)));
                    this.forSendMessage(bot, group, messageChain, count, time);
                } else if (message.startsWith("循环执行")) {
                    messageChain.set(0, new TextMessage(message.substring(message.indexOf("循环执行") + 4)));
                    messageChain.add(0, new AtMessage("3889001741"));
                    this.forSendMessage(bot, group, messageChain, count, time);
                }
            }

            boolean isControlQQ = false;
            if (StringUtils.isNotBlank(bot.getBotConfig().getControlQQ())) {
                isControlQQ = ("&" + bot.getBotConfig().getControlQQ() + "&").contains("&" + member.getUserId() + "&");
            } else {
                isControlQQ = bot.getBotConfig().getMasterQQ() == member.getUserId();
            }

            if (isControlQQ) {
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

    @GroupMessageHandler(
            isAt = true,
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void 开启无偿双修(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) {
        if ((bot.getBotConfig().isEnableAutoRepair() || group.getGroupId() == 682220759L) && message.contains("双修")) {
            Iterator iterator = messageChain.iterator();

            while(iterator.hasNext()) {
                Message timeMessage = (Message)iterator.next();
                if (!(timeMessage instanceof AtMessage)) {
                    break;
                }

                iterator.remove();
            }

            if (messageChain.get(0) instanceof TextMessage) {
                message = ((TextMessage)messageChain.get(0)).getText().trim();
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
                    if (numberMatcher.find()) {
                        numberKeyword = Integer.parseInt(numberMatcher.group());
                    }

                    messageChain.set(0, new TextMessage(textKeyword));
                    messageChain.add(0, new AtMessage("3889001741"));
                    this.forSendMessage(bot, group, messageChain, numberKeyword, 3);
                }

                if (message.startsWith("我的双修次数")) {
                    messageChain.set(0, new TextMessage("我的双修次数"));
                    messageChain.add(0, new AtMessage("3889001741"));
                    this.forSendMessage(bot, group, messageChain, 1, 1);
                }
            }
        }

    }

    private void forSendMessage(Bot bot, Group group, MessageChain messageChain, int count, int time) {
        for(int i = 0; i < count; ++i) {
            BotConfig botConfig = bot.getBotConfig();
            if (botConfig.isStop()) {
                botConfig.setStop(false);
                return;
            }

            try {
                group.sendMessage(messageChain);
                Thread.sleep((long)time * 1000L);
            } catch (Exception var9) {
            }
        }

    }

    private void executeSendAllMessage(final Group group, final MessageChain messageChain, final int count, final int time) {
        customPool.submit(new Runnable() {
            public void run() {
                Iterator var1 = BotFactory.getBots().values().iterator();

                while(var1.hasNext()) {
                    try {
                        Bot bot1 = (Bot)var1.next();
                        Group bot1Group = bot1.getGroup(group.getGroupId());
                        TestService.this.forSendMessage(bot1, bot1Group, messageChain, count, time);
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
        if (botConfig.isEnableAutoSecret() && message.contains("" + bot.getBotId())) {
            LocalDateTime now = LocalDateTime.now();
            if (message.contains("正在秘境中") && message.contains("分身乏术")) {
                long groupId = botConfig.getGroupId();
                if (botConfig.getTaskId() != 0L) {
                    groupId = botConfig.getTaskId();
                }

                bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("秘境结算"));
            }

            if (message.contains("道友现在什么都没干")) {
                botConfig.setXslTime(-1L);
                botConfig.setMjTime(-1L);
                if (now.getHour() != 12 || now.getMinute() != 40 && now.getMinute() != 41 && now.getMinute() != 42) {
                    proccessCultivation(group);
                }
            }

            String[] parts;
            if (message.contains("进行中的：") && message.contains("可结束") && message.contains("探索")) {
                if (message.contains("(原")) {
                    parts = message.split("预计|\\(原");
                } else {
                    parts = message.split("预计|分钟");
                }

                botConfig.setMjTime((long)(Double.parseDouble(parts[1]) * 60.0 * 1000.0 + (double)System.currentTimeMillis()));
                botConfig.setStartScheduled(false);
            }

            if (message.contains("进入秘境") && message.contains("探索需要花费")) {
                if (message.contains("(原")) {
                    parts = message.split("花费时间：|\\(原");
                } else {
                    parts = message.split("花费时间：|分钟");
                }

                botConfig.setMjTime((long)(Double.parseDouble(parts[1]) * 60.0 * 1000.0 + (double)System.currentTimeMillis()));
                botConfig.setStartScheduled(false);
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 悬赏令(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
        boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
        if (isGroup && isAtSelf && botConfig.getRewardMode() != 1) {
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
                proccessCultivation(group);
            }

            if (message.contains("进行中的悬赏令") && message.contains("可结束")) {
                String[] parts;
                if (message.contains("(原")) {
                    parts = message.split("预计|\\(原");
                } else {
                    parts = message.split("预计|分钟");
                }

                botConfig.setXslTime((long)(Math.ceil(Double.parseDouble(parts[1])) * 60.0 * 1000.0 + (double)System.currentTimeMillis()));
            }

            if (message.contains("进行中的悬赏令") && message.contains("已结束")) {
                botConfig.setXslTime(-1L);
                botConfig.setMjTime(-1L);
                botConfig.setStartScheduled(true);
                group.sendMessage((new MessageChain()).at("3889001741").text("悬赏令结算"));
            }

            if (message.contains("接取任务") && message.contains("成功")) {
                group.sendMessage((new MessageChain()).at("3889001741").text("悬赏令结算"));
            }
        }

    }

    @GroupMessageHandler(
            senderIds = {3889001741L}
    )
    public void 悬赏令接取(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
        BotConfig botConfig = bot.getBotConfig();
        if ((botConfig.getRewardMode() == 3 || botConfig.getRewardMode() == 4 || botConfig.getRewardMode() == 5) && message.contains("" + bot.getBotId()) && message.contains("道友的个人悬赏令")) {
            Iterator var7 = messageChain.getMessageByType(TextMessage.class).iterator();
            if (message.contains("仙阶极品") && !message.contains("两仪心经") && botConfig.getMasterQQ() == 819463350L) {
                group.sendMessage((new MessageChain()).at("" + botConfig.getMasterQQ()).text("悬赏令出货了！！！"));
            }

            while(true) {
                while(true) {
                    int index;
                    int maxPrice;
                    int receIndex;
                    do {
                        do {
                            if (!var7.hasNext()) {
                                return;
                            }

                            TextMessage textMessage = (TextMessage)var7.next();
                            message = textMessage.getText();
                        } while(!message.contains("道友的个人悬赏令"));

                        Pattern pattern = Pattern.compile("可能额外获得：(.*?)!");
                        Matcher matcher = pattern.matcher(message);
                        int count = 0;
                        index = 0;
                        maxPrice = 0;
                        receIndex = 0;

                        while(matcher.find()) {
                            String name = matcher.group(1).replaceAll("\\s", "");
                            int colonIndex = name.indexOf(58);
                            if (colonIndex >= 0) {
                                name = name.substring(colonIndex + 1).trim();
                            }

                            if (StringUtils.isNotBlank(name)) {
                                ++count;
                                ProductPrice first = this.productPriceResponse.getFirstByNameOrderByTimeDesc(name.trim());
                                if (first != null) {
                                    ++index;
                                    if (first.getPrice() > maxPrice) {
                                        maxPrice = first.getPrice();
                                        receIndex = count;
                                    }
                                }
                            }
                        }
                    } while(index != 3);

                    long groupId = botConfig.getGroupId();
                    if (botConfig.getTaskId() != 0L) {
                        groupId = botConfig.getTaskId();
                    }

                    List durations;
                    if (botConfig.getRewardMode() == 4 && maxPrice < 800) {
                        durations = extractDurations(message);
                        receIndex = findShortestDurationIndex(durations);
                        bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("悬赏令接取" + receIndex));
                    } else if (botConfig.getRewardMode() == 5 && maxPrice < 800) {
                        durations = extractRewards(message);
                        receIndex = findLongRewardsIndex(durations);
                        bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("悬赏令接取" + receIndex));
                    } else {
                        bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("悬赏令接取" + receIndex));
                    }
                }
            }
        }
    }

    public static List<Long> extractRewards(String input) {
        List<Long> rewards = new ArrayList();
        Pattern pattern = Pattern.compile("基础报酬(\\d+)修为");
        Matcher matcher = pattern.matcher(input);

        while(matcher.find()) {
            rewards.add(Long.parseLong(matcher.group(1)));
        }

        return rewards;
    }

    public static int findLongRewardsIndex(List<Long> rewardss) {
        if (rewardss.isEmpty()) {
            return 0;
        } else {
            int longIndex = 0;
            long longDuration = (Long)rewardss.get(0);

            for(int i = 1; i < rewardss.size(); ++i) {
                if ((Long)rewardss.get(i) > longDuration) {
                    longDuration = (Long)rewardss.get(i);
                    longIndex = i;
                }
            }

            return longIndex + 1;
        }
    }

    public static List<Integer> extractDurations(String input) {
        List<Integer> durations = new ArrayList();
        Pattern pattern = Pattern.compile("预计需(\\d+)分钟");
        Matcher matcher = pattern.matcher(input);

        while(matcher.find()) {
            durations.add(Integer.parseInt(matcher.group(1)));
        }

        return durations;
    }

    public static int findShortestDurationIndex(List<Integer> durations) {
        if (durations.isEmpty()) {
            return 0;
        } else {
            int shortestIndex = 0;
            int shortestDuration = (Integer)durations.get(0);

            for(int i = 1; i < durations.size(); ++i) {
                if ((Integer)durations.get(i) < shortestDuration) {
                    shortestDuration = (Integer)durations.get(i);
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
        if (botConfig.getTaskId() != 0L) {
            groupId = botConfig.getTaskId();
        }

        boolean isGroup = group.getGroupId() == botConfig.getGroupId() || group.getGroupId() == botConfig.getTaskId();
        boolean isAtSelf = message.contains("" + bot.getBotId()) || message.contains(bot.getBotName());
        if (botConfig.getRewardMode() != 1 && isGroup && isAtSelf) {
            if (message.contains("悬赏令结算") && message.contains("增加修为")) {
                bot.getBotConfig().setXslTime(-1L);
                if (botConfig.getRewardMode() != 3 && botConfig.getRewardMode() != 4 && botConfig.getRewardMode() != 5) {
                    proccessCultivation(group);
                } else {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("悬赏令刷新"));
                }
            }

            if (message.contains("烟雾缭绕") || message.contains("在秘境最深处") || message.contains("道友在秘境") || message.contains("道友进入秘境后") || message.contains("秘境内竟然") || message.contains("道友大战一番成功") || message.contains("道友大战一番不敌")) {
                bot.getBotConfig().setMjTime(-1L);
                proccessCultivation(group);
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
            if (botConfig.getTaskId() != 0L) {
                groupId = botConfig.getTaskId();
            }

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
                if (botConfig.isStop()) {
                    botConfig.setStop(false);
                    botConfig.setMjTime(-1L);
                    return;
                }

                long groupId = botConfig.getGroupId();
                if (botConfig.getTaskId() != 0L) {
                    groupId = botConfig.getTaskId();
                }

                botConfig.setMjTime(-1L);

                try {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("秘境结算"));
                    Thread.sleep(3000L);
                } catch (InterruptedException var5) {
                }
            }

        });
        BotFactory.getBots().values().forEach((bot) -> {
            BotConfig botConfig = bot.getBotConfig();
            if (botConfig.getRewardMode() != 1 && botConfig.getXslTime() > 0L && botConfig.getXslTime() < System.currentTimeMillis()) {
                if (botConfig.isStop()) {
                    botConfig.setStop(false);
                    botConfig.setXslTime(-1L);
                    return;
                }

                long groupId = botConfig.getGroupId();
                if (botConfig.getTaskId() != 0L) {
                    groupId = botConfig.getTaskId();
                }

                botConfig.setXslTime(-1L);

                try {
                    bot.getGroup(groupId).sendMessage((new MessageChain()).at("3889001741").text("悬赏令结算"));
                    Thread.sleep(3000L);
                } catch (InterruptedException var5) {
                }
            }

        });
    }
}
