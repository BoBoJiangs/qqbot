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
                dto.setStop(getBoolean(runtimeStatus, "isStop", false));
                dto.setStartScheduled(getBoolean(runtimeStatus, "isStartScheduled", false));
                dto.setCommand(getString(runtimeStatus, "command", ""));
                dto.setVerificationStatus(getString(runtimeStatus, "verificationStatus", ""));
                dto.setChallengeMode(getInt(runtimeStatus, "challengeMode", 0));
                
                Long lastSendTime = getLong(runtimeStatus, "lastSendTime", 0L);
                if (lastSendTime > 0) {
                    dto.setLastSendTime(LocalDateTime.ofEpochSecond(lastSendTime / 1000, 0, ZoneOffset.UTC));
                }
                
                dto.setXslTime(getLong(runtimeStatus, "xslTime", 0L));
                dto.setMjTime(getLong(runtimeStatus, "mjTime", 0L));
                dto.setTaskStatusEquip(getInt(runtimeStatus, "taskStatusEquip", 0));
                dto.setTaskStatusSkills(getInt(runtimeStatus, "taskStatusSkills", 0));
                dto.setTaskStatusHerbs(getInt(runtimeStatus, "taskStatusHerbs", 0));
                dto.setStartScheduledMarket(getBoolean(runtimeStatus, "isStartScheduledMarket", false));
                dto.setStartScheduledEquip(getBoolean(runtimeStatus, "isStartScheduledEquip", false));
                dto.setStartScheduledSkills(getBoolean(runtimeStatus, "isStartScheduledSkills", false));
                dto.setStartScheduledHerbs(getBoolean(runtimeStatus, "isStartScheduledHerbs", false));
                dto.setEnableAutoBuyLowPrice(getBoolean(runtimeStatus, "isEnableAutoBuyLowPrice", false));
                dto.setStartAutoLingG(getBoolean(runtimeStatus, "isStartAutoLingG", false));
                dto.setEnableAutoCqMj(getBoolean(runtimeStatus, "isEnableAutoCqMj", false));
                dto.setEnableCheckMarket(getBoolean(runtimeStatus, "isEnableCheckMarket", false));
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
            result.put("aiCheng", persist.getAiCheng());
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
            
            BotConfigPersist persist = botConfigManager.getBotConfig(botId);
            if (persist == null) {
                persist = new BotConfigPersist();
            }
            persist.setEnableXslPriceQuery(getBoolean(configData, "enableXslPriceQuery", persist.isEnableXslPriceQuery()));
            persist.setRewardMode(getInt(configData, "rewardMode", persist.getRewardMode()));
            persist.setXslPriceLimit(getInt(configData, "xslPriceLimit", persist.getXslPriceLimit()));
            persist.setCultivationMode(getInt(configData, "cultivationMode", persist.getCultivationMode()));
            persist.setEnableAutoRepair(getBoolean(configData, "enableAutoRepair", persist.isEnableAutoRepair()));
            persist.setEnableSectMission(getBoolean(configData, "enableSectMission", persist.isEnableSectMission()));
            persist.setSectMode(getInt(configData, "sectMode", persist.getSectMode()));
            persist.setEnableAutoField(getBoolean(configData, "enableAutoField", persist.isEnableAutoField()));
            persist.setEnableAutoSecret(getBoolean(configData, "enableAutoSecret", persist.isEnableAutoSecret()));
            persist.setEnableCheckPrice(getBoolean(configData, "enableCheckPrice", persist.isEnableCheckPrice()));
            persist.setEnableGuessTheIdiom(getBoolean(configData, "enableGuessTheIdiom", persist.isEnableGuessTheIdiom()));
            persist.setEnableAutomaticReply(getBoolean(configData, "enableAutomaticReply", persist.isEnableAutomaticReply()));
            persist.setEnableAutoTask(getBoolean(configData, "enableAutoTask", persist.isEnableAutoTask()));
            persist.setAutoVerifyModel(getInt(configData, "autoVerifyModel", persist.getAutoVerifyModel()));
            persist.setControlQQ(getString(configData, "controlQQ", persist.getControlQQ()));
            persist.setGroupQQ(getString(configData, "groupQQ", persist.getGroupQQ()));
            persist.setLingShiQQ(getLong(configData, "lingShiQQ", persist.getLingShiQQ()));
            persist.setBotNumber(getInt(configData, "botNumber", persist.getBotNumber()));
            persist.setAiCheng(getString(configData, "aiCheng", persist.getAiCheng()));
            
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

    private static boolean getBoolean(Map<String, Object> data, String key, boolean def) {
        Object v = data.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean(((String) v).trim());
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return def;
    }

    private static int getInt(Map<String, Object> data, String key, int def) {
        Object v = data.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (Exception ignored) {
                return def;
            }
        }
        return def;
    }

    private static Long getLong(Map<String, Object> data, String key, Long def) {
        Object v = data.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            String t = ((String) v).trim();
            if (t.isEmpty()) return def;
            try {
                return Long.parseLong(t);
            } catch (Exception ignored) {
                return def;
            }
        }
        return def;
    }

    private static String getString(Map<String, Object> data, String key, String def) {
        Object v = data.get(key);
        if (v == null) return def;
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
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
