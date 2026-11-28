package com.activityassistant.api;

import com.activityassistant.service.ActivityBehaviorService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/behavior")
public class ActivityBehaviorController {
    private final ActivityBehaviorService behavior;

    public ActivityBehaviorController(ActivityBehaviorService behavior) {
        this.behavior = behavior;
    }

    record Event(UUID eventId, String activityId, String eventType, Integer durationMs) {}

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void record(Authentication auth, @RequestBody Event event) {
        behavior.record(AuthController.required(auth).id(), event.eventId(), event.activityId(),
                event.eventType(), event.durationMs());
    }

    @GetMapping("/overview")
    Map<String, Object> overview(Authentication auth) {
        return behavior.overview(AuthController.required(auth).id());
    }
}
