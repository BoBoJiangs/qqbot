package top.sshh.qqbot.controller;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.component.BotFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 测试控制器
 * 用于调试API问题
 */
@RestController
public class TestController {
    
    @GetMapping("/api/test")
    public Map<String, Object> test() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "API工作正常");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }
    
    @GetMapping("/api/test/bots")
    public Map<String, Object> testBots() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Map<Long, Bot> bots = BotFactory.getBots();
            result.put("status", "success");
            result.put("botCount", bots.size());
            result.put("bots", bots.keySet());
            
            // 测试第一个Bot的基本信息
            if (!bots.isEmpty()) {
                Bot firstBot = bots.values().iterator().next();
                Map<String, Object> botInfo = new HashMap<>();
                botInfo.put("botId", firstBot.getBotId());
                botInfo.put("botName", firstBot.getBotName());
                botInfo.put("groupId", firstBot.getBotConfig().getGroupId());
                result.put("firstBot", botInfo);
            }
            
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
            result.put("exception", e.getClass().getSimpleName());
        }
        
        return result;
    }
    
    @GetMapping("/api/test/config")
    public Map<String, Object> testConfig() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Map<Long, Bot> bots = BotFactory.getBots();
            if (bots.isEmpty()) {
                result.put("status", "error");
                result.put("error", "没有找到任何Bot");
                return result;
            }
            
            Bot firstBot = bots.values().iterator().next();
            Long botId = firstBot.getBotId();
            
            // 测试配置加载
            result.put("status", "success");
            result.put("botId", botId);
            result.put("botName", firstBot.getBotName());
            result.put("groupId", firstBot.getBotConfig().getGroupId());
            
            // 测试基本配置属性
            Map<String, Object> configInfo = new HashMap<>();
            configInfo.put("cultivationMode", firstBot.getBotConfig().getCultivationMode());
            configInfo.put("rewardMode", firstBot.getBotConfig().getRewardMode());
            configInfo.put("enableCheckPrice", firstBot.getBotConfig().isEnableCheckPrice());
            configInfo.put("enableAutoSecret", firstBot.getBotConfig().isEnableAutoSecret());
            result.put("config", configInfo);
            
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
            result.put("exception", e.getClass().getSimpleName());
            e.printStackTrace();
        }
        
        return result;
    }
    
    @GetMapping("/api/test/config/{botId}")
    public Map<String, Object> testBotConfig(@PathVariable Long botId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                result.put("status", "error");
                result.put("error", "Bot不存在");
                return result;
            }
            
            result.put("status", "success");
            result.put("botId", botId);
            result.put("botName", bot.getBotName());
            result.put("groupId", bot.getBotConfig().getGroupId());
            
            // 测试基本配置属性
            Map<String, Object> configInfo = new HashMap<>();
            configInfo.put("cultivationMode", bot.getBotConfig().getCultivationMode());
            configInfo.put("rewardMode", bot.getBotConfig().getRewardMode());
            configInfo.put("enableCheckPrice", bot.getBotConfig().isEnableCheckPrice());
            configInfo.put("enableAutoSecret", bot.getBotConfig().isEnableAutoSecret());
            configInfo.put("enableAutoRepair", bot.getBotConfig().isEnableAutoRepair());
            configInfo.put("enableSectMission", bot.getBotConfig().isEnableSectMission());
            configInfo.put("autoVerifyModel", bot.getBotConfig().getAutoVerifyModel());
            configInfo.put("botNumber", bot.getBotConfig().getBotNumber());
            result.put("config", configInfo);
            
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
            result.put("exception", e.getClass().getSimpleName());
            e.printStackTrace();
        }
        
        return result;
    }
}
