package top.sshh.qqbot.service.liandan;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.message.support.ForwardNodeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** 同步发送摘要、明细卡片、命令；转发失败不会阻断可复制命令。 */
final class HerbAnalysisMessageSender {
    private static final Logger logger = LoggerFactory.getLogger(HerbAnalysisMessageSender.class);
    private static final int MAX_DETAILS_PER_FORWARD = 19;
    private static final String FORWARD_NAME = "药材分析助手";

    private HerbAnalysisMessageSender() {
    }

    static void send(Bot bot, long groupId, HerbAnalysisMessageFormatter.Report report) {
        Group group = bot.getGroup(groupId);
        if (group == null) {
            throw new IllegalStateException("未找到分析结果目标群：" + groupId);
        }
        for (String summary : report.getSummaries()) {
            sendPlainText(group, summary);
        }

        List<String> details = report.getDetails();
        for (int start = 0; start < details.size(); start += MAX_DETAILS_PER_FORWARD) {
            List<String> batch = details.subList(start, Math.min(start + MAX_DETAILS_PER_FORWARD, details.size()));
            List<ForwardNodeMessage> nodes = new ArrayList<>();
            String senderId = String.valueOf(bot.getBotId());
            nodes.add(new ForwardNodeMessage(senderId, FORWARD_NAME,
                    new MessageChain().text("药材积压分析详情\n配方、库存缺口、收益测算与调价建议")));
            for (String detail : batch) {
                nodes.add(new ForwardNodeMessage(senderId, FORWARD_NAME, new MessageChain().text(detail)));
            }
            try {
                int messageId = group.sendGroupForwardMessage(nodes);
                // SnowLuma 的消息 ID 可以是负数，只有 0 表示未取得有效 ID。
                if (messageId == 0) throw new IllegalStateException("合并转发未返回有效消息ID");
            } catch (Exception e) {
                logger.warn("药材分析合并转发失败，改用普通文本 botId={} groupId={} start={}",
                        bot.getBotId(), groupId, start, e);
                for (String detail : batch) {
                    try {
                        sendPlainText(group, "药材分析详情（合并转发失败，普通文本补发）\n" + detail);
                    } catch (Exception fallbackError) {
                        logger.error("药材分析明细补发失败 botId={} groupId={}",
                                bot.getBotId(), groupId, fallbackError);
                    }
                }
            }
        }

        for (String commands : report.getCommands()) {
            sendPlainText(group, commands);
        }
    }

    private static void sendPlainText(Group group, String text) {
        if (group.sendMessage(new MessageChain().text(text)) == 0) {
            throw new IllegalStateException("分析结果发送未返回有效消息ID");
        }
    }
}
