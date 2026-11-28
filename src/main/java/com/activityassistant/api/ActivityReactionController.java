package com.activityassistant.api;

import com.activityassistant.service.ActivityReactionService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class ActivityReactionController {
    private final ActivityReactionService reactions;

    public ActivityReactionController(ActivityReactionService reactions) {
        this.reactions = reactions;
    }

    @GetMapping("/activities/{id}/reactions")
    Map<String, Object> status(Authentication auth, @PathVariable String id) {
        return reactions.status(AuthController.required(auth).id(), id);
    }

    @PutMapping("/activities/{id}/reactions/{kind}")
    Map<String, Object> add(Authentication auth, @PathVariable String id, @PathVariable String kind) {
        return reactions.change(AuthController.required(auth).id(), id, kind, true);
    }

    @DeleteMapping("/activities/{id}/reactions/{kind}")
    Map<String, Object> remove(Authentication auth, @PathVariable String id, @PathVariable String kind) {
        return reactions.change(AuthController.required(auth).id(), id, kind, false);
    }

    @GetMapping("/me/favorites")
    Map<String, Object> favorites(Authentication auth) {
        return Map.of("favorites", reactions.favorites(AuthController.required(auth).id()));
    }
}
