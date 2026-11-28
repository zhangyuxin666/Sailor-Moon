package com.activityassistant.service;

import com.activityassistant.persistence.mapper.BehaviorMapper;
import com.activityassistant.support.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@Service
public class ActivityBehaviorService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final BehaviorMapper mapper;

    public ActivityBehaviorService(BehaviorMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void record(String accountId, UUID eventId, String activityId, String type, Integer durationMs) {
        if (eventId == null || activityId == null || activityId.isBlank() || activityId.length() > 32 || type == null) {
            throw ApiException.badRequest("行为事件字段无效");
        }
        int duration = durationMs == null ? 0 : durationMs;
        int score = switch (type) {
            case "view" -> 1;
            case "share_clicked" -> 3;
            case "dwell" -> duration >= 30_000 ? 2 : 0;
            default -> throw ApiException.badRequest("不支持的行为类型");
        };
        if (duration < 0 || duration > 300_000 || ("dwell".equals(type) ? duration == 0 : duration != 0)) {
            throw ApiException.badRequest("停留时长无效");
        }
        String category = mapper.visibleCategory(accountId, activityId);
        if (category == null) throw ApiException.notFound("活动不可见");
        if (mapper.record(accountId, eventId.toString(), activityId, type, category, duration) == 1) {
            mapper.aggregate(accountId, category, "view".equals(type) ? 1 : 0, duration,
                    "share_clicked".equals(type) ? 1 : 0, score);
        }
    }

    public Map<String, Object> overview(String accountId) {
        var recommendations = mapper.recommendations(accountId, LocalDate.now(ZONE));
        boolean preview = recommendations.isEmpty();
        return Map.of("features", mapper.features(accountId),
                "recommendations", preview ? mapper.previewRecommendations(accountId) : recommendations,
                "recommendation_preview", preview);
    }

    @Scheduled(cron = "${app.behavior.recommendation-cron:0 0 8 * * *}", zone = "Asia/Shanghai")
    @Transactional
    public void generateDailyRecommendations() {
        if (mapper.tryRecommendationLock()) {
            LocalDate date = LocalDate.now(ZONE);
            mapper.clearRecommendations(date);
            mapper.generate(date);
        }
    }
}
