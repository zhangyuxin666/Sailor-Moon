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
public interface ParticipationMapper {
    @Select("""
            SELECT 1
            FROM activity_classes
            WHERE activity_id=#{id}
            """)
    Map<String, Object> findActivityClassRow(@Param("id") String id);

    default Optional<Map<String, Object>> findActivityClass(String id) {
        return Optional.ofNullable(findActivityClassRow(id));
    }

    @Select("""
            SELECT 1
            FROM activity_classes ac
            JOIN class_organizations co ON co.class_id=ac.class_id AND co.organization_id=ac.organization_id
            WHERE ac.activity_id=#{id}
            """)
    Map<String, Object> findValidClassRow(@Param("id") String id);

    default Optional<Map<String, Object>> findValidClass(String id) {
        return Optional.ofNullable(findValidClassRow(id));
    }

    @Select("""
            SELECT ac.class_id
            FROM activity_classes ac
            JOIN class_organizations co ON co.class_id=ac.class_id AND co.organization_id=ac.organization_id
            WHERE ac.activity_id=#{id}
            """)
    Map<String, Object> findClassIdRow(@Param("id") String id);

    default Optional<Map<String, Object>> findClassId(String id) {
        return Optional.ofNullable(findClassIdRow(id));
    }

    @Select("""
            SELECT 1
            FROM activity_classes ac
            JOIN class_organizations co ON co.class_id=ac.class_id AND co.organization_id=ac.organization_id
            JOIN class_members cm ON cm.class_id=ac.class_id
            JOIN accounts a ON a.id=cm.account_id
            JOIN organization_members om ON om.organization_id=ac.organization_id AND om.account_id=a.id
            WHERE ac.activity_id=#{id} AND a.id=#{accountId} AND a.status='active'
            """)
    Map<String, Object> findMemberRow(@Param("id") String id, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findMember(String id, String accountId) {
        return Optional.ofNullable(findMemberRow(id, accountId));
    }

    @Select("""
            SELECT 1
            FROM activity_participants
            WHERE activity_id=#{id} AND account_id=#{accountId}
            """)
    Map<String, Object> findRosterMemberRow(@Param("id") String id, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findRosterMember(String id, String accountId) {
        return Optional.ofNullable(findRosterMemberRow(id, accountId));
    }

    @Select("""
            SELECT attendance_open,feedback_open,version,roster_confirmed
            FROM participation_settings
            WHERE activity_id=#{id}
            """)
    Map<String, Object> findSettingsRow(@Param("id") String id);

    default Optional<Map<String, Object>> findSettings(String id) {
        return Optional.ofNullable(findSettingsRow(id));
    }

    @Select("""
            SELECT kind,payload_json,result_json
            FROM participation_requests
            WHERE activity_id=#{id} AND actor_id=#{actorId} AND request_id=#{requestId}
            """)
    Map<String, Object> findRequestRow(@Param("id") String id, @Param("actorId") String actorId, @Param("requestId") String requestId);

    default Optional<Map<String, Object>> findRequest(String id, String actorId, String requestId) {
        return Optional.ofNullable(findRequestRow(id, actorId, requestId));
    }

    @Insert("""
            INSERT INTO participation_requests(activity_id,actor_id,request_id,kind,payload_json,result_json,created_at) VALUES(#{id},#{actorId},#{requestId},#{kind},#{payload},#{result},#{createdAt})
            """)
    void saveRequest(@Param("id") String id, @Param("actorId") String actorId, @Param("requestId") String requestId, @Param("kind") String kind, @Param("payload") String payload, @Param("result") String result, @Param("createdAt") String createdAt);

    @Insert("""
            INSERT INTO activity_participants(activity_id,account_id,added_at) SELECT ac.activity_id,a.id,#{createdAt}
            FROM activity_classes ac
            JOIN class_organizations co ON co.class_id=ac.class_id AND co.organization_id=ac.organization_id
            JOIN class_members cm ON cm.class_id=ac.class_id
            JOIN accounts a ON a.id=cm.account_id
            JOIN organization_members om ON om.organization_id=ac.organization_id AND om.account_id=a.id
            WHERE ac.activity_id=#{id} AND a.status='active'
            ON CONFLICT DO NOTHING
            """)
    void snapshotRoster(@Param("createdAt") String createdAt, @Param("id") String id);

    @Insert("""
            INSERT INTO participation_settings(activity_id,attendance_open,feedback_open,version,roster_confirmed) VALUES(#{id},#{attendance},#{feedback},2,#{confirmed}) ON CONFLICT(activity_id) DO UPDATE SET attendance_open=EXCLUDED.attendance_open,feedback_open=EXCLUDED.feedback_open,version=participation_settings.version+1,roster_confirmed=participation_settings.roster_confirmed OR EXCLUDED.roster_confirmed
            """)
    void saveSettings(@Param("id") String id, @Param("attendance") boolean attendance, @Param("feedback") boolean feedback, @Param("confirmed") boolean confirmed);

    @Insert("""
            INSERT INTO activity_check_ins(activity_id,account_id,checked_by,checked_in_at) VALUES(#{id},#{accountId},#{checkedBy},#{now})
            ON CONFLICT DO NOTHING
            """)
    int insertCheckIn(@Param("id") String id, @Param("accountId") String accountId, @Param("checkedBy") String checkedBy, @Param("now") String now);

    @Select("""
            SELECT checked_in_at
            FROM activity_check_ins
            WHERE activity_id=#{id} AND account_id=#{accountId}
            """)
    Map<String, Object> findCheckInRow(@Param("id") String id, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findCheckIn(String id, String accountId) {
        return Optional.ofNullable(findCheckInRow(id, accountId));
    }

    @Select("""
            SELECT 1
            FROM activity_check_ins
            WHERE activity_id=#{id} AND account_id=#{accountId}
            """)
    Map<String, Object> hasCheckInRow(@Param("id") String id, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> hasCheckIn(String id, String accountId) {
        return Optional.ofNullable(hasCheckInRow(id, accountId));
    }

    @Select("""
            SELECT version
            FROM activity_feedback
            WHERE activity_id=#{id} AND account_id=#{accountId}
            """)
    Map<String, Object> findFeedbackVersionRow(@Param("id") String id, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findFeedbackVersion(String id, String accountId) {
        return Optional.ofNullable(findFeedbackVersionRow(id, accountId));
    }

    @Insert("""
            INSERT INTO activity_feedback(activity_id,account_id,rating,comment,version,created_at,updated_at) VALUES(#{id},#{accountId},#{rating},#{comment},1,#{now},#{updatedAt})
            """)
    void insertFeedback(@Param("id") String id, @Param("accountId") String accountId, @Param("rating") int rating, @Param("comment") String comment, @Param("now") String now, @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE activity_feedback SET rating=#{rating},comment=#{comment},version=version+1,updated_at=#{now}
            WHERE activity_id=#{id} AND account_id=#{accountId}
            """)
    void updateFeedback(@Param("rating") int rating, @Param("comment") String comment, @Param("now") String now, @Param("id") String id, @Param("accountId") String accountId);

    @Select("""
            SELECT rating,comment,version
            FROM activity_feedback
            WHERE activity_id=#{id} AND account_id=#{accountId}
            """)
    Map<String, Object> findFeedbackRow(@Param("id") String id, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findFeedback(String id, String accountId) {
        return Optional.ofNullable(findFeedbackRow(id, accountId));
    }

    @Select("""
            SELECT DISTINCT a.id,a.title,a.status,a.plan_json,COALESCE(p.attendance_open,false) attendance_open,COALESCE(p.feedback_open,false) feedback_open,(ci.account_id IS NOT NULL) checked_in,(f.account_id IS NOT NULL) feedback_submitted
            FROM activities a
            JOIN activity_classes ac ON ac.activity_id=a.id
            JOIN class_organizations co ON co.class_id=ac.class_id AND co.organization_id=ac.organization_id
            JOIN class_members cm ON cm.class_id=ac.class_id
            JOIN accounts member ON member.id=cm.account_id AND member.status='active'
            JOIN organization_members om ON om.account_id=member.id AND om.organization_id=ac.organization_id
            LEFT JOIN participation_settings p ON p.activity_id=a.id
            LEFT JOIN activity_check_ins ci ON ci.activity_id=a.id AND ci.account_id=cm.account_id
            LEFT JOIN activity_feedback f ON f.activity_id=a.id AND f.account_id=cm.account_id
            WHERE cm.account_id=#{accountId} AND a.status IN('ready','finished','cancelled')
            ORDER BY a.id
            """)
    List<Map<String, Object>> listMine(@Param("accountId") String accountId);

    @Select("""
            WITH member_ids AS(SELECT cm.account_id
            FROM activity_classes ac
            JOIN class_organizations co ON co.class_id=ac.class_id AND co.organization_id=ac.organization_id
            JOIN class_members cm ON cm.class_id=ac.class_id
            JOIN accounts a ON a.id=cm.account_id
            JOIN organization_members om ON om.account_id=a.id AND om.organization_id=ac.organization_id
            WHERE ac.activity_id=#{id} AND a.status='active'), ids AS(SELECT account_id
            FROM activity_participants
            WHERE activity_id=#{rosterId} UNION SELECT account_id
            FROM member_ids) SELECT ids.account_id,COALESCE(a.display_name,'已离开成员') display_name,COALESCE(a.username,'') username,COALESCE(a.student_no,'') student_no,(m.account_id IS NOT NULL) current_member,(m.account_id IS NOT NULL AND (r.account_id IS NOT NULL OR #{confirmed}=false)) eligible,(r.account_id IS NOT NULL) rostered,(ci.account_id IS NOT NULL) checked_in,ci.checked_in_at,(f.account_id IS NOT NULL) feedback_submitted
            FROM ids
            LEFT JOIN accounts a ON a.id=ids.account_id
            LEFT JOIN member_ids m ON m.account_id=ids.account_id
            LEFT JOIN activity_participants r ON r.activity_id=#{participantId} AND r.account_id=ids.account_id
            LEFT JOIN activity_check_ins ci ON ci.activity_id=#{checkInId} AND ci.account_id=ids.account_id
            LEFT JOIN activity_feedback f ON f.activity_id=#{feedbackId} AND f.account_id=ids.account_id
            ORDER BY a.student_no,ids.account_id
            """)
    List<Map<String, Object>> listParticipants(@Param("id") String id, @Param("rosterId") String rosterId, @Param("confirmed") boolean confirmed, @Param("participantId") String participantId, @Param("checkInId") String checkInId, @Param("feedbackId") String feedbackId);

    @Select("""
            SELECT f.account_id,COALESCE(a.display_name,'已离开成员') display_name,f.rating,f.comment,f.version,f.updated_at
            FROM activity_feedback f
            LEFT JOIN accounts a ON a.id=f.account_id
            WHERE f.activity_id=#{id}
            ORDER BY f.updated_at,f.account_id
            """)
    List<Map<String, Object>> listFeedback(@Param("id") String id);
}
