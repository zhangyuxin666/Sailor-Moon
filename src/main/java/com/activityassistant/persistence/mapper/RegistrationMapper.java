package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RegistrationMapper {
    record NewRegistration(String formId, String name, String contact, String extraJson,
                           String createdAt, String identityKey, String requestId, String cancelTokenHash) {}

    @Select(value = """
            INSERT INTO registrations
                (form_id, name, contact, extra_json, created_at, identity_key, request_id, cancel_token_hash)
            VALUES (#{formId}, #{name}, #{contact}, #{extraJson}, #{createdAt},
                    #{identityKey}, #{requestId}, #{cancelTokenHash})
            RETURNING id
            """, affectData = true)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    long insert(NewRegistration registration);

    @Select("""
            SELECT activity_id
            FROM forms
            WHERE id=#{id}
            """)
    Map<String, Object> findFormActivityRow(@Param("id") String id);

    default Optional<Map<String, Object>> findFormActivity(String id) {
        return Optional.ofNullable(findFormActivityRow(id));
    }

    @Select("""
            SELECT *
            FROM forms
            WHERE id=#{id} FOR UPDATE
            """)
    Map<String, Object> lockFormRow(@Param("id") Object id);

    default Optional<Map<String, Object>> lockForm(Object id) {
        return Optional.ofNullable(lockFormRow(id));
    }

    @Select("""
            SELECT COUNT(*) AS count
            FROM registrations
            WHERE form_id=#{id} AND status='active'
            """)
    long countActive(@Param("id") String id);

    @Select("""
            SELECT *
            FROM registrations
            WHERE form_id=#{formId} AND request_id=#{requestId}
            """)
    Map<String, Object> findReplayRow(@Param("formId") String formId, @Param("requestId") String requestId);

    default Optional<Map<String, Object>> findReplay(String formId, String requestId) {
        return Optional.ofNullable(findReplayRow(formId, requestId));
    }

    @Select("""
            SELECT *
            FROM activities
            WHERE id=#{activityId}
            """)
    Map<String, Object> findActivityRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivity(String activityId) {
        return Optional.ofNullable(findActivityRow(activityId));
    }

    @Select("""
            SELECT name,contact
            FROM registrations
            WHERE form_id=#{formId} AND status='active' AND identity_key IS NULL
            """)
    List<Map<String, Object>> listLegacyIdentities(@Param("formId") String formId);

    @Select("""
            SELECT id
            FROM registrations
            WHERE form_id=#{formId} AND identity_key=#{identity} AND status='active'
            """)
    Map<String, Object> findActiveIdentityRow(@Param("formId") String formId, @Param("identity") String identity);

    default Optional<Map<String, Object>> findActiveIdentity(String formId, String identity) {
        return Optional.ofNullable(findActiveIdentityRow(formId, identity));
    }

    @Select("""
            SELECT *
            FROM registrations
            WHERE id=#{id} AND form_id=#{formId} FOR UPDATE
            """)
    Map<String, Object> lockRegistrationRow(@Param("id") long id, @Param("formId") String formId);

    default Optional<Map<String, Object>> lockRegistration(long id, String formId) {
        return Optional.ofNullable(lockRegistrationRow(id, formId));
    }

    @Update("""
            UPDATE registrations
            SET status='cancelled',cancelled_at=#{now}
            WHERE id=#{id}
            """)
    void cancelRegistration(@Param("now") String now, @Param("id") long id);

    @Select("""
            SELECT id,status,cancel_token_hash
            FROM registrations
            WHERE id=#{id} AND form_id=#{formId}
            """)
    Map<String, Object> findReceiptRow(@Param("id") long id, @Param("formId") String formId);

    default Optional<Map<String, Object>> findReceipt(long id, String formId) {
        return Optional.ofNullable(findReceiptRow(id, formId));
    }

    @Select("""
            SELECT r.id,r.form_id,r.name,r.status
            FROM registrations r JOIN forms f ON f.id=r.form_id
            WHERE r.id=#{id} AND f.activity_id=#{activityId}
            """)
    Map<String, Object> findOwnerRegistrationRow(@Param("id") long id, @Param("activityId") String activityId);

    default Optional<Map<String, Object>> findOwnerRegistration(long id, String activityId) {
        return Optional.ofNullable(findOwnerRegistrationRow(id, activityId));
    }

    @Select("""
            SELECT *
            FROM forms
            WHERE activity_id=#{activityId}
            ORDER BY created_at LIMIT 1 FOR UPDATE
            """)
    Map<String, Object> lockActivityFormRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> lockActivityForm(String activityId) {
        return Optional.ofNullable(lockActivityFormRow(activityId));
    }

    @Update("""
            UPDATE forms
            SET capacity=#{capacity},registration_deadline=#{deadline},registration_status=#{status},version=version+1
            WHERE id=#{id}
            """)
    void updateSettings(@Param("capacity") Object capacity, @Param("deadline") String deadline, @Param("status") String status, @Param("id") Object id);

    @Select("""
            SELECT f.*,a.title AS activity_title,a.status AS activity_status,a.plan_json
            FROM forms f JOIN activities a ON a.id=f.activity_id
            WHERE f.id=#{id}
            """)
    Map<String, Object> findPublicFormRow(@Param("id") String id);

    default Optional<Map<String, Object>> findPublicForm(String id) {
        return Optional.ofNullable(findPublicFormRow(id));
    }

    @Select("""
            SELECT user_id
            FROM activities
            WHERE id=#{activityId}
            """)
    Map<String, Object> findActivityOwnerRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivityOwner(String activityId) {
        return Optional.ofNullable(findActivityOwnerRow(activityId));
    }

    @Select("""
            SELECT *
            FROM forms
            WHERE activity_id=#{activityId}
            ORDER BY created_at LIMIT 1
            """)
    Map<String, Object> findActivityFormRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivityForm(String activityId) {
        return Optional.ofNullable(findActivityFormRow(activityId));
    }

    @Select("""
            SELECT id,name,contact,extra_json,created_at,status,cancelled_at
            FROM registrations
            WHERE form_id=#{formId}
            ORDER BY id
            """)
    List<Map<String, Object>> listRegistrations(@Param("formId") Object formId);
}
