package com.activityassistant.api;

import com.activityassistant.service.AnalyticsService;
import com.activityassistant.service.AnalyticsStreamService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** 数据大屏接口。身份从登录会话获取，不信任客户端传入组织信息。 */
@RestController
@RequestMapping("/analytics")
public class AnalyticsController {
    private final AnalyticsService analytics;
    private final AnalyticsStreamService stream;

    public AnalyticsController(AnalyticsService analytics, AnalyticsStreamService stream) {
        this.analytics = analytics;
        this.stream = stream;
    }

    @GetMapping("/overview")
    Map<String, Object> overview(Authentication authentication,
                                 @RequestParam(defaultValue = "30") int days,
                                 @RequestParam(name = "class_id", required = false) String classId) {
        return analytics.overview(AuthController.required(authentication), days, classId);
    }

    /** SSE：数据库有变化时立即推送新快照，前端不再轮询。 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(Authentication authentication,
                      @RequestParam(defaultValue = "30") int days,
                      @RequestParam(name = "class_id", required = false) String classId) {
        int safeDays = days == 7 || days == 30 || days == 90 ? days : 30;
        String safeClass = classId == null || classId.isBlank() ? null : classId;
        return stream.subscribe(AuthController.required(authentication), safeDays, safeClass);
    }
}
