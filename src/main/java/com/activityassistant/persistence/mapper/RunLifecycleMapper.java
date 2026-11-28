package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;
import java.util.Optional;

@Mapper
public interface RunLifecycleMapper {
    @Update("""
            UPDATE activities
            SET title=#{title}, plan_json=#{plan}
            WHERE id=#{activityId}
            """)
    void saveActivityPlan(@Param("title") String title, @Param("plan") String plan, @Param("activityId") String activityId);

    @Update("""
            UPDATE agent_runs
            SET input_json=#{input}, plan_json=#{plan}, resolution_json=#{resolution}, updated_at=#{now}
            WHERE id=#{runId}
            """)
    void saveInput(@Param("input") String input, @Param("plan") String plan, @Param("resolution") String resolution, @Param("now") String now, @Param("runId") String runId);

    @Select("""
            SELECT status
            FROM background_jobs
            WHERE id=#{runId} FOR UPDATE
            """)
    Map<String, Object> lockJobRow(@Param("runId") String runId);

    default Optional<Map<String, Object>> lockJob(String runId) {
        return Optional.ofNullable(lockJobRow(runId));
    }

    @Update("""
            UPDATE agent_runs
            SET plan_json=#{plan}, updated_at=#{now}
            WHERE id=#{runId}
            """)
    void saveRunPlan(@Param("plan") String plan, @Param("now") String now, @Param("runId") String runId);

    @Update("""
            UPDATE agent_runs
            SET status='pending', phase='planning', resolution_json=NULL, error=NULL, updated_at=#{now}
            WHERE id=#{runId} AND status IN ('waiting_input','failed')
            """)
    void resetRun(@Param("now") String now, @Param("runId") String runId);

    @Update("""
            UPDATE activities
            SET status='queued'
            WHERE id=#{activityId}
            """)
    void queueActivity(@Param("activityId") String activityId);

    @Update("""
            UPDATE background_jobs
            SET status='queued', attempts=0, next_attempt_at=NULL, started_at=NULL, finished_at=NULL, heartbeat_at=NULL, locked_by=NULL, error=NULL, updated_at=#{now}
            WHERE id=#{runId} AND status<>'running'
            """)
    int resetJob(@Param("now") String now, @Param("runId") String runId);

    @Update("""
            UPDATE agent_runs
            SET status='waiting_input', phase='planning', resolution_json=#{resolution}, error=NULL, updated_at=#{now}
            WHERE id=#{runId}
            """)
    void waitForInput(@Param("resolution") String resolution, @Param("now") String now, @Param("runId") String runId);

    @Update("""
            UPDATE activities
            SET status='waiting_input'
            WHERE id=#{activityId}
            """)
    void waitActivity(@Param("activityId") String activityId);

    @Update("""
            UPDATE background_jobs
            SET status='succeeded', finished_at=#{finishedAt}, error=NULL, locked_by=NULL, heartbeat_at=NULL, updated_at=#{now}
            WHERE id=#{runId}
            """)
    void finishJob(@Param("finishedAt") String finishedAt, @Param("now") String now, @Param("runId") String runId);

    @Select("""
            SELECT *
            FROM agent_runs
            WHERE id=#{runId}
            """)
    Map<String, Object> findRunRow(@Param("runId") String runId);

    default Optional<Map<String, Object>> findRun(String runId) {
        return Optional.ofNullable(findRunRow(runId));
    }

    @Select("""
            SELECT status
            FROM activities
            WHERE id=#{activityId} FOR UPDATE
            """)
    Map<String, Object> lockActivityStatusRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> lockActivityStatus(String activityId) {
        return Optional.ofNullable(lockActivityStatusRow(activityId));
    }

    @Select("""
            SELECT *
            FROM agent_runs
            WHERE id=#{runId} FOR UPDATE
            """)
    Map<String, Object> lockRunRow(@Param("runId") String runId);

    default Optional<Map<String, Object>> lockRun(String runId) {
        return Optional.ofNullable(lockRunRow(runId));
    }

    @Select("""
            SELECT title, raw_input, plan_json
            FROM activities
            WHERE id=#{activityId} FOR UPDATE
            """)
    Map<String, Object> lockActivityPlanRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> lockActivityPlan(String activityId) {
        return Optional.ofNullable(lockActivityPlanRow(activityId));
    }
}
