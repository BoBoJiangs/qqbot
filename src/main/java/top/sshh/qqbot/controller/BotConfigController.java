package top.sshh.qqbot.controller;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.component.BotFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.BotConfigDTO;
import top.sshh.qqbot.data.BotConfigPersist;
import top.sshh.qqbot.service.BotConfigManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Bot配置管理控制器
 * 提供配置管理的核心功能
 */
@Component
public class BotConfigController {
    
    @Autowired
    private BotConfigManager botConfigManager;
    
    /**
     * 获取所有Bot配置
     */
    public Map<String, Object> getAllBotConfigs() {
        try {
            return botConfigManager.getAllBotConfigs();
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取配置失败: " + e.getMessage());
            return error;
        }
    }
    
    /**
     * 获取指定Bot的配置
     */
    public BotConfigDTO getBotConfig(Long botId) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                return null;
            }
            
            BotConfigPersist persist = botConfigManager.getBotConfig(botId);
            BotConfigDTO dto = BotConfigDTO.fromBotConfigPersist(
                botId, 
                bot.getBotName(), 
                bot.getBotConfig().getGroupId(), 
                persist
            );
            
            // 添加运行时状态
            Map<String, Object> runtimeStatus = botConfigManager.getBotRuntimeStatus(botId);
            if (runtimeStatus != null) {
                dto.setStop((Boolean) runtimeStatus.get("isStop"));
                dto.setStartScheduled((Boolean) runtimeStatus.get("isStartScheduled"));
                dto.setCommand((String) runtimeStatus.get("command"));
                dto.setVerificationStatus((String) runtimeStatus.get("verificationStatus"));
                dto.setChallengeMode((Integer) runtimeStatus.get("challengeMode"));
                
                Long lastSendTime = (Long) runtimeStatus.get("lastSendTime");
                if (lastSendTime != null && lastSendTime > 0) {
                    dto.setLastSendTime(LocalDateTime.ofEpochSecond(lastSendTime / 1000, 0, ZoneOffset.UTC));
                }
                
                dto.setXslTime((Long) runtimeStatus.get("xslTime"));
                dto.setMjTime((Long) runtimeStatus.get("mjTime"));
                dto.setTaskStatusEquip((Integer) runtimeStatus.get("taskStatusEquip"));
                dto.setTaskStatusSkills((Integer) runtimeStatus.get("taskStatusSkills"));
                dto.setTaskStatusHerbs((Integer) runtimeStatus.get("taskStatusHerbs"));
                dto.setStartScheduledMarket((Boolean) runtimeStatus.get("isStartScheduledMarket"));
                dto.setStartScheduledEquip((Boolean) runtimeStatus.get("isStartScheduledEquip"));
                dto.setStartScheduledSkills((Boolean) runtimeStatus.get("isStartScheduledSkills"));
                dto.setStartScheduledHerbs((Boolean) runtimeStatus.get("isStartScheduledHerbs"));
                dto.setEnableAutoBuyLowPrice((Boolean) runtimeStatus.get("isEnableAutoBuyLowPrice"));
                dto.setStartAutoLingG((Boolean) runtimeStatus.get("isStartAutoLingG"));
                dto.setEnableAutoCqMj((Boolean) runtimeStatus.get("isEnableAutoCqMj"));
                dto.setEnableCheckMarket((Boolean) runtimeStatus.get("isEnableCheckMarket"));
            }
            
            return dto;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 更新Bot配置
     */
    public Map<String, Object> updateBotConfig(Long botId, BotConfigDTO configDTO) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Bot不存在");
                return error;
            }
            
            BotConfigPersist persist = configDTO.toBotConfigPersist();
            boolean success = botConfigManager.updateBotConfig(botId, persist);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            if (!success) {
                result.put("error", "配置更新失败");
            }
            
            return result;
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "更新配置失败: " + e.getMessage());
            return error;
        }
    }
    
    /**
     * 重置Bot配置为默认值
     */
    public Map<String, Object> resetBotConfig(Long botId) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Bot不存在");
                return error;
            }
            
            boolean success = botConfigManager.resetBotConfig(botId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            if (!success) {
                result.put("error", "重置配置失败");
            }
            
            return result;
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "重置配置失败: " + e.getMessage());
            return error;
        }
    }
    
    /**
     * 批量更新配置
     */
    public Map<String, Object> batchUpdateConfigs(Map<Long, BotConfigDTO> configs) {
        try {
            Map<Long, BotConfigPersist> persistMap = new HashMap<>();
            for (Map.Entry<Long, BotConfigDTO> entry : configs.entrySet()) {
                persistMap.put(entry.getKey(), entry.getValue().toBotConfigPersist());
            }
            
            Map<Long, Boolean> results = botConfigManager.batchUpdateConfigs(persistMap);
            
            Map<String, Object> result = new HashMap<>();
            result.put("results", results);
            
            return result;
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "批量更新失败: " + e.getMessage());
            return error;
        }
    }
    
    /**
     * 获取配置选项说明
     */
    public Map<String, Object> getConfigOptions() {
        try {
            return botConfigManager.getConfigOptions();
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取选项失败: " + e.getMessage());
            return error;
        }
    }
    
    /**
     * 获取Bot运行时状态
     */
    public Map<String, Object> getBotRuntimeStatus(Long botId) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                return null;
            }
            
            return botConfigManager.getBotRuntimeStatus(botId);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取状态失败: " + e.getMessage());
            return error;
        }
    }
    
    /**
     * 导出Bot配置
     */
    public Map<String, Object> exportBotConfig(Long botId) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Bot不存在");
                return error;
            }
            
            String configJson = botConfigManager.exportBotConfig(botId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("config", configJson);
            result.put("botId", botId);
            result.put("botName", bot.getBotName());
            
            return result;
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "导出配置失败: " + e.getMessage());
            return error;
        }
    }
    
    /**
     * 导入Bot配置
     */
    public Map<String, Object> importBotConfig(Long botId, String configJson) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Bot不存在");
                return error;
            }
            
            if (configJson == null || configJson.trim().isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "配置JSON不能为空");
                return error;
            }
            
            boolean success = botConfigManager.importBotConfig(botId, configJson);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            if (!success) {
                result.put("error", "导入配置失败");
            }
            
            return result;
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "导入配置失败: " + e.getMessage());
            return error;
        }
    }
    
    /**
     * 获取所有在线Bot列表
     */
    public Map<String, Object> getBots() {
        try {
            Map<String, Object> bots = new HashMap<>();
            
            for (Bot bot : BotFactory.getBots().values()) {
                Map<String, Object> botInfo = new HashMap<>();
                botInfo.put("botId", bot.getBotId());
                botInfo.put("botName", bot.getBotName());
                botInfo.put("groupId", bot.getBotConfig().getGroupId());
                botInfo.put("online", true); // 这里可以添加更复杂的在线状态检测
                
                bots.put(String.valueOf(bot.getBotId()), botInfo);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("bots", bots);
            result.put("count", bots.size());
            
            return result;
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取Bot列表失败: " + e.getMessage());
            return error;
        }
    }
}