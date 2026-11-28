package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface TemplateMapper {
    @Select("""
            SELECT role
            FROM organization_members
            WHERE organization_id=#{organizationId} AND account_id=#{accountId}
            """)
    Map<String, Object> findMembershipRow(@Param("organizationId") String organizationId, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findMembership(String organizationId, String accountId) {
        return Optional.ofNullable(findMembershipRow(organizationId, accountId));
    }

    @Select("""
            SELECT *
            FROM business_templates
            WHERE organization_id=#{organizationId}
            ORDER BY updated_at DESC,id LIMIT 200
            """)
    List<Map<String, Object>> listTemplates(@Param("organizationId") String organizationId);

    @Select("""
            SELECT *
            FROM business_templates
            WHERE id=#{id} AND organization_id=#{organizationId}
            """)
    Map<String, Object> findTemplateRow(@Param("id") String id, @Param("organizationId") String organizationId);

    default Optional<Map<String, Object>> findTemplate(String id, String organizationId) {
        return Optional.ofNullable(findTemplateRow(id, organizationId));
    }

    @Select("""
            SELECT id,status,draft_json
            FROM agent_drafts
            WHERE organization_id=#{organizationId} AND creator_id=#{creatorId}
            ORDER BY updated_at DESC,id LIMIT 200
            """)
    List<Map<String, Object>> listSourceDrafts(@Param("organizationId") String organizationId, @Param("creatorId") String creatorId);

    @Select("""
            SELECT a.id,a.title,a.status
            FROM activities a JOIN activity_classes ac ON ac.activity_id=a.id
            WHERE ac.organization_id=#{organizationId} AND a.user_id=#{username}
            ORDER BY a.created_at DESC,a.id LIMIT 200
            """)
    List<Map<String, Object>> listSourceActivities(@Param("organizationId") String organizationId, @Param("username") String username);

    @Select("""
            SELECT draft_json
            FROM agent_drafts
            WHERE id=#{id} AND organization_id=#{organizationId} AND creator_id=#{creatorId}
            """)
    Map<String, Object> findSourceDraftRow(@Param("id") String id, @Param("organizationId") String organizationId, @Param("creatorId") String creatorId);

    default Optional<Map<String, Object>> findSourceDraft(String id, String organizationId, String creatorId) {
        return Optional.ofNullable(findSourceDraftRow(id, organizationId, creatorId));
    }

    @Select("""
            SELECT organization_id
            FROM activity_classes
            WHERE activity_id=#{activityId}
            """)
    Map<String, Object> findActivityScopeRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivityScope(String activityId) {
        return Optional.ofNullable(findActivityScopeRow(activityId));
    }

    @Select("""
            SELECT plan_json
            FROM activities
            WHERE id=#{activityId}
            """)
    Map<String, Object> findActivityPlan(@Param("activityId") String activityId);

    @Select("""
            SELECT config_json
            FROM activity_workflows
            WHERE activity_id=#{activityId}
            """)
    Map<String, Object> findWorkflowRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findWorkflow(String activityId) {
        return Optional.ofNullable(findWorkflowRow(activityId));
    }

    @Select("""
            SELECT id,title,priority
            FROM tasks
            WHERE activity_id=#{activityId}
            ORDER BY id
            """)
    List<Map<String, Object>> listSourceTasks(@Param("activityId") String activityId);

    @Select("""
            SELECT depends_on_id
            FROM task_dependencies
            WHERE task_id=#{taskId}
            ORDER BY depends_on_id
            """)
    List<Map<String, Object>> listDependencies(@Param("taskId") Object taskId);

    @Insert("""
            INSERT INTO business_templates(id,organization_id,creator_id,name,description,blueprint_json,created_at,updated_at) VALUES(#{id},#{organizationId},#{creatorId},#{name},#{description},#{blueprint},#{now},#{updatedAt})
            """)
    void insertTemplate(@Param("id") String id, @Param("organizationId") String organizationId, @Param("creatorId") String creatorId, @Param("name") String name, @Param("description") String description, @Param("blueprint") String blueprint, @Param("now") String now, @Param("updatedAt") String updatedAt);

    @Select("""
            SELECT *
            FROM business_templates
            WHERE id=#{id} AND organization_id=#{organizationId} FOR UPDATE
            """)
    Map<String, Object> lockTemplateRow(@Param("id") String id, @Param("organizationId") String organizationId);

    default Optional<Map<String, Object>> lockTemplate(String id, String organizationId) {
        return Optional.ofNullable(lockTemplateRow(id, organizationId));
    }

    @Update("""
            UPDATE business_templates
            SET name=#{name},description=#{description},blueprint_json=#{blueprint},version=version+1,updated_at=#{updatedAt}
            WHERE id=#{id}
            """)
    void updateTemplate(@Param("name") String name, @Param("description") String description, @Param("blueprint") String blueprint, @Param("updatedAt") String updatedAt, @Param("id") String id);

    @Delete("""
            DELETE
            FROM business_templates
            WHERE id=#{id}
            """)
    void deleteTemplate(@Param("id") String id);

    @Select("""
            SELECT *
            FROM template_applications
            WHERE account_id=#{accountId} AND request_id=#{requestId}
            """)
    Map<String, Object> findApplicationRow(@Param("accountId") String accountId, @Param("requestId") String requestId);

    default Optional<Map<String, Object>> findApplication(String accountId, String requestId) {
        return Optional.ofNullable(findApplicationRow(accountId, requestId));
    }

    @Insert("""
            INSERT INTO template_applications(account_id,request_id,template_id,class_id,payload_hash,draft_id,created_at) VALUES(#{accountId},#{requestId},#{templateId},#{classId},#{hash},#{draftId},#{now})
            ON CONFLICT DO NOTHING
            """)
    int insertApplication(@Param("accountId") String accountId, @Param("requestId") String requestId, @Param("templateId") String templateId, @Param("classId") String classId, @Param("hash") String hash, @Param("draftId") String draftId, @Param("now") String now);

    @Insert("""
            INSERT INTO agent_drafts(id,organization_id,class_id,creator_id,raw_input,intent_type,complexity,draft_json,status,created_at,updated_at) VALUES(#{id},#{organizationId},#{classId},#{creatorId},#{rawInput},#{intent},#{complexity},#{draft},'draft',#{now},#{updatedAt})
            """)
    void insertDraft(@Param("id") String id, @Param("organizationId") String organizationId, @Param("classId") String classId, @Param("creatorId") String creatorId, @Param("rawInput") String rawInput, @Param("intent") String intent, @Param("complexity") String complexity, @Param("draft") String draft, @Param("now") String now, @Param("updatedAt") String updatedAt);
}
