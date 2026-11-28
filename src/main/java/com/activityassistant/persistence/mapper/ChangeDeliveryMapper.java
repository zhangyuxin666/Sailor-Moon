package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;
import java.util.Optional;

@Mapper
public interface ChangeDeliveryMapper {
    @Select("SELECT * FROM activity_changes WHERE id = #{id}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Map<String, Object> findChangeRow(@Param("id") String id);

    default Optional<Map<String, Object>> findChange(String id) {
        return Optional.ofNullable(findChangeRow(id));
    }

    @Select("SELECT status FROM activities WHERE id = #{activityId}")
    Map<String, Object> findActivityStatusRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivityStatus(String activityId) {
        return Optional.ofNullable(findActivityStatusRow(activityId));
    }

    @Select("SELECT group_openid FROM activity_channels WHERE activity_id = #{activityId}")
    Map<String, Object> findChannelRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findChannel(String activityId) {
        return Optional.ofNullable(findChannelRow(activityId));
    }

    @Update("""
            UPDATE activity_changes
            SET notification_status = 'cancelled', completed_at = #{completedAt}
            WHERE id = #{id}
            """)
    void cancel(@Param("id") String id, @Param("completedAt") String completedAt);

    @Update("""
            UPDATE activity_changes
            SET notification_status = #{status}, error = NULL, completed_at = #{completedAt}
            WHERE id = #{id}
            """)
    void complete(@Param("id") String id, @Param("status") String status,
                  @Param("completedAt") String completedAt);

    @Update("""
            UPDATE activity_changes
            SET notification_status = #{status}, error = #{error}
            WHERE id = #{id}
            """)
    void markFailure(@Param("id") String id, @Param("status") String status,
                     @Param("error") String error);
}
