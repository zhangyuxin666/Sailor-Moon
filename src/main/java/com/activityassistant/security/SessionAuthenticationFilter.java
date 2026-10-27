package com.activityassistant.security;

import com.activityassistant.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_READ_PATHS = Set.of(
            "/", "/login", "/portal", "/agent", "/assignments", "/members", "/settings", "/activity", "/privacy",
            "/auth/status", "/health", "/health/ready", "/cluster/node", "/actuator/health",
            "/actuator/health/liveness", "/actuator/health/readiness");
    private static final Set<String> AUTH_POST_PATHS = Set.of(
            "/auth/login", "/auth/bootstrap", "/auth/change-password", "/auth/logout");

    private final AuthService authService;

    public SessionAuthenticationFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(cookie -> AuthService.SESSION_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
        AccountPrincipal account = authService.findByRawToken(token).orElse(null);
        if (account != null) {
            List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + account.role().toUpperCase()));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(account, token, authorities));
            if (account.forcePasswordChange() > 0 && !allowedBeforePasswordChange(request)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"detail\":\"请先修改初始密码或重置后的密码\",\"code\":\"password_change_required\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean allowedBeforePasswordChange(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty()) {
            if (!path.equals(contextPath) && !path.startsWith(contextPath + "/")) return false;
            path = path.substring(contextPath.length());
        }
        if (path.isEmpty()) path = "/";
        // Match only canonical paths: encoded separators, matrix parameters or dot paths must not widen this allowlist.
        if (path.contains("%") || path.contains(";") || path.contains("\\") || path.contains("//") || path.contains("..")) return false;
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return PUBLIC_READ_PATHS.contains(path)
                    || path.startsWith("/assets/") || path.startsWith("/static/")
                    || path.matches("/favicon\\.[A-Za-z0-9]+")
                    || path.matches("/activity/[A-Za-z0-9_-]+")
                    || path.matches("/forms/[A-Za-z0-9_-]+")
                    || path.matches("/public/forms/[A-Za-z0-9_-]+");
        }
        if ("POST".equals(method)) {
            return AUTH_POST_PATHS.contains(path)
                    || path.matches("/forms/[A-Za-z0-9_-]+/registrations")
                    || "/internal/qq/events".equals(path);
        }
        return false;
    }
}
