package com.activityassistant.api;

import com.activityassistant.service.NotificationStreamService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 站内消息实时推送：登录用户各自持有一条 SSE 连接。 */
@RestController
@RequestMapping("/notifications")
public class NotificationStreamController {
    private final NotificationStreamService stream;

    public NotificationStreamController(NotificationStreamService stream) {
        this.stream = stream;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(Authentication authentication) {
        return stream.subscribe(AuthController.required(authentication).id());
    }
}
