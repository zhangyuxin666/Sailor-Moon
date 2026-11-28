package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;
import java.util.Optional;

@Mapper
public interface DeliveryRecoveryMapper {
    @Select("""
            SELECT * FROM delivery_events
            WHERE id=#{deliveryId} AND activity_id=#{activityId}
            """)
    Map<String, Object> findEventRow(@Param("deliveryId") String deliveryId, @Param("activityId") String activityId);

    default Optional<Map<String, Object>> findEvent(String deliveryId, String activityId) {
        return Optional.ofNullable(findEventRow(deliveryId, activityId));
    }

    @Select("""
            SELECT organization_id FROM activity_classes
            WHERE activity_id=#{activityId}
            """)
    Map<String, Object> findActivityOrganizationRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivityOrganization(String activityId) {
        return Optional.ofNullable(findActivityOrganizationRow(activityId));
    }

    @Select("""
            SELECT * FROM outbound_deliveries
            WHERE logical_key=#{key}
            """)
    Map<String, Object> findOutboundRow(@Param("key") String key);

    default Optional<Map<String, Object>> findOutbound(String key) {
        return Optional.ofNullable(findOutboundRow(key));
    }

    @Update("""
            UPDATE delivery_events
            SET status='sent',external_id=#{externalId},error=NULL,completed_at=#{completedAt}
            WHERE id=#{deliveryId}
            """)
    int markDeliverySent(@Param("externalId") String externalId, @Param("completedAt") String completedAt, @Param("deliveryId") String deliveryId);

    @Update("""
            UPDATE activity_changes
            SET notification_status='sent',error=NULL,completed_at=#{completedAt}
            WHERE id=#{changeId} AND activity_id=#{activityId}
            """)
    int markChangeSent(@Param("completedAt") String completedAt, @Param("changeId") String changeId, @Param("activityId") String activityId);

    @Update("""
            UPDATE reminders
            SET status='sent',error=NULL
            WHERE id=#{reminderId} AND activity_id=#{activityId}
            """)
    int markReminderSent(@Param("reminderId") String reminderId, @Param("activityId") String activityId);

    @Update("""
            UPDATE background_jobs
            SET status='queued',attempts=0,next_attempt_at=NULL,error=NULL,finished_at=NULL,locked_by=NULL,lease_until=NULL,lease_version=lease_version+1
            WHERE kind='activity' AND ref_id=#{activityId} AND status='failed'
            """)
    int requeueActivityJob(@Param("activityId") String activityId);

    @Update("""
            UPDATE activities
            SET status='queued'
            WHERE id=#{activityId}
            """)
    int queueActivity(@Param("activityId") String activityId);

    @Update("""
            UPDATE agent_runs
            SET status='pending',error=NULL
            WHERE kind='activity' AND ref_id=#{activityId} AND status='failed'
            """)
    int resetActivityRun(@Param("activityId") String activityId);

    @Update("""
            UPDATE background_jobs
            SET status='succeeded',error=NULL,finished_at=#{finishedAt},locked_by=NULL,lease_until=NULL
            WHERE kind=#{kind} AND ref_id=#{reference} AND status='failed'
            """)
    int completeFailedJob(@Param("finishedAt") String finishedAt, @Param("kind") String kind, @Param("reference") String reference);

    @Select("""
            SELECT * FROM reminders
            WHERE id=#{reminderId} AND activity_id=#{activityId}
            """)
    Map<String, Object> findReminderRow(@Param("reminderId") String reminderId, @Param("activityId") String activityId);

    default Optional<Map<String, Object>> findReminder(String reminderId, String activityId) {
        return Optional.ofNullable(findReminderRow(reminderId, activityId));
    }

    @Update("""
            UPDATE background_jobs
            SET status='queued',attempts=0,error=NULL,finished_at=NULL,next_attempt_at=NULL,locked_by=NULL,lease_until=NULL,lease_version=lease_version+1
            WHERE kind='reminder' AND ref_id=#{reminderId} AND status='failed'
            """)
    int requeueReminderJob(@Param("reminderId") String reminderId);

    @Update("""
            UPDATE reminders
            SET status='scheduled',error=NULL
            WHERE id=#{reminderId}
            """)
    int scheduleReminder(@Param("reminderId") String reminderId);
}
