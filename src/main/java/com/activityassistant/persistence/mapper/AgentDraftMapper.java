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
public interface AgentDraftMapper {
    @Select("""
            SELECT a.id AS account_id, a.display_name
            FROM class_members cm JOIN accounts a ON a.id=cm.account_id
            WHERE cm.class_id=#{classId} AND a.status='active'
            ORDER BY a.student_no, a.id
            """)
    List<Map<String, Object>> listClassMembers(@Param("classId") String classId);

    @Insert("""
            INSERT INTO agent_drafts (id, organization_id, class_id, creator_id, raw_input, intent_type, complexity, draft_json, status, created_at, updated_at)
            VALUES (#{id}, #{organizationId}, #{classId}, #{creatorId}, #{rawInput}, #{intent}, #{complexity}, #{draft}, 'draft', #{createdAt}, #{updatedAt})
            """)
    void insertDraft(@Param("id") String id, @Param("organizationId") String organizationId, @Param("classId") String classId, @Param("creatorId") String creatorId, @Param("rawInput") String rawInput, @Param("intent") String intent, @Param("complexity") String complexity, @Param("draft") String draft, @Param("createdAt") String createdAt, @Param("updatedAt") String updatedAt);

    @Select("""
            SELECT d.*, c.name AS class_name
            FROM agent_drafts d JOIN classes c ON c.id=d.class_id JOIN organization_members om ON om.organization_id=d.organization_id
            WHERE d.id=#{id} AND om.account_id=#{accountId}
            """)
    Map<String, Object> findVisibleDraftRow(@Param("id") String id, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findVisibleDraft(String id, String accountId) {
        return Optional.ofNullable(findVisibleDraftRow(id, accountId));
    }

    @Select("""
            SELECT d.id, d.intent_type, d.complexity, d.status, d.result_type, d.result_id, d.created_at, d.updated_at, c.name AS class_name, d.draft_json
            FROM agent_drafts d JOIN classes c ON c.id=d.class_id JOIN organization_members om ON om.organization_id=d.organization_id
            WHERE om.account_id=#{accountId}
            ORDER BY d.created_at DESC LIMIT 100
            """)
    List<Map<String, Object>> listVisibleDrafts(@Param("accountId") String accountId);

    @Select("""
            SELECT id
            FROM agent_drafts
            WHERE id=#{id} FOR UPDATE
            """)
    Map<String, Object> lockDraftRow(@Param("id") String id);

    default Optional<Map<String, Object>> lockDraft(String id) {
        return Optional.ofNullable(lockDraftRow(id));
    }

    @Update("""
            UPDATE agent_drafts
            SET intent_type=#{intent}, complexity=#{complexity}, draft_json=#{draft}, updated_at=#{now}
            WHERE id=#{id}
            """)
    void updateDraft(@Param("intent") String intent, @Param("complexity") String complexity, @Param("draft") String draft, @Param("now") String now, @Param("id") String id);

    @Update("""
            UPDATE agent_drafts
            SET status='publishing', updated_at=#{now}
            WHERE id=#{id} AND status='draft'
            """)
    int startPublishing(@Param("now") String now, @Param("id") String id);

    @Update("""
            UPDATE agent_drafts
            SET status='published', result_type=#{resultType}, result_id=#{resultId}, updated_at=#{now}
            WHERE id=#{id}
            """)
    void publish(@Param("resultType") String resultType, @Param("resultId") String resultId, @Param("now") String now, @Param("id") String id);

    @Update("""
            UPDATE agent_drafts
            SET status='draft', updated_at=#{now}
            WHERE id=#{id}
            """)
    void restoreDraft(@Param("now") String now, @Param("id") String id);

    @Update("""
            UPDATE activities
            SET title=#{title}, plan_json=#{plan}
            WHERE id=#{id}
            """)
    void saveActivityPlan(@Param("title") String title, @Param("plan") String plan, @Param("id") String id);

    @Update("""
            UPDATE agent_runs
            SET plan_json=#{plan}, updated_at=#{now}
            WHERE id=#{id}
            """)
    void saveRunPlan(@Param("plan") String plan, @Param("now") String now, @Param("id") String id);

    @Select("""
            SELECT id, status
            FROM agent_runs
            WHERE kind='activity' AND ref_id=#{id}
            """)
    Map<String, Object> findResultRunRow(@Param("id") String id);

    default Optional<Map<String, Object>> findResultRun(String id) {
        return Optional.ofNullable(findResultRunRow(id));
    }

    @Select("""
            SELECT a.id AS account_id
            FROM class_members cm JOIN accounts a ON a.id=cm.account_id
            WHERE cm.class_id=#{classId} AND a.status='active'
            """)
    List<Map<String, Object>> listActiveMembers(@Param("classId") String classId);
}
