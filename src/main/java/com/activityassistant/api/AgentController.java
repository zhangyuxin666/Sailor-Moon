package com.activityassistant.api;

import com.activityassistant.service.AgentDraftService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AgentController {
    private final AgentDraftService drafts;

    public AgentController(AgentDraftService drafts) {
        this.drafts = drafts;
    }

    @PostMapping("/agent/drafts")
    Map<String, Object> create(Authentication authentication, @Valid @RequestBody ApiModels.AgentRequest body) {
        return drafts.create(AuthController.required(authentication), body.classId(), body.text());
    }

    @GetMapping("/agent/drafts")
    Map<String, Object> list(Authentication authentication) {
        return Map.of("drafts", drafts.list(AuthController.required(authentication)));
    }

    @GetMapping("/agent/drafts/{id}")
    Map<String, Object> get(Authentication authentication, @PathVariable String id) {
        return drafts.get(AuthController.required(authentication), id);
    }

    @PutMapping("/agent/drafts/{id}")
    Map<String, Object> update(Authentication authentication, @PathVariable String id,
                               @Valid @RequestBody ApiModels.AgentDraftUpdateRequest body) {
        return drafts.update(AuthController.required(authentication), id, body.draft());
    }

    @PostMapping("/agent/drafts/{id}/publish")
    Map<String, Object> publish(Authentication authentication, @PathVariable String id) {
        return drafts.publish(AuthController.required(authentication), id);
    }
}
