package com.activityassistant.api;

import com.activityassistant.service.AnalyticsService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 数据大屏接口。身份从登录会话获取，不信任客户端传入组织信息。 */
@RestController
@RequestMapping("/analytics")
public class AnalyticsController {
    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/overview")
    Map<String, Object> overview(Authentication authentication,
                                 @RequestParam(defaultValue = "30") int days,
                                 @RequestParam(name = "class_id", required = false) String classId) {
        return analytics.overview(AuthController.required(authentication), days, classId);
    }
}
