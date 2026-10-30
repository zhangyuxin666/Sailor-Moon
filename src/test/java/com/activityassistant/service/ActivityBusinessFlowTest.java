package com.activityassistant.service;

import com.activityassistant.integration.AiClient;
import com.activityassistant.orchestration.ActivityOrchestrator;
import com.activityassistant.orchestration.ExecutionJournal;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ActivityBusinessFlowTest {
    private final Db db = mock(Db.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final ActivityGate gate=new ActivityGate(db,new ObjectMapper(),mock(com.activityassistant.config.AppProperties.class),mock(AuditService.class),mock(BackgroundJobService.class));
    private final RegistrationService registrationService=new RegistrationService(gate,db);
    private final TaskManagementService taskService=new TaskManagementService(db,gate);
    @org.junit.jupiter.api.BeforeEach void activeActivity(){when(db.one("SELECT * FROM activities WHERE id=? FOR UPDATE","activity")).thenReturn(Optional.of(Map.of("status","ready","user_id","owner")));}
    private final ActivityService service = new ActivityService(db, mock(BackgroundJobService.class), runs,
            mock(AiClient.class), mock(QqService.class), new ObjectMapper(), mock(ActivityOrchestrator.class),
            mock(ExecutionJournal.class), mock(RunLifecycleService.class),registrationService,taskService,gate);
    private final AccountPrincipal student = new AccountPrincipal("student-id", "student", "同名", "", "participant", "active", "now", 0);

    @Test void participantCanCompleteAndUndoOwnTaskUsingAccountRelationship() {
        when(db.one("SELECT * FROM tasks WHERE id=?", "task")).thenReturn(Optional.of(Map.of("id", "task", "activity_id", "activity", "assignee", "旧名字")));
        when(db.one("SELECT 1 FROM activity_task_assignees WHERE task_id=? AND account_id=?", "task", student.id())).thenReturn(Optional.of(Map.of("exists", 1)));
        when(db.required("SELECT * FROM tasks WHERE id=?", "task")).thenReturn(Map.of("id", "task"));
        service.updateTask("task", "done", student);
        service.updateTask("task", "pending", student);
        verify(db).update("UPDATE tasks SET status=?,version=version+1 WHERE id=?", "done", "task");
        verify(db).update("UPDATE tasks SET status=?,version=version+1 WHERE id=?", "pending", "task");
    }
    @Test void matchingDisplayNameDoesNotGrantAccessToAnotherTask() {
        when(db.one("SELECT * FROM tasks WHERE id=?", "task")).thenReturn(Optional.of(Map.of("id", "task", "activity_id","activity","assignee", "同名")));
        assertThatThrownBy(() -> service.updateTask("task", "done", student)).isInstanceOf(ApiException.class).hasMessageContaining("未分配给你");
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void invalidStatusIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.updateTask("task", "anything", student)).isInstanceOf(ApiException.class);
        verify(db, never()).update(anyString(),any(Object[].class));
    }
    @Test void privateStatsAreRejectedBeforeReadingAnswers() {
        when(db.one("SELECT user_id FROM activities WHERE id=?", "activity")).thenReturn(Optional.of(Map.of("user_id", "owner")));
        assertThatThrownBy(() -> service.formStats("activity", student.username())).isInstanceOf(ApiException.class);
        verify(db, never()).list(anyString(), any(Object[].class));
        verify(db, never()).one("SELECT * FROM forms WHERE activity_id=? ORDER BY created_at LIMIT 1", "activity");
    }
    @SuppressWarnings("unchecked")
    @Test void ownerReceivesQuestionLabelsAndAllHistoricalAnswerShapes() {
        when(db.one("SELECT user_id FROM activities WHERE id=?", "activity")).thenReturn(Optional.of(Map.of("user_id", "owner")));
        when(db.one("SELECT * FROM forms WHERE activity_id=? ORDER BY created_at LIMIT 1", "activity")).thenReturn(Optional.of(Map.of("id", "form", "title", "报名问卷", "fields_json", "[{\"name\":\"choice\",\"label\":\"交通方式\"}]")));
        when(db.list("SELECT id,name,contact,extra_json,created_at,status,cancelled_at FROM registrations WHERE form_id=? ORDER BY id", "form")).thenReturn(List.of(Map.of("status","active","name", "测试", "contact", "test-contact", "created_at", "now", "extra_json", "{\"choice\":\"公交\",\"old_field\":[\"甲\",\"乙\"],\"meta\":{\"value\":true}}")));
        when(db.one(startsWith("SELECT f.*"),eq("form"))).thenReturn(Optional.of(Map.of("id","form","fields_json","[]","plan_json","{}","registration_status","open","activity_status","ready")));
        when(db.required("SELECT COUNT(*) AS count FROM registrations WHERE form_id=? AND status='active'","form")).thenReturn(Map.of("count",1));
        Map<String, Object> result = service.formStats("activity", "owner");
        assertThat(((JsonNode)result.get("fields")).get(0).path("label").asText()).isEqualTo("交通方式");
        Map<String, Object> answer = ((List<Map<String, Object>>) result.get("registrations")).get(0);
        JsonNode extra = (JsonNode)answer.get("extra");
        assertThat(extra.path("old_field").size()).isEqualTo(2);
        assertThat(extra.path("meta").path("value").asBoolean()).isTrue();
        assertThat(answer).doesNotContainKey("extra_json");
    }
    @SuppressWarnings("unchecked")
    @Test void detailHasRunLinkAndSafeFailureWithRealStepState() {
        when(db.one("SELECT user_id FROM activities WHERE id=?", "activity")).thenReturn(Optional.of(Map.of("user_id", "owner")));
        when(db.required("SELECT * FROM activities WHERE id=?", "activity")).thenReturn(Map.of("id", "activity", "status", "failed"));
        when(runs.byRef("activity", "activity")).thenReturn(Map.of("id", "run"));
        when(db.readList("SELECT * FROM steps WHERE activity_id=? ORDER BY id", "activity")).thenReturn(List.of(Map.of("step", "plan", "status", "failed", "attempts", 3, "detail", "Authorization: Bearer fake-secret", "error", "postgresql://user:fake-password@localhost/db", "result_json", "fake-secret")));
        Map<String, Object> result = service.detail("activity", "owner");
        assertThat(result).containsEntry("run_id", "run");
        Map<String, Object> step = ((List<Map<String, Object>>)result.get("steps")).get(0);
        assertThat(step).containsEntry("status", "failed").containsEntry("attempts", 3).doesNotContainKey("result_json");
        assertThat(step.toString()).doesNotContain("fake-secret", "fake-password", "postgresql", "Authorization");
    }
}
