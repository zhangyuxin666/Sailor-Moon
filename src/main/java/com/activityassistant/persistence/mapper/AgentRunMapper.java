package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;
import java.util.Optional;

@Mapper
public interface AgentRunMapper {
    @Insert("""
            INSERT INTO agent_runs (id, kind, ref_id, organization_id, actor_id, status, phase, created_at, updated_at)
            VALUES (#{id}, #{kind}, #{refId}, #{organizationId}, #{actorId}, #{status}, 'planning', #{createdAt}, #{updatedAt})
            ON CONFLICT (kind, ref_id) DO NOTHING
            """)
    void create(@Param("id") String id, @Param("kind") String kind, @Param("refId") String refId, @Param("organizationId") String organizationId, @Param("actorId") String actorId, @Param("status") String status, @Param("createdAt") String createdAt, @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE agent_runs
            SET status='succeeded', phase='delivery', result_json=#{result}, resolution_json=#{resolution}, error=NULL, updated_at=#{now}
            WHERE ref_id=#{refId} AND status<>'cancelled'
            """)
    void complete(@Param("result") String result, @Param("resolution") String resolution, @Param("now") String now, @Param("refId") String refId);

    @Update("""
            UPDATE agent_runs
            SET status='waiting_input',phase='planning',resolution_json=#{resolution},updated_at=#{now}
            WHERE kind='activity' AND ref_id=#{refId} AND status<>'cancelled'
            """)
    void markWaiting(@Param("resolution") String resolution, @Param("now") String now, @Param("refId") String refId);

    @Update("""
            UPDATE agent_runs
            SET context_json=#{context}, updated_at=#{now}
            WHERE ref_id=#{refId}
            """)
    void saveContext(@Param("context") String context, @Param("now") String now, @Param("refId") String refId);

    @Update("""
            UPDATE agent_runs
            SET resolution_json=#{resolution}, status=COALESCE(#{status}, status), error=#{error}, updated_at=#{now}
            WHERE ref_id=#{refId}
            """)
    void saveResolution(@Param("resolution") String resolution, @Param("status") String status, @Param("error") String error, @Param("now") String now, @Param("refId") String refId);

    @Select("""
            SELECT * FROM agent_runs
            WHERE kind=#{kind} AND ref_id=#{refId}
            """)
    Map<String, Object> findByRefRow(@Param("kind") String kind, @Param("refId") String refId);

    default Optional<Map<String, Object>> findByRef(String kind, String refId) {
        return Optional.ofNullable(findByRefRow(kind, refId));
    }

    @Select("""
            SELECT * FROM agent_runs
            WHERE id=#{id}
            """)
    Map<String, Object> findByIdRow(@Param("id") String id);

    default Optional<Map<String, Object>> findById(String id) {
        return Optional.ofNullable(findByIdRow(id));
    }

    @Update("""
            UPDATE agent_runs
            SET status=COALESCE(#{status}, status), phase=COALESCE(#{phase}, phase), plan_json=COALESCE(#{plan}, plan_json), result_json=COALESCE(#{result}, result_json), error=#{error}, updated_at=#{now}
            WHERE ref_id=#{refId} AND status<>'cancelled'
            """)
    void updateState(@Param("status") String status, @Param("phase") String phase, @Param("plan") String plan, @Param("result") String result, @Param("error") String error, @Param("now") String now, @Param("refId") String refId);
}
