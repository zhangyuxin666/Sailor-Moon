package com.activityassistant.api;

import com.activityassistant.persistence.Db;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URI;
import java.util.Map;

/**
 * 页面控制器。
 * 迁移到 React SPA 后，所有页面路由统一返回构建出的 index.html，
 * 由前端 React Router 负责客户端路由。深层路径（/activity/{id}、/forms/{id}）
 * 也必须返回 index.html，否则刷新时 404。
 */
@Controller
public class PageController {
    private final Db db;

    public PageController(Db db) {
        this.db = db;
    }

    @GetMapping("/")
    ResponseEntity<?> root(Authentication authentication) {
        return redirect(authentication == null ? "/login" : "/portal");
    }

    // ---- SPA 页面路由：全部返回 index.html ----

    @GetMapping({"/login", "/portal", "/agent", "/assignments", "/members",
            "/settings", "/activity", "/privacy"})
    ResponseEntity<ClassPathResource> spaPage() {
        return indexHtml();
    }

    /** 活动详情深层路径，如 /activity/123 */
    @GetMapping("/activity/{activityId}")
    ResponseEntity<ClassPathResource> activityDetail() {
        return indexHtml();
    }

    /** 公开报名深层路径，如 /forms/abc */
    @GetMapping("/forms/{formId}")
    ResponseEntity<ClassPathResource> publicForm() {
        return indexHtml();
    }

    // ---- 健康检查 ----

    @ResponseBody
    @GetMapping("/health")
    Map<String, String> health() { return Map.of("status", "ok"); }

    @ResponseBody
    @GetMapping("/health/ready")
    Map<String, String> ready() {
        db.required("SELECT 1 AS ok");
        return Map.of("status", "ready", "database", "ok");
    }

    // ---- 私有方法 ----

    /** 返回 SPA 构建产物 index.html，不缓存以确保每次拿到最新版本 */
    private ResponseEntity<ClassPathResource> indexHtml() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.VARY, "Cookie")
                .body(new ClassPathResource("static/index.html"));
    }

    private ResponseEntity<Void> redirect(String path) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(path))
                .cacheControl(CacheControl.noStore()).build();
    }
}
