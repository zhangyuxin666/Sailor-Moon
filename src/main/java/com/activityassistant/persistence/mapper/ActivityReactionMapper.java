package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface ActivityReactionMapper {
    @Insert("""
            INSERT INTO activity_reactions (account_id, activity_id, kind, category)
            VALUES (#{accountId}, #{activityId}, #{kind}, #{category})
            ON CONFLICT DO NOTHING
            """)
    int add(@Param("accountId") String accountId, @Param("activityId") String activityId,
            @Param("kind") String kind, @Param("category") String category);

    @Select(value = """
            DELETE FROM activity_reactions
            WHERE account_id = #{accountId} AND activity_id = #{activityId} AND kind = #{kind}
            RETURNING category
            """, affectData = true)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    String remove(@Param("accountId") String accountId, @Param("activityId") String activityId,
                  @Param("kind") String kind);

    @Select("""
            SELECT count(*) FILTER (WHERE kind = 'like') AS like_count,
                   COALESCE(bool_or(account_id = #{accountId} AND kind = 'like'), false) AS liked,
                   COALESCE(bool_or(account_id = #{accountId} AND kind = 'favorite'), false) AS favorited
            FROM activity_reactions WHERE activity_id = #{activityId}
            """)
    Map<String, Object> status(@Param("accountId") String accountId, @Param("activityId") String activityId);

    @Select("""
            SELECT a.id, a.title, a.status, r.created_at, (a.user_id = u.username) AS owned
            FROM activity_reactions r JOIN activities a ON a.id = r.activity_id
            JOIN accounts u ON u.id = r.account_id
            WHERE r.account_id = #{accountId} AND r.kind = 'favorite' AND u.status = 'active'
              AND
            """ + BehaviorMapper.VISIBLE + " ORDER BY r.created_at DESC, a.id LIMIT 100")
    List<Map<String, Object>> favorites(@Param("accountId") String accountId);
}
