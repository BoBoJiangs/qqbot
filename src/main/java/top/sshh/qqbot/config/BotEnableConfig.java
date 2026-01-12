package top.sshh.qqbot.config;

import com.zhuangxv.bot.EnableBot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "botcore.enabled", havingValue = "true", matchIfMissing = true)
@EnableBot
public class BotEnableConfig {
}

