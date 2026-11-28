package com.activityassistant.service;

import com.activityassistant.integration.AiClient;
import com.activityassistant.orchestration.ActivityOrchestrator;
import com.activityassistant.orchestration.ExecutionJournal;
import com.activityassistant.persistence.mapper.ActivityMapper;
import com.activityassistant.persistence.ReplicaReads;
import com.activityassistant.persistence.mapper.ActivityGateMapper;
import com.activityassistant.persistence.mapper.TaskManagementMapper;
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
    private final ActivityGateMapper gateMapper = mock(ActivityGateMapper.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final ActivityGate gate=new ActivityGate(gateMapper,new ObjectMapper(),mock(com.activityassistant.config.AppProperties.class),mock(AuditService.class),mock(BackgroundJobService.class));
    private final com.activityassistant.persistence.mapper.RegistrationMapper registrationMapper = mock(com.activityassistant.persistence.mapper.RegistrationMapper.class);
    private final RegistrationService registrationService = new RegistrationService(gate, registrationMapper);
    private final TaskManagementMapper taskMapper = mock(TaskManagementMapper.class);
    private final TaskManagementService taskService = new TaskManagementService(taskMapper, gate);
    @org.junit.jupiter.api.BeforeEach void activeActivity(){when(gateMapper.lockActivity("activity")).thenReturn(Optional.of(Map.of("status","ready","user_id","owner")));}
    private final ReplicaReads replicaReads = mock(ReplicaReads.class);
    private final ActivityMapper activityMapper = mock(ActivityMapper.class);
    private final ActivityService service = new ActivityService(replicaReads, activityMapper, mock(BackgroundJobService.class), runs,
            mock(AiClient.class), mock(QqService.class), new ObjectMapper(), mock(ActivityOrchestrator.class),
            mock(ExecutionJournal.class), mock(RunLifecycleService.class),registrationService,taskService,gate);
    private final AccountPrincipal student = new AccountPrincipal("student-id", "student", "同名", "", "participant", "active", "now", 0);

    @Test void participantCanCompleteAndUndoOwnTaskUsingAccountRelationship() {
        when(taskMapper.findTask("task")).thenReturn(Optional.of(Map.of("id", "task", "activity_id", "activity", "assignee", "旧名字")));
        when(taskMapper.findAssignment("task", student.id())).thenReturn(Optional.of(Map.of("exists", 1)));

        service.updateTask("task", "done", student);
        service.updateTask("task", "pending", student);
        verify(taskMapper).updateStatus("done", "task");
        verify(taskMapper).updateStatus("pending", "task");
    }
    @Test void matchingDisplayNameDoesNotGrantAccessToAnotherTask() {
        when(taskMapper.findTask("task")).thenReturn(Optional.of(Map.of("id", "task", "activity_id","activity","assignee", "同名")));
        assertThatThrownBy(() -> service.updateTask("task", "done", student)).isInstanceOf(ApiException.class).hasMessageContaining("未分配给你");
        verify(taskMapper, never()).updateStatus(anyString(), anyString());
    }
    @Test void invalidStatusIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.updateTask("task", "anything", student)).isInstanceOf(ApiException.class);
        verifyNoInteractions(taskMapper);
    }
    @Test void privateStatsAreRejectedBeforeReadingAnswers() {
        when(registrationMapper.findActivityOwner("activity")).thenReturn(Optional.of(Map.of("user_id", "owner")));
        assertThatThrownBy(() -> service.formStats("activity", student.username())).isInstanceOf(ApiException.class);
        verify(registrationMapper, never()).listRegistrations(any());
        verify(registrationMapper, never()).findActivityForm(anyString());
    }
    @SuppressWarnings("unchecked")
    @Test void ownerReceivesQuestionLabelsAndAllHistoricalAnswerShapes() {
        when(registrationMapper.findActivityOwner("activity")).thenReturn(Optional.of(Map.of("user_id", "owner")));
        when(registrationMapper.findActivityForm("activity")).thenReturn(Optional.of(Map.of("id", "form", "title", "报名问卷", "fields_json", "[{\"name\":\"choice\",\"label\":\"交通方式\"}]")));
        when(registrationMapper.listRegistrations("form")).thenReturn(List.of(Map.of("status","active","name", "测试", "contact", "test-contact", "created_at", "now", "extra_json", "{\"choice\":\"公交\",\"old_field\":[\"甲\",\"乙\"],\"meta\":{\"value\":true}}")));
        when(registrationMapper.findPublicForm("form")).thenReturn(Optional.of(Map.of("id","form","fields_json","[]","plan_json","{}","registration_status","open","activity_status","ready")));
        when(registrationMapper.countActive("form")).thenReturn(1L);
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
        when(replicaReads.readMapper(eq(ActivityMapper.class), eq(activityMapper), any())).thenAnswer(call -> {
            java.util.function.Function<ActivityMapper, List<Map<String, Object>>> query = call.getArgument(2);
            return query.apply(activityMapper);
        });
        when(activityMapper.findOwner("activity")).thenReturn(Optional.of(Map.of("user_id", "owner")));
        when(activityMapper.findActivity("activity")).thenReturn(Map.of("id", "activity", "status", "failed"));
        when(runs.byRef("activity", "activity")).thenReturn(Map.of("id", "run"));
        when(activityMapper.listSteps("activity")).thenReturn(List.of(Map.of("step", "plan", "status", "failed", "attempts", 3, "detail", "Authorization: Bearer fake-secret", "error", "postgresql://user:fake-password@localhost/db", "result_json", "fake-secret")));
        Map<String, Object> result = service.detail("activity", "owner");
        assertThat(result).containsEntry("run_id", "run");
        Map<String, Object> step = ((List<Map<String, Object>>)result.get("steps")).get(0);
        assertThat(step).containsEntry("status", "failed").containsEntry("attempts", 3).doesNotContainKey("result_json");
        assertThat(step.toString()).doesNotContain("fake-secret", "fake-password", "postgresql", "Authorization");
    }
}
