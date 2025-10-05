package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import com.zhuangxv.bot.config.BotConfig;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.component.BotFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.sshh.qqbot.data.BotConfigPersist;
import top.sshh.qqbot.service.utils.Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可视化配置管理类
 * 提供Web界面配置Bot参数的功能
 */
@Service
public class BotConfigManager {
    
    private static final Logger log = LoggerFactory.getLogger(BotConfigManager.class);
    
    
    // 缓存所有Bot的配置
    private final Map<Long, BotConfigPersist> botConfigCache = new ConcurrentHashMap<>();
    
    /**
     * 获取所有Bot的配置信息
     */
    public Map<String, Object> getAllBotConfigs() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Map<Long, Bot> bots = BotFactory.getBots();
            if (bots == null || bots.isEmpty()) {
                result.put("error", "没有找到任何Bot");
                result.put("botCount", 0);
                return result;
            }
            
            for (Bot bot : bots.values()) {
                try {
                    BotConfig botConfig = bot.getBotConfig();
                    BotConfigPersist persist = loadBotConfig(bot.getBotId());
                    
                    Map<String, Object> botInfo = new HashMap<>();
                    botInfo.put("botId", bot.getBotId());
                    botInfo.put("botName", bot.getBotName());
                    botInfo.put("groupId", botConfig.getGroupId());
                    botInfo.put("config", persist);
                    botInfo.put("runtime", getRuntimeConfig(botConfig));
                    
                    result.put(String.valueOf(bot.getBotId()), botInfo);
                } catch (Exception e) {
                    log.error("处理Bot {} 时出错: {}", bot.getBotId(), e.getMessage(), e);
                    Map<String, Object> errorInfo = new HashMap<>();
                    errorInfo.put("botId", bot.getBotId());
                    errorInfo.put("botName", bot.getBotName());
                    errorInfo.put("error", "配置加载失败: " + e.getMessage());
                    result.put(String.valueOf(bot.getBotId()), errorInfo);
                }
            }
            
            result.put("botCount", result.size());
            
        } catch (Exception e) {
            log.error("获取所有Bot配置时出错: {}", e.getMessage(), e);
            result.put("error", "获取配置失败: " + e.getMessage());
            result.put("exception", e.getClass().getSimpleName());
        }
        
        return result;
    }
    
    /**
     * 获取指定Bot的配置
     */
    public BotConfigPersist getBotConfig(Long botId) {
        return loadBotConfig(botId);
    }
    
    /**
     * 更新Bot配置
     */
    public boolean updateBotConfig(Long botId, BotConfigPersist newConfig) {
        try {
            // 验证配置参数
            if (!validateConfig(newConfig)) {
                return false;
            }
            
            // 保存到文件
            saveBotConfigToFile(botId, newConfig);
            
            // 更新到Bot运行时配置
            Bot bot = BotFactory.getBots().get(botId);
            if (bot != null) {
                applyConfigToBot(bot, newConfig);
            }
            
            // 更新缓存
            botConfigCache.put(botId, newConfig);
            
            log.info("Bot {} 配置更新成功", botId);
            return true;
            
        } catch (Exception e) {
            log.error("更新Bot配置失败: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 重置Bot配置为默认值
     */
    public boolean resetBotConfig(Long botId) {
        BotConfigPersist defaultConfig = createDefaultConfig();
        return updateBotConfig(botId, defaultConfig);
    }
    
    /**
     * 批量更新多个Bot的配置
     */
    public Map<Long, Boolean> batchUpdateConfigs(Map<Long, BotConfigPersist> configs) {
        Map<Long, Boolean> results = new HashMap<>();
        
        for (Map.Entry<Long, BotConfigPersist> entry : configs.entrySet()) {
            boolean success = updateBotConfig(entry.getKey(), entry.getValue());
            results.put(entry.getKey(), success);
        }
        
        return results;
    }
    
    /**
     * 获取配置选项的说明信息
     */
    public Map<String, Object> getConfigOptions() {
        Map<String, Object> options = new HashMap<>();
        
        // 悬赏令模式选项
        Map<String, String> rewardModes = new HashMap<>();
        rewardModes.put("1", "手动模式");
        rewardModes.put("2", "半自动模式");
        rewardModes.put("3", "全自动价值优先");
        rewardModes.put("4", "全自动时长最短");
        rewardModes.put("5", "全自动修为最高");
        options.put("rewardModes", rewardModes);
        
        // 修炼模式选项
        Map<String, String> cultivationModes = new HashMap<>();
        cultivationModes.put("0", "无");
        cultivationModes.put("1", "修炼");
        cultivationModes.put("2", "普通闭关");
        cultivationModes.put("3", "宗门闭关");
        options.put("cultivationModes", cultivationModes);
        
        // 宗门任务模式选项
        Map<String, String> sectModes = new HashMap<>();
        sectModes.put("1", "邪修查抄");
        sectModes.put("2", "所有任务");
        options.put("sectModes", sectModes);
        
        // 验证模式选项
        Map<String, String> verifyModes = new HashMap<>();
        verifyModes.put("0", "手动验证");
        verifyModes.put("1", "半自动验证");
        verifyModes.put("2", "全自动验证");
        options.put("verifyModes", verifyModes);
        
        return options;
    }
    
    /**
     * 获取Bot运行时状态
     */
    public Map<String, Object> getBotRuntimeStatus(Long botId) {
        Bot bot = BotFactory.getBots().get(botId);
        if (bot == null) {
            return null;
        }
        
        BotConfig botConfig = bot.getBotConfig();
        Map<String, Object> status = new HashMap<>();
        
        status.put("isStop", botConfig.isStop());
        status.put("isStartScheduled", botConfig.isStartScheduled());
        status.put("lastSendTime", botConfig.getLastSendTime());
        status.put("xslTime", botConfig.getXslTime());
        status.put("mjTime", botConfig.getMjTime());
        status.put("command", botConfig.getCommand());
        status.put("verificationStatus", botConfig.getVerificationStatus());
        status.put("challengeMode", botConfig.getChallengeMode());
        
        return status;
    }
    
    /**
     * 导出Bot配置
     */
    public String exportBotConfig(Long botId) {
        BotConfigPersist config = getBotConfig(botId);
        return JSON.toJSONString(config);
    }
    
    /**
     * 导入Bot配置
     */
    public boolean importBotConfig(Long botId, String configJson) {
        try {
            BotConfigPersist config = JSON.parseObject(configJson, BotConfigPersist.class);
            return updateBotConfig(botId, config);
        } catch (Exception e) {
            log.error("导入配置失败: {}", e.getMessage(), e);
            return false;
        }
    }
    
    // 私有方法
    
    private BotConfigPersist loadBotConfig(Long botId) {
        // 先从缓存获取
        BotConfigPersist cached = botConfigCache.get(botId);
        if (cached != null) {
            return cached;
        }
        
        try {
            Path path = Paths.get("./config/bot-" + botId + ".json");
            if (!Files.exists(path)) {
                // 如果配置文件不存在，返回默认配置
                return createDefaultConfig();
            }
            
            String content = Utils.readString(path);
            BotConfigPersist persist = JSON.parseObject(content, BotConfigPersist.class);
            
            // 缓存配置
            botConfigCache.put(botId, persist);
            
            return persist;
        } catch (Exception e) {
            log.error("加载Bot配置失败: {}", e.getMessage(), e);
            return createDefaultConfig();
        }
    }
    
    private void saveBotConfigToFile(Long botId, BotConfigPersist config) throws IOException {
        Path path = Paths.get("./config/bot-" + botId + ".json");
        Files.createDirectories(path.getParent());
        Files.write(path,
                JSON.toJSONString(config).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }
    
    private void applyConfigToBot(Bot bot, BotConfigPersist persist) {
        BotConfig botConfig = bot.getBotConfig();
        
        botConfig.setGroupQQ(persist.getGroupQQ());
        botConfig.setEnableSectMission(persist.isEnableSectMission());
        botConfig.setControlQQ(persist.getControlQQ());
        botConfig.setEnableAutoSecret(persist.isEnableAutoSecret());
        botConfig.setEnableXslPriceQuery(persist.isEnableXslPriceQuery());
        botConfig.setRewardMode(persist.getRewardMode());
        botConfig.setEnableCheckPrice(persist.isEnableCheckPrice());
        botConfig.setEnableGuessTheIdiom(persist.isEnableGuessTheIdiom());
        botConfig.setEnableAutomaticReply(persist.isEnableAutomaticReply());
        botConfig.setBotNumber(persist.getBotNumber());
        botConfig.setEnableAutoTask(persist.isEnableAutoTask());
        botConfig.setAutoVerifyModel(persist.getAutoVerifyModel());
        botConfig.setEnableAutoField(persist.isEnableAutoField());
        botConfig.setEnableAutoRepair(persist.isEnableAutoRepair());
        botConfig.setCultivationMode(persist.getCultivationMode());
        botConfig.setSectMode(persist.getSectMode());
        botConfig.setXslPriceLimit(persist.getXslPriceLimit());
        botConfig.setLingShiQQ(persist.getLingShiQQ());
    }
    
    private BotConfigPersist createDefaultConfig() {
        BotConfigPersist config = new BotConfigPersist();
        config.setEnableXslPriceQuery(false);
        config.setEnableAutoRepair(true);
        config.setRewardMode(5);
        config.setEnableCheckPrice(false);
        config.setEnableGuessTheIdiom(false);
        config.setCultivationMode(0);
        config.setSectMode(1);
        config.setEnableAutoField(true);
        config.setEnableAutoSecret(true);
        config.setXslPriceLimit(1000);
        config.setBotNumber(0);
        config.setEnableAutomaticReply(false);
        config.setEnableAutoTask(true);
        config.setAutoVerifyModel(0);
        config.setEnableSectMission(true);
        return config;
    }
    
    private boolean validateConfig(BotConfigPersist config) {
        if (config == null) {
            return false;
        }
        
        // 验证悬赏令模式
        if (config.getRewardMode() < 1 || config.getRewardMode() > 5) {
            return false;
        }
        
        // 验证修炼模式
        if (config.getCultivationMode() < 0 || config.getCultivationMode() > 3) {
            return false;
        }
        
        // 验证宗门任务模式
        if (config.getSectMode() < 1 || config.getSectMode() > 2) {
            return false;
        }
        
        // 验证验证模式
        if (config.getAutoVerifyModel() < 0 || config.getAutoVerifyModel() > 2) {
            return false;
        }
        
        // 验证价格限制
        if (config.getXslPriceLimit() < 0) {
            return false;
        }
        
        return true;
    }
    
    private Map<String, Object> getRuntimeConfig(BotConfig botConfig) {
        Map<String, Object> runtime = new HashMap<>();
        try {
            runtime.put("isStop", botConfig.isStop());
            runtime.put("isStartScheduled", botConfig.isStartScheduled());
            runtime.put("lastSendTime", botConfig.getLastSendTime());
            runtime.put("xslTime", botConfig.getXslTime());
            runtime.put("mjTime", botConfig.getMjTime());
            runtime.put("command", botConfig.getCommand());
            runtime.put("verificationStatus", botConfig.getVerificationStatus());
            runtime.put("challengeMode", botConfig.getChallengeMode());
            runtime.put("taskStatusEquip", botConfig.getTaskStatusEquip());
            runtime.put("taskStatusSkills", botConfig.getTaskStatusSkills());
            runtime.put("taskStatusHerbs", botConfig.getTaskStatusHerbs());
            runtime.put("isStartScheduledMarket", botConfig.isStartScheduledMarket());
            runtime.put("isStartScheduledEquip", botConfig.isStartScheduledEquip());
            runtime.put("isStartScheduledSkills", botConfig.isStartScheduledSkills());
            runtime.put("isStartScheduledHerbs", botConfig.isStartScheduledHerbs());
            runtime.put("isEnableAutoBuyLowPrice", botConfig.isEnableAutoBuyLowPrice());
            runtime.put("isStartAutoLingG", botConfig.isStartAutoLingG());
            runtime.put("isEnableAutoCqMj", botConfig.isEnableAutoCqMj());
            runtime.put("isEnableCheckMarket", botConfig.isEnableCheckMarket());
        } catch (Exception e) {
            log.error("获取运行时配置时出错: {}", e.getMessage(), e);
            runtime.put("error", "获取运行时配置失败: " + e.getMessage());
        }
        return runtime;
    }
}
