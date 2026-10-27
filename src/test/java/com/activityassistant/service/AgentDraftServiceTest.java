package com.activityassistant.service;

import com.activityassistant.integration.AiClient;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentDraftServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AccountPrincipal account = new AccountPrincipal(
            "u1", "manager", "Manager", "", "manager", "active", "2026-10-18", 0);

    @Test
    void publishPausesActivityWhenRequiredInformationIsStillMissing() throws Exception {
        Harness h = harness("""
                {"intent_type":"activity","title":"Trip","description":"Organize trip","event_time":"",
                 "missing_information":["请确认活动时间"],"workflow":{"create_calendar":true}}
                """);

        Map<String, Object> result = h.service.publish(account, "d1");

        verify(h.lifecycle).pauseForInput("run1", "a1", List.of("请确认活动时间"));
        assertThat(result).containsEntry("status", "published").containsEntry("run_status", "waiting_input");
        assertThat(h.db.publishedPlan.path("title").asText()).isEqualTo("Trip");
        assertThat(h.db.runPlan).isEqualTo(h.db.publishedPlan);
        verifyNoInteractions(h.ai);
    }

    @Test
    void publishUsesReviewedFieldsWorkflowAndAccountIdsWithoutRegenerating() throws Exception {
        Harness h = harness("""
                {"intent_type":"activity","title":"Reviewed","description":"Edited","event_time":"2026-10-25T19:00:00",
                 "missing_information":["请确认活动时间"],"workflow":{"create_form":true,"assign_tasks":true,"publish_message":false},
                 "form_fields":[{"name":"food","label":"Food choice","type":"select","options":["Rice","Noodles"]}],
                 "execution_plan":{"title":"Old AI title","event_time":"2026-01-01T00:00:00",
                  "tasks":[{"title":"Prepare","account_id":"u1","assignee":"Manager"}],"materials":["water"],"remind_minutes_before":30}}
                """);

        Map<String, Object> result = h.service.publish(account, "d1");

        assertThat(result).containsEntry("run_status", "pending").doesNotContainKey("missing_information");
        JsonNode plan = h.db.publishedPlan;
        assertThat(plan.path("title").asText()).isEqualTo("Reviewed");
        assertThat(plan.path("event_time").asText()).isEqualTo("2026-10-25T19:00:00");
        assertThat(plan.path("form_fields").get(2).path("name").asText()).isEqualTo("food");
        assertThat(plan.path("tasks").get(0).path("account_id").asText()).isEqualTo("u1");
        assertThat(plan.path("materials").get(0).asText()).isEqualTo("water");
        assertThat(plan.path("tool_intents").toString()).doesNotContain("publish_message");
        verifyNoInteractions(h.ai, h.lifecycle);
    }

    @Test
    void surveyKeepsReviewedQuestionsAndSupplementalAnswers() throws Exception {
        Harness h = harness("""
                {"intent_type":"survey","title":"Travel survey","description":"Survey",
                 "event_time":"2026-10-25T19:00:00","workflow":{"create_form":true},
                 "missing_information":["Which room?","请确认活动日期"],
                 "input":{"answers":{"Which room?":"Room 204"}},
                 "form_fields":[{"name":"destination","label":"Destination","type":"select","options":["Park","Museum"]}]}
                """);

        Map<String, Object> result = h.service.publish(account, "d1");

        assertThat(result).containsEntry("run_status", "pending");
        assertThat(h.db.publishedPlan.path("form_fields").get(2).path("options").toString())
                .isEqualTo("[\"Park\",\"Museum\"]");
        assertThat(h.db.publishedPlan.path("description").asText()).contains("Room 204");
        verifyNoInteractions(h.ai, h.lifecycle);
    }

    @Test
    void activityCreationGeneratesTasksAndMaterialsBeforeReview() throws Exception {
        Harness h = harness("{}");
        when(h.ai.analyzeWorkRequest(anyString(), anyString(), eq("org1"))).thenReturn(mapper.readTree("""
                {"intent_type":"activity","complexity":"complex","title":"Reviewed title","description":"Reviewed description",
                 "event_time":"2026-10-25T19:00:00","workflow":{"create_form":true,"assign_tasks":true}}
                """));
        when(h.ai.plan(eq("Organize trip"), any(), eq("org1"))).thenReturn(mapper.readTree("""
                {"title":"Generated","form_fields":[{"name":"preference","label":"Preference","type":"text"}],
                 "tasks":[{"title":"Prepare","account_id":"u1","assignee":"Manager"}],"materials":["water"]}
                """));

        Map<String, Object> result = h.service.create(account, "c1", "Organize trip");

        JsonNode draft = (JsonNode) result.get("draft");
        assertThat(draft.path("execution_plan").path("tasks").get(0).path("account_id").asText()).isEqualTo("u1");
        assertThat(draft.path("execution_plan").path("materials").get(0).asText()).isEqualTo("water");
        assertThat(draft.path("execution_plan").path("title").asText()).isEqualTo("Reviewed title");
        assertThat(draft.path("form_fields")).hasSize(3);
        verify(h.ai).plan(eq("Organize trip"), any(), eq("org1"));
    }

    @Test
    void legacyTaskDraftMustBeRegeneratedBeforeSavingOrPublishing() throws Exception {
        String legacy = """
                {"intent_type":"activity","title":"Legacy","event_time":"2026-10-25T19:00:00",
                 "workflow":{"assign_tasks":true}}
                """;
        Harness h = harness(legacy);
        assertThatThrownBy(() -> h.service.update(account, "d1", mapper.readTree(legacy)))
                .isInstanceOf(ApiException.class).hasMessageContaining("重新生成");
        assertThatThrownBy(() -> h.service.publish(account, "d1"))
                .isInstanceOf(ApiException.class).hasMessageContaining("重新生成");
        verifyNoInteractions(h.activities, h.ai);
    }

    @Test
    void taskAccountMustStillBelongToTheClassAtPublication() throws Exception {
        Harness h = harness("""
                {"intent_type":"activity","title":"Trip","event_time":"2026-10-25T19:00:00","workflow":{"assign_tasks":true},
                 "execution_plan":{"tasks":[{"title":"Prepare","account_id":"other-class-user"}]}}
                """);
        h.service.publish(account,"d1");
        assertThat(h.db.publishedPlan.path("tasks").get(0).path("account_id").asText()).isEmpty();
        assertThat(h.db.publishedPlan.path("tasks").get(0).path("unassigned_reason").asText()).contains("不属于当前班级");
        verifyNoInteractions(h.ai);
    }

    private Harness harness(String draftJson) {
        FakeDb db = new FakeDb(draftJson, mapper);
        ClassroomService classroom = mock(ClassroomService.class);
        ActivityService activities = mock(ActivityService.class);
        AiClient ai = mock(AiClient.class);
        AuditService audits = mock(AuditService.class);
        QqService qq = mock(QqService.class);
        RunLifecycleService lifecycle = mock(RunLifecycleService.class);
        when(classroom.requireClassManager("c1", account))
                .thenReturn(Map.of("organization_id", "org1", "name", "Class"));
        when(activities.queue(eq(account), anyString(), anyBoolean(), eq("c1"), any()))
                .thenReturn(Map.of("activity_id", "a1", "run_id", "run1", "status", "queued"));
        return new Harness(new AgentDraftService(db, classroom, activities, ai, audits, qq, mapper, lifecycle),
                db, ai, activities, lifecycle);
    }

    private record Harness(AgentDraftService service, FakeDb db, AiClient ai,
                           ActivityService activities, RunLifecycleService lifecycle) {}

    private static final class FakeDb extends Db {
        private final Map<String, Object> row = new LinkedHashMap<>();
        private final ObjectMapper mapper;
        private JsonNode publishedPlan;
        private JsonNode runPlan;

        FakeDb(String draftJson, ObjectMapper mapper) {
            super(new JdbcTemplate());
            this.mapper = mapper;
            row.putAll(Map.of("id", "d1", "organization_id", "org1", "class_id", "c1", "creator_id", "u1",
                    "raw_input", "Organize trip", "status", "draft", "draft_json", draftJson));
        }

        @Override
        public Optional<Map<String, Object>> one(String sql, Object... args) {
            if (sql.startsWith("SELECT id FROM agent_drafts")) return Optional.of(Map.of("id", row.get("id")));
            if (sql.startsWith("SELECT d.*")) return Optional.of(row);
            throw new AssertionError("Unexpected query: " + sql);
        }

        @Override
        public List<Map<String, Object>> list(String sql, Object... args) {
            if (sql.startsWith("SELECT a.id AS account_id"))
                return List.of(Map.of("account_id", "u1", "display_name", "Manager"));
            throw new AssertionError("Unexpected query: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            try {
                if (sql.startsWith("INSERT INTO agent_drafts")) {
                    row.put("id", args[0]);
                    row.put("draft_json", args[7]);
                } else if (sql.startsWith("UPDATE agent_drafts SET intent_type=")) {
                    row.put("draft_json", args[2]);
                } else if (sql.startsWith("UPDATE activities SET title=")) {
                    publishedPlan = mapper.readTree(String.valueOf(args[1]));
                } else if (sql.startsWith("UPDATE agent_runs SET plan_json=")) {
                    runPlan = mapper.readTree(String.valueOf(args[0]));
                }
                return 1;
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
