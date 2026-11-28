package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface RecapMapper {
    @Select("SELECT 1 FROM activity_classes WHERE activity_id=#{activityId}")
    Map<String, Object> findClassLinkRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findClassLink(String activityId) {
        return Optional.ofNullable(findClassLinkRow(activityId));
    }

    @Select("SELECT 1 FROM activity_classes ac JOIN class_organizations co ON co.class_id=ac.class_id AND co.organization_id=ac.organization_id WHERE ac.activity_id=#{activityId}")
    Map<String, Object> findValidClassLinkRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findValidClassLink(String activityId) {
        return Optional.ofNullable(findValidClassLinkRow(activityId));
    }

    @Select("SELECT class_id FROM activity_classes WHERE activity_id=#{activityId}")
    Map<String, Object> findActivityClassRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivityClass(String activityId) {
        return Optional.ofNullable(findActivityClassRow(activityId));
    }

    @Update("UPDATE activity_recaps SET status='failed',error='生成超时，请新建复盘版本',completed_at=clock_timestamp() WHERE activity_id=#{activityId} AND status='generating' AND created_at<clock_timestamp()-interval '180 seconds'")
    void expireStale(@Param("activityId") String activityId);

    @Select("SELECT * FROM activity_recaps WHERE activity_id=#{activityId} AND request_id=#{requestId}")
    Map<String, Object> findByRequestRow(@Param("activityId") String activityId, @Param("requestId") String requestId);

    default Optional<Map<String, Object>> findByRequest(String activityId, String requestId) {
        return Optional.ofNullable(findByRequestRow(activityId, requestId));
    }

    @Select("SELECT rating,count(*) n FROM activity_feedback WHERE activity_id=#{activityId} GROUP BY rating")
    List<Map<String, Object>> feedbackRatings(@Param("activityId") String activityId);

    @Select("SELECT COALESCE(max(version),0)+1 n FROM activity_recaps WHERE activity_id=#{activityId}")
    Map<String, Object> nextVersion(@Param("activityId") String activityId);

    @Insert("INSERT INTO activity_recaps(id,activity_id,version,request_id,snapshot_json) VALUES(#{recapId},#{activityId},#{version},#{requestId},#{snapshot})")
    void insertRecap(@Param("recapId") String recapId, @Param("activityId") String activityId, @Param("version") int version, @Param("requestId") String requestId, @Param("snapshot") String snapshot);

    @Select("SELECT * FROM activity_recaps WHERE id=#{recapId}")
    Map<String, Object> findById(@Param("recapId") String recapId);

    @Update("UPDATE activity_recaps SET status='succeeded',body=#{body},generation_json=#{generation},completed_at=clock_timestamp(),error=NULL WHERE id=#{recapId} AND status='generating'")
    void completeRecap(@Param("body") String body, @Param("generation") String generation, @Param("recapId") String recapId);

    @Select("SELECT * FROM activity_recaps WHERE activity_id=#{activityId} ORDER BY version DESC LIMIT 20")
    List<Map<String, Object>> listRecent(@Param("activityId") String activityId);

    @Select("SELECT * FROM activity_recaps WHERE id=#{recapId} AND activity_id=#{activityId}")
    Map<String, Object> findForActivityRow(@Param("recapId") String recapId, @Param("activityId") String activityId);

    default Optional<Map<String, Object>> findForActivity(String recapId, String activityId) {
        return Optional.ofNullable(findForActivityRow(recapId, activityId));
    }

    @Update("UPDATE activities SET status='finished',version=version+1 WHERE id=#{activityId}")
    void finishActivity(@Param("activityId") String activityId);

    @Update("UPDATE forms SET registration_status='closed',version=version+1 WHERE activity_id=#{activityId}")
    void closeForms(@Param("activityId") String activityId);

    @Update("UPDATE participation_settings SET attendance_open=false,version=version+1 WHERE activity_id=#{activityId}")
    void closeAttendance(@Param("activityId") String activityId);

    @Update("UPDATE reminders SET status='cancelled' WHERE activity_id=#{activityId} AND status='scheduled'")
    void cancelReminders(@Param("activityId") String activityId);

    @Update("UPDATE background_jobs SET status='failed',error='活动已结束',locked_by=NULL,lease_until=NULL,lease_version=lease_version+1 WHERE kind='reminder' AND ref_id IN(SELECT id FROM reminders WHERE activity_id=#{activityId} AND status='cancelled') AND status IN('queued','running')")
    void cancelReminderJobs(@Param("activityId") String activityId);

    @Select("SELECT count(*) n FROM registrations r JOIN forms f ON f.id=r.form_id WHERE f.activity_id=#{activityId} AND r.status='active'")
    long countActiveRegistrations(@Param("activityId") String activityId);

    @Select("SELECT count(*) n FROM registrations r JOIN forms f ON f.id=r.form_id WHERE f.activity_id=#{activityId} AND r.status='cancelled'")
    long countCancelledRegistrations(@Param("activityId") String activityId);

    @Select("SELECT count(*) n FROM tasks WHERE activity_id=#{activityId}")
    long countTasks(@Param("activityId") String activityId);

    @Select("SELECT count(*) n FROM tasks WHERE activity_id=#{activityId} AND status='done'")
    long countCompletedTasks(@Param("activityId") String activityId);

    @Select("SELECT count(*) n FROM tasks t WHERE activity_id=#{activityId} AND NOT EXISTS(SELECT 1 FROM activity_task_assignees a WHERE a.task_id=t.id)")
    long countUnassignedTasks(@Param("activityId") String activityId);

    @Select("SELECT count(*) n FROM participation_settings WHERE activity_id=#{activityId} AND roster_confirmed=true")
    long countAttendanceSettings(@Param("activityId") String activityId);

    @Select("SELECT count(*) n FROM activity_participants WHERE activity_id=#{activityId}")
    long countParticipants(@Param("activityId") String activityId);

    @Select("SELECT count(*) n FROM activity_check_ins WHERE activity_id=#{activityId}")
    long countCheckIns(@Param("activityId") String activityId);

    @Select("SELECT count(*) n FROM activity_feedback WHERE activity_id=#{activityId}")
    long countFeedback(@Param("activityId") String activityId);

    @Select("SELECT COALESCE(sum(rating),0) n FROM activity_feedback WHERE activity_id=#{activityId}")
    long sumFeedbackRatings(@Param("activityId") String activityId);

    @Select("SELECT count(*) n FROM reminders WHERE activity_id=#{activityId}")
    long countReminders(@Param("activityId") String activityId);

    @Select("SELECT status, count(*) n FROM steps WHERE activity_id=#{activityId} GROUP BY status")
    List<Map<String,Object>> stepStatuses(@Param("activityId") String activityId);

    @Select("SELECT status, count(*) n FROM delivery_events WHERE activity_id=#{activityId} GROUP BY status")
    List<Map<String,Object>> deliveryStatuses(@Param("activityId") String activityId);

    @Select("SELECT status, count(*) n FROM reminders WHERE activity_id=#{activityId} GROUP BY status")
    List<Map<String,Object>> reminderStatuses(@Param("activityId") String activityId);
}
