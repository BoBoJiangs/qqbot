package top.sshh.qqbot.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sshh.qqbot.service.AdminAuthService;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private AdminAuthService adminAuthService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body, HttpSession session) {
        try {
            String username = body.get("username") == null ? null : String.valueOf(body.get("username"));
            String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
            boolean ok = adminAuthService.authenticate(username, password);
            if (!ok) {
                Map<String, Object> res = new HashMap<>();
                res.put("success", false);
                res.put("message", "账号或密码错误");
                return ResponseEntity.status(401).body(res);
            }
            session.setAttribute(AdminAuthService.SESSION_KEY, Boolean.TRUE);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("username", adminAuthService.username());
            return ResponseEntity.ok(res);
        } catch (Throwable e) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            return ResponseEntity.status(500).body(res);
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        boolean authenticated = Boolean.TRUE.equals(session.getAttribute(AdminAuthService.SESSION_KEY));
        Map<String, Object> res = new HashMap<>();
        res.put("authenticated", authenticated);
        if (authenticated) res.put("username", adminAuthService.username());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, Object> body, HttpSession session) {
        try {
            boolean authenticated = Boolean.TRUE.equals(session.getAttribute(AdminAuthService.SESSION_KEY));
            if (!authenticated) {
                Map<String, Object> res = new HashMap<>();
                res.put("success", false);
                res.put("message", "未登录");
                return ResponseEntity.status(401).body(res);
            }
            String oldPassword = body.get("oldPassword") == null ? null : String.valueOf(body.get("oldPassword"));
            String newPassword = body.get("newPassword") == null ? null : String.valueOf(body.get("newPassword"));
            adminAuthService.changePassword(oldPassword, newPassword);
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("message", "密码已更新");
            return ResponseEntity.ok(res);
        } catch (Throwable e) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            return ResponseEntity.status(500).body(res);
        }
    }
}

