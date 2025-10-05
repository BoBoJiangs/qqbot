package top.sshh.qqbot.controller;

import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.component.BotFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sshh.qqbot.data.BotConfigDTO;
import top.sshh.qqbot.data.BotConfigPersist;
import top.sshh.qqbot.service.BotConfigManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Bot配置管理Web控制器
 * 提供REST API接口
 */
@RestController
@RequestMapping("/api/bot-config")
@CrossOrigin(origins = "*")
public class BotConfigWebController {
    
    @Autowired
    private BotConfigManager botConfigManager;
    
    /**
     * 获取所有Bot配置
     */
    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> getAllBotConfigs() {
        try {
            Map<String, Object> configs = botConfigManager.getAllBotConfigs();
            return ResponseEntity.ok(configs);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取配置失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 获取指定Bot的配置
     */
    @GetMapping("/{botId}")
    public ResponseEntity<Map<String, Object>> getBotConfig(@PathVariable Long botId) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Bot不存在");
                return ResponseEntity.status(404).body(error);
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
            
            // 转换为Map以避免序列化问题
            Map<String, Object> result = new HashMap<>();
            result.put("botId", dto.getBotId());
            result.put("botName", dto.getBotName());
            result.put("groupId", dto.getGroupId());
            result.put("enableXslPriceQuery", dto.isEnableXslPriceQuery());
            result.put("rewardMode", dto.getRewardMode());
            result.put("xslPriceLimit", dto.getXslPriceLimit());
            result.put("cultivationMode", dto.getCultivationMode());
            result.put("enableAutoRepair", dto.isEnableAutoRepair());
            result.put("enableSectMission", dto.isEnableSectMission());
            result.put("sectMode", dto.getSectMode());
            result.put("enableAutoField", dto.isEnableAutoField());
            result.put("enableAutoSecret", dto.isEnableAutoSecret());
            result.put("enableCheckPrice", dto.isEnableCheckPrice());
            result.put("enableGuessTheIdiom", dto.isEnableGuessTheIdiom());
            result.put("enableAutomaticReply", dto.isEnableAutomaticReply());
            result.put("enableAutoTask", dto.isEnableAutoTask());
            result.put("autoVerifyModel", dto.getAutoVerifyModel());
            result.put("controlQQ", dto.getControlQQ());
            result.put("groupQQ", dto.getGroupQQ());
            result.put("lingShiQQ", dto.getLingShiQQ());
            result.put("botNumber", dto.getBotNumber());
            result.put("isStop", dto.isStop());
            result.put("isStartScheduled", dto.isStartScheduled());
            result.put("command", dto.getCommand());
            result.put("verificationStatus", dto.getVerificationStatus());
            result.put("challengeMode", dto.getChallengeMode());
            result.put("lastSendTime", dto.getLastSendTime());
            result.put("xslTime", dto.getXslTime());
            result.put("mjTime", dto.getMjTime());
            result.put("taskStatusEquip", dto.getTaskStatusEquip());
            result.put("taskStatusSkills", dto.getTaskStatusSkills());
            result.put("taskStatusHerbs", dto.getTaskStatusHerbs());
            result.put("isStartScheduledMarket", dto.isStartScheduledMarket());
            result.put("isStartScheduledEquip", dto.isStartScheduledEquip());
            result.put("isStartScheduledSkills", dto.isStartScheduledSkills());
            result.put("isStartScheduledHerbs", dto.isStartScheduledHerbs());
            result.put("isEnableAutoBuyLowPrice", dto.isEnableAutoBuyLowPrice());
            result.put("isStartAutoLingG", dto.isStartAutoLingG());
            result.put("isEnableAutoCqMj", dto.isEnableAutoCqMj());
            result.put("isEnableCheckMarket", dto.isEnableCheckMarket());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取配置失败: " + e.getMessage());
            error.put("exception", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 更新Bot配置
     */
    @PutMapping("/{botId}")
    public ResponseEntity<Map<String, Object>> updateBotConfig(
            @PathVariable Long botId, 
            @RequestBody Map<String, Object> configData) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Bot不存在");
                return ResponseEntity.status(404).body(error);
            }
            
            // 从Map创建BotConfigPersist
            BotConfigPersist persist = new BotConfigPersist();
            persist.setEnableXslPriceQuery((Boolean) configData.getOrDefault("enableXslPriceQuery", false));
            persist.setRewardMode((Integer) configData.getOrDefault("rewardMode", 5));
            persist.setXslPriceLimit((Integer) configData.getOrDefault("xslPriceLimit", 1000));
            persist.setCultivationMode((Integer) configData.getOrDefault("cultivationMode", 0));
            persist.setEnableAutoRepair((Boolean) configData.getOrDefault("enableAutoRepair", true));
            persist.setEnableSectMission((Boolean) configData.getOrDefault("enableSectMission", true));
            persist.setSectMode((Integer) configData.getOrDefault("sectMode", 1));
            persist.setEnableAutoField((Boolean) configData.getOrDefault("enableAutoField", true));
            persist.setEnableAutoSecret((Boolean) configData.getOrDefault("enableAutoSecret", true));
            persist.setEnableCheckPrice((Boolean) configData.getOrDefault("enableCheckPrice", false));
            persist.setEnableGuessTheIdiom((Boolean) configData.getOrDefault("enableGuessTheIdiom", false));
            persist.setEnableAutomaticReply((Boolean) configData.getOrDefault("enableAutomaticReply", false));
            persist.setEnableAutoTask((Boolean) configData.getOrDefault("enableAutoTask", true));
            persist.setAutoVerifyModel((Integer) configData.getOrDefault("autoVerifyModel", 0));
            persist.setControlQQ((String) configData.get("controlQQ"));
            persist.setGroupQQ((String) configData.get("groupQQ"));
            persist.setLingShiQQ((Long) configData.get("lingShiQQ"));
            persist.setBotNumber((Integer) configData.getOrDefault("botNumber", 0));
            
            boolean success = botConfigManager.updateBotConfig(botId, persist);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            if (!success) {
                result.put("error", "配置更新失败");
            }
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "更新配置失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 重置Bot配置为默认值
     */
    @PostMapping("/{botId}/reset")
    public ResponseEntity<Map<String, Object>> resetBotConfig(@PathVariable Long botId) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Bot不存在");
                return ResponseEntity.status(404).body(error);
            }
            
            boolean success = botConfigManager.resetBotConfig(botId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            if (!success) {
                result.put("error", "重置配置失败");
            }
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "重置配置失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 获取配置选项说明
     */
    @GetMapping("/options")
    public ResponseEntity<Map<String, Object>> getConfigOptions() {
        try {
            Map<String, Object> options = botConfigManager.getConfigOptions();
            return ResponseEntity.ok(options);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取选项失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 获取Bot运行时状态
     */
    @GetMapping("/{botId}/status")
    public ResponseEntity<Map<String, Object>> getBotRuntimeStatus(@PathVariable Long botId) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                return ResponseEntity.status(404).build();
            }
            
            Map<String, Object> status = botConfigManager.getBotRuntimeStatus(botId);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取状态失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 导出Bot配置
     */
    @GetMapping("/{botId}/export")
    public ResponseEntity<Map<String, Object>> exportBotConfig(@PathVariable Long botId) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Bot不存在");
                return ResponseEntity.status(404).body(error);
            }
            
            String configJson = botConfigManager.exportBotConfig(botId);
            
            Map<String, Object> result = new HashMap<>();
            result.put("config", configJson);
            result.put("botId", botId);
            result.put("botName", bot.getBotName());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "导出配置失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 导入Bot配置
     */
    @PostMapping("/{botId}/import")
    public ResponseEntity<Map<String, Object>> importBotConfig(
            @PathVariable Long botId, 
            @RequestBody Map<String, String> request) {
        try {
            Bot bot = BotFactory.getBots().get(botId);
            if (bot == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Bot不存在");
                return ResponseEntity.status(404).body(error);
            }
            
            String configJson = request.get("config");
            if (configJson == null || configJson.trim().isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "配置JSON不能为空");
                return ResponseEntity.badRequest().body(error);
            }
            
            boolean success = botConfigManager.importBotConfig(botId, configJson);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            if (!success) {
                result.put("error", "导入配置失败");
            }
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "导入配置失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
    
    /**
     * 获取所有在线Bot列表
     */
    @GetMapping("/bots")
    public ResponseEntity<Map<String, Object>> getBots() {
        try {
            Map<String, Object> bots = new HashMap<>();
            
            for (Bot bot : BotFactory.getBots().values()) {
                Map<String, Object> botInfo = new HashMap<>();
                botInfo.put("botId", bot.getBotId());
                botInfo.put("botName", bot.getBotName());
                botInfo.put("groupId", bot.getBotConfig().getGroupId());
                botInfo.put("online", true);
                
                bots.put(String.valueOf(bot.getBotId()), botInfo);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("bots", bots);
            result.put("count", bots.size());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取Bot列表失败: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
}
