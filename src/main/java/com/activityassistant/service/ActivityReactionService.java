package com.activityassistant.service;

import com.activityassistant.persistence.mapper.ActivityReactionMapper;
import com.activityassistant.persistence.mapper.BehaviorMapper;
import com.activityassistant.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ActivityReactionService {
    private final ActivityReactionMapper reactions;
    private final BehaviorMapper behavior;

    public ActivityReactionService(ActivityReactionMapper reactions, BehaviorMapper behavior) {
        this.reactions = reactions;
        this.behavior = behavior;
    }

    public Map<String, Object> status(String accountId, String activityId) {
        requireVisible(accountId, activityId);
        return reactions.status(accountId, activityId);
    }

    public List<Map<String, Object>> favorites(String accountId) {
        return reactions.favorites(accountId);
    }

    @Transactional
    public Map<String, Object> change(String accountId, String activityId, String kind, boolean enabled) {
        int points = switch (kind) {
            case "like" -> 3;
            case "favorite" -> 5;
            default -> throw ApiException.badRequest("不支持的活动互动类型");
        };
        String category = requireVisible(accountId, activityId);
        if (enabled) {
            if (reactions.add(accountId, activityId, kind, category) == 1) {
                record(accountId, activityId, kind, category, points);
            }
        } else {
            String originalCategory = reactions.remove(accountId, activityId, kind);
            if (originalCategory != null) {
                record(accountId, activityId, kind + "_removed", originalCategory, -points);
            }
        }
        return reactions.status(accountId, activityId);
    }

    private String requireVisible(String accountId, String activityId) {
        String category = behavior.visibleCategory(accountId, activityId);
        if (category == null) throw ApiException.notFound("活动不可见");
        return category;
    }

    private void record(String accountId, String activityId, String event, String category, int points) {
        behavior.record(accountId, UUID.randomUUID().toString(), activityId, event, category, 0);
        behavior.aggregate(accountId, category, 0, 0, 0, points);
    }
}
