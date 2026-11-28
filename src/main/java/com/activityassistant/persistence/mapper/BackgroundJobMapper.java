package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;
import java.util.Optional;

@Mapper
public interface BackgroundJobMapper {
    @Select("""
            SELECT GREATEST(1, floor(extract(epoch FROM (deadline_at - clock_timestamp())) * 1000))
            FROM background_jobs
            WHERE id = #{id} AND status = 'running' AND locked_by = #{token}
              AND lease_version = #{version} AND lease_until > clock_timestamp()
              AND deadline_at > clock_timestamp()
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long remainingLeaseMillis(@Param("id") String id, @Param("token") String token,
                              @Param("version") long version);

    @Select("SELECT set_config('statement_timeout', #{millis}, true)")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    String setStatementTimeout(@Param("millis") String millis);

    @Select("SELECT set_config('lock_timeout', #{millis}, true)")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    String setLockTimeout(@Param("millis") String millis);

    @Select("""
            SELECT * FROM background_jobs
            WHERE kind=#{kind} AND ref_id=#{refId}
            """)
    Map<String, Object> findByRefRow(@Param("kind") String kind, @Param("refId") Object refId);

    default Optional<Map<String, Object>> findByRef(String kind, Object refId) {
        return Optional.ofNullable(findByRefRow(kind, refId));
    }

    @Insert("""
            INSERT INTO background_jobs (id, kind, ref_id, status, attempts, created_at, updated_at)
            VALUES (#{id}, #{kind}, #{refId}, 'queued', 0, #{createdAt}, #{updatedAt})
            ON CONFLICT (kind, ref_id) DO NOTHING
            """)
    int enqueue(@Param("id") Object id, @Param("kind") String kind, @Param("refId") Object refId, @Param("createdAt") String createdAt, @Param("updatedAt") String updatedAt);

    @Select("""
            SELECT * FROM background_jobs
            WHERE status='queued' AND (next_attempt_at IS NULL OR next_attempt_at<=#{now})
            ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
            """)
    Map<String, Object> lockNextRow(@Param("now") String now);

    default Optional<Map<String, Object>> lockNext(String now) {
        return Optional.ofNullable(lockNextRow(now));
    }

    @Update("""
            UPDATE background_jobs
            SET status='running', attempts=attempts+1, started_at=#{startedAt}, heartbeat_at=#{heartbeatAt}, locked_by=#{token}, worker_instance=#{instance}, next_attempt_at=NULL, updated_at=#{now}, lease_version=lease_version+1, lease_until=clock_timestamp()+(#{leaseSeconds} * interval '1 second'), deadline_at=clock_timestamp()+(#{maxSeconds} * interval '1 second')
            WHERE id=#{id}
            """)
    int claim(@Param("startedAt") String startedAt, @Param("heartbeatAt") String heartbeatAt, @Param("token") Object token, @Param("instance") String instance, @Param("now") String now, @Param("leaseSeconds") int leaseSeconds, @Param("maxSeconds") int maxSeconds, @Param("id") Object id);

    @Update("""
            UPDATE background_jobs
            SET heartbeat_at=#{now},lease_until=LEAST(deadline_at,clock_timestamp()+(#{leaseSeconds} * interval '1 second')),updated_at=#{updatedAt}
            WHERE id=#{id} AND status='running' AND locked_by=#{token} AND lease_version=#{leaseVersion} AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()
            """)
    int heartbeat(@Param("now") String now, @Param("leaseSeconds") int leaseSeconds, @Param("updatedAt") String updatedAt, @Param("id") Object id, @Param("token") Object token, @Param("leaseVersion") Object leaseVersion);

    @Update("""
            UPDATE background_jobs
            SET status='succeeded',finished_at=#{finishedAt},error=NULL,locked_by=NULL,heartbeat_at=NULL,lease_until=NULL,updated_at=#{updatedAt}
            WHERE id=#{id} AND status='running' AND locked_by=#{token} AND lease_version=#{leaseVersion} AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()
            """)
    int finish(@Param("finishedAt") String finishedAt, @Param("updatedAt") String updatedAt, @Param("id") Object id, @Param("token") Object token, @Param("leaseVersion") Object leaseVersion);

    @Update("""
            UPDATE background_jobs
            SET status='failed',error='活动已取消',finished_at=#{finishedAt},locked_by=NULL,heartbeat_at=NULL,lease_until=NULL
            WHERE id=#{id} AND status='running' AND locked_by=#{token} AND lease_version=#{leaseVersion}
            """)
    int cancel(@Param("finishedAt") String finishedAt, @Param("id") Object id, @Param("token") Object token, @Param("leaseVersion") Object leaseVersion);

    @Update("""
            UPDATE background_jobs
            SET status=#{status},error=#{error},finished_at=#{finishedAt},next_attempt_at=#{nextAttemptAt},locked_by=NULL,heartbeat_at=NULL,lease_until=NULL,updated_at=#{updatedAt}
            WHERE id=#{id} AND status='running' AND locked_by=#{token} AND lease_version=#{leaseVersion} AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()
            """)
    int markFailure(@Param("status") String status, @Param("error") String error, @Param("finishedAt") String finishedAt, @Param("nextAttemptAt") String nextAttemptAt, @Param("updatedAt") String updatedAt, @Param("id") Object id, @Param("token") Object token, @Param("leaseVersion") Object leaseVersion);

    @Select("""
            SELECT * FROM background_jobs
            WHERE id=#{id} AND status='running' AND (lease_until IS NULL OR lease_until<=clock_timestamp() OR deadline_at<=clock_timestamp()) FOR UPDATE
            """)
    Map<String, Object> lockExpiredRow(@Param("id") Object id);

    default Optional<Map<String, Object>> lockExpired(Object id) {
        return Optional.ofNullable(lockExpiredRow(id));
    }

    @Update("""
            UPDATE background_jobs
            SET status=#{status},error=#{error},locked_by=NULL,heartbeat_at=NULL,lease_until=NULL,lease_version=lease_version+1,next_attempt_at=#{nextAttemptAt},finished_at=#{finishedAt},updated_at=#{updatedAt}
            WHERE id=#{id}
            """)
    int recover(@Param("status") String status, @Param("error") String error, @Param("nextAttemptAt") String nextAttemptAt, @Param("finishedAt") String finishedAt, @Param("updatedAt") String updatedAt, @Param("id") Object id);

    @Update("""
            UPDATE activity_changes
            SET notification_status='failed',error=#{error}
            WHERE id=#{refId} AND notification_status='pending'
            """)
    int failActivityChange(@Param("error") String error, @Param("refId") Object refId);

    @Update("""
            UPDATE activities
            SET status=#{status}
            WHERE id=#{refId} AND status NOT IN ('cancelled','finished','ready')
            """)
    int updateActivityState(@Param("status") String status, @Param("refId") Object refId);

    @Update("""
            UPDATE agent_runs
            SET status=#{runStatus},error=#{error},updated_at=#{now}
            WHERE kind='activity' AND ref_id=#{refId} AND status NOT IN ('cancelled','succeeded')
            """)
    int updateRunState(@Param("runStatus") String runStatus, @Param("error") String error, @Param("now") String now, @Param("refId") Object refId);

    @Select("""
            SELECT * FROM background_jobs
            WHERE id=#{id}
            """)
    Map<String, Object> findByIdRow(@Param("id") Object id);

    default Optional<Map<String, Object>> findById(Object id) {
        return Optional.ofNullable(findByIdRow(id));
    }

    @Update("UPDATE reminders SET status='failed', error=#{error} WHERE id=#{refId} AND status='scheduled'")
    void failReminder(@Param("error") String error, @Param("refId") Object refId);

    @Update("UPDATE todo_reminders SET status='failed', error=#{error} WHERE id=#{refId} AND status='scheduled'")
    void failTodoReminder(@Param("error") String error, @Param("refId") Object refId);
}
