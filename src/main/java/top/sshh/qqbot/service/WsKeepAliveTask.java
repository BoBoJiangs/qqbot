package top.sshh.qqbot.service;

import com.zhuangxv.bot.api.support.GetLoginInfo;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.component.BotFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SnowLuma 等协议端的 ws 服务端在客户端静默约 90 秒后会判定半开连接并强制断开，
 * 而 bot-core 客户端空闲时不发送任何心跳，导致连接反复掉线、断连窗口内的事件丢失。
 * 这里定时对每个机器人发一个轻量请求（get_group_list）保持连接活跃。
 */
@Component
public class WsKeepAliveTask {
    private static final Logger logger = LoggerFactory.getLogger(WsKeepAliveTask.class);

    @Scheduled(fixedDelay = 40000)
    public void keepAlive() {
        for (Bot bot : BotFactory.getBots().values()) {
            try {
                bot.invoke(new GetLoginInfo());
            } catch (Exception e) {
                logger.debug("ws保活请求失败 bot={}: {}", bot.getBotId(), e.getMessage());
            }
        }
    }
}
