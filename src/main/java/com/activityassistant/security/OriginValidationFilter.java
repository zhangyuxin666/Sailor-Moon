package com.activityassistant.security;

import com.activityassistant.config.AppProperties;
import com.activityassistant.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

@Component
public class OriginValidationFilter extends OncePerRequestFilter {
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public OriginValidationFilter(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean hasSession = request.getCookies() != null && Arrays.stream(request.getCookies())
                .anyMatch(cookie -> AuthService.SESSION_COOKIE.equals(cookie.getName()));
        String origin = request.getHeader("Origin");
        if (MUTATING.contains(request.getMethod()) && hasSession && origin != null && !allowed(origin)) {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), Map.of("detail", "请求来源不受信任"));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(String origin) {
        String value = origin.replaceAll("/$", "");
        return value.equals(properties.publicBaseUrl().replaceAll("/$", ""))
                || value.matches("https?://(127\\.0\\.0\\.1|localhost)(:\\d+)?");
    }
}
