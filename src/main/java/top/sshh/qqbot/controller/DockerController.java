package top.sshh.qqbot.controller;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sshh.qqbot.service.DockerService;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/docker")
@CrossOrigin(origins = "*")
public class DockerController {

    @Autowired
    private DockerService dockerService;

    @GetMapping("/containers")
    public ResponseEntity<?> listContainers(@RequestParam(defaultValue = "true") boolean all) {
        try {
            List<Container> containers = dockerService.listContainers(all);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Container c : containers) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", c.getId());
                item.put("names", c.getNames());
                item.put("image", c.getImage());
                item.put("state", c.getState());
                item.put("status", c.getStatus());

                ContainerPort[] ports = c.getPorts();
                if (ports != null) {
                    List<Map<String, Object>> portList = new ArrayList<>();
                    for (ContainerPort p : ports) {
                        Map<String, Object> pm = new HashMap<>();
                        pm.put("ip", p.getIp());
                        pm.put("privatePort", p.getPrivatePort());
                        pm.put("publicPort", p.getPublicPort());
                        pm.put("type", p.getType());
                        portList.add(pm);
                    }
                    item.put("ports", portList);
                } else {
                    item.put("ports", null);
                }

                result.add(item);
            }
            return ResponseEntity.ok(result);
        } catch (Throwable e) {
            e.printStackTrace();
            Map<String, String> error = new HashMap<>();
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            error.put("message", "获取容器列表失败: " + msg);
            return ResponseEntity.status(500).body(error);
        }
    }

    @GetMapping("/diagnose")
    public ResponseEntity<?> diagnose(@RequestParam(defaultValue = "false") boolean reconnect) {
        try {
            return ResponseEntity.ok(dockerService.diagnose(reconnect));
        } catch (Throwable e) {
            e.printStackTrace();
            Map<String, String> error = new HashMap<>();
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            error.put("message", "Docker诊断失败: " + msg);
            return ResponseEntity.status(500).body(error);
        }
    }

    @PostMapping("/containers/{id}/restart")
    public ResponseEntity<Map<String, Object>> restartContainer(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (dockerService.isSelfContainerId(id)) {
                dockerService.restartContainerAsync(id, 500);
                result.put("success", true);
                result.put("message", "正在重启服务容器，页面会短暂断开，约 5-15 秒后恢复");
                return ResponseEntity.accepted().body(result);
            } else {
                dockerService.restartContainer(id);
                result.put("success", true);
                result.put("message", "Container restarted successfully");
                return ResponseEntity.ok(result);
            }
        } catch (Throwable e) {
            result.put("success", false);
            result.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/containers/{id}/stop")
    public ResponseEntity<Map<String, Object>> stopContainer(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        try {
            dockerService.stopContainer(id);
            result.put("success", true);
            result.put("message", "Container stopped successfully");
            return ResponseEntity.ok(result);
        } catch (Throwable e) {
            result.put("success", false);
            result.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            return ResponseEntity.status(500).body(result);
        }
    }

    @DeleteMapping("/containers/{id}")
    public ResponseEntity<Map<String, Object>> removeContainer(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        try {
            dockerService.removeContainer(id);
            result.put("success", true);
            result.put("message", "Container removed successfully");
            return ResponseEntity.ok(result);
        } catch (Throwable e) {
            result.put("success", false);
            result.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/napcat")
    public ResponseEntity<?> createNapcat(@RequestBody Map<String, Object> body) {
        try {
            String account = body.get("account") == null ? null : String.valueOf(body.get("account"));
            int hostPort = body.get("hostPort") == null ? 0 : Integer.parseInt(String.valueOf(body.get("hostPort")));
            return ResponseEntity.ok(dockerService.createNapcatBot(account, hostPort));
        } catch (Throwable e) {
            e.printStackTrace();
            Map<String, Object> error = new LinkedHashMap<>();
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            error.put("success", false);
            error.put("message", "创建机器人容器失败: " + msg);
            return ResponseEntity.status(500).body(error);
        }
    }
}
