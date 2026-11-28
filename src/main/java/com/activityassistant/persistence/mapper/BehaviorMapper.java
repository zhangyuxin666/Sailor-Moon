package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface BehaviorMapper {
    String VISIBLE = """
            (a.user_id = u.username OR EXISTS (
                SELECT 1 FROM activity_classes ac
                JOIN class_organizations co ON co.class_id = ac.class_id AND co.organization_id = ac.organization_id
                JOIN class_members cm ON cm.class_id = ac.class_id AND cm.account_id = u.id
                JOIN organization_members om ON om.organization_id = co.organization_id AND om.account_id = u.id
                WHERE ac.activity_id = a.id
            ))
            """;
    String CATEGORY = """
            CASE WHEN lower(a.title) ~ '技术|编程|人工智能|ai' THEN 'technology'
                 WHEN a.title ~ '运动|体育|篮球|足球' THEN 'sports'
                 WHEN a.title ~ '音乐|艺术|文化|绘画' THEN 'culture'
                 ELSE 'general' END
            """;

    @Select("SELECT " + CATEGORY + " FROM activities a JOIN accounts u ON u.id = #{accountId}"
            + " WHERE a.id = #{activityId} AND u.status = 'active' AND " + VISIBLE)
    String visibleCategory(@Param("accountId") String accountId, @Param("activityId") String activityId);

    @Insert("""
            INSERT INTO behavior_events (account_id, event_id, activity_id, event_type, category, duration_ms)
            VALUES (#{accountId}, CAST(#{eventId} AS uuid), #{activityId}, #{type}, #{category}, #{duration})
            ON CONFLICT DO NOTHING
            """)
    int record(@Param("accountId") String accountId, @Param("eventId") String eventId,
               @Param("activityId") String activityId, @Param("type") String type,
               @Param("category") String category, @Param("duration") int duration);

    @Insert("""
            INSERT INTO user_interest_features (account_id, category, views, dwell_ms, share_clicks, score)
            VALUES (#{accountId}, #{category}, #{views}, #{duration}, #{shares}, #{score})
            ON CONFLICT (account_id, category) DO UPDATE SET
               views = user_interest_features.views + EXCLUDED.views,
               dwell_ms = user_interest_features.dwell_ms + EXCLUDED.dwell_ms,
               share_clicks = user_interest_features.share_clicks + EXCLUDED.share_clicks,
               score = user_interest_features.score + EXCLUDED.score
            """)
    void aggregate(@Param("accountId") String accountId, @Param("category") String category,
                   @Param("views") int views, @Param("duration") int duration,
                   @Param("shares") int shares, @Param("score") int score);

    @Select("SELECT category, views, dwell_ms, share_clicks, score FROM user_interest_features "
            + "WHERE account_id = #{accountId} ORDER BY score DESC, category")
    List<Map<String, Object>> features(@Param("accountId") String accountId);

    @Select("SELECT pg_try_advisory_xact_lock(18490115)")
    boolean tryRecommendationLock();

    @Delete("DELETE FROM daily_activity_recommendations WHERE recommendation_date = #{date}")
    void clearRecommendations(@Param("date") LocalDate date);

    @Insert("""
            INSERT INTO daily_activity_recommendations (account_id, activity_id, recommendation_date, score)
            SELECT account_id, activity_id, #{date}, score FROM (
              SELECT u.id AS account_id, a.id AS activity_id, f.score,
                     row_number() OVER (PARTITION BY u.id ORDER BY f.score DESC, a.created_at DESC, a.id) AS rank
              FROM user_interest_features f JOIN accounts u ON u.id = f.account_id
              JOIN activities a ON f.category = (
            """ + CATEGORY + """
              )
              WHERE u.status = 'active' AND a.status = 'ready' AND f.score > 0 AND
            """ + VISIBLE + """
            ) candidates WHERE rank <= 5
            ON CONFLICT DO NOTHING
            """)
    int generate(@Param("date") LocalDate date);

    @Select("""
            SELECT a.id, a.title, r.score, r.recommendation_date
            FROM daily_activity_recommendations r JOIN activities a ON a.id = r.activity_id
            JOIN accounts u ON u.id = r.account_id
            WHERE r.account_id = #{accountId} AND r.recommendation_date = #{date}
              AND a.status = 'ready' AND
            """ + VISIBLE + " ORDER BY r.score DESC, a.created_at DESC, a.id")
    List<Map<String, Object>> recommendations(@Param("accountId") String accountId, @Param("date") LocalDate date);

    @Select("""
            SELECT a.id, a.title, COALESCE(f.score, 0) AS score
            FROM activities a JOIN accounts u ON u.id = #{accountId}
            LEFT JOIN user_interest_features f ON f.account_id = u.id AND f.category = (
            """ + CATEGORY + """
            )
            WHERE u.status = 'active' AND a.status = 'ready' AND
            """ + VISIBLE + " ORDER BY score DESC, a.created_at DESC, a.id LIMIT 5")
    List<Map<String, Object>> previewRecommendations(@Param("accountId") String accountId);
}
