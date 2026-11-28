package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface ManagementMapper {
    @Select("""
            SELECT id, activity_id, content, created_at
            FROM messages
            WHERE recipient_account_id = #{accountId}
            ORDER BY id DESC LIMIT 100
            """)
    List<Map<String, Object>> listNotifications(@Param("accountId") String accountId);

    @Select("SELECT kind, ref_id, status FROM agent_runs WHERE id = #{runId}")
    Map<String, Object> findRunRow(@Param("runId") String runId);

    default Optional<Map<String, Object>> findRun(String runId) {
        return Optional.ofNullable(findRunRow(runId));
    }

    @Select("SELECT user_id FROM activities WHERE id = #{activityId}")
    Map<String, Object> findActivityOwnerRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivityOwner(String activityId) {
        return Optional.ofNullable(findActivityOwnerRow(activityId));
    }
}
