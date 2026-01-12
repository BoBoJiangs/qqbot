package top.sshh.qqbot.config;

import org.springframework.stereotype.Component;
import top.sshh.qqbot.service.AdminAuthService;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class AdminAuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();

        if (!needsAuth(path)) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = req.getSession(false);
        boolean authed = session != null && Boolean.TRUE.equals(session.getAttribute(AdminAuthService.SESSION_KEY));
        if (authed) {
            chain.doFilter(request, response);
            return;
        }

        if (isHtmlRequest(req) && isPagePath(path)) {
            String next = path;
            String q = req.getQueryString();
            if (q != null && !q.isEmpty()) next = next + "?" + q;
            String location = "/login.html?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8.name());
            res.setStatus(302);
            res.setHeader("Location", location);
            return;
        }

        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"success\":false,\"message\":\"未登录\"}");
    }

    private boolean needsAuth(String path) {
        if (path == null) return false;
        if (path.startsWith("/api/docker/")) return true;
        if (path.startsWith("/api/docker")) return true;
        if (path.startsWith("/api/bot-config/")) return true;
        if (path.startsWith("/api/bot-config")) return true;
        if (path.startsWith("/api/auth/change-password")) return true;
        if (path.equals("/container-manager.html")) return true;
        if (path.equals("/bot-config.html")) return true;
        return false;
    }

    private boolean isPagePath(String path) {
        return path != null && path.endsWith(".html");
    }

    private boolean isHtmlRequest(HttpServletRequest req) {
        String accept = req.getHeader("Accept");
        if (accept == null) return false;
        return accept.contains("text/html") || accept.contains("application/xhtml+xml");
    }
}
