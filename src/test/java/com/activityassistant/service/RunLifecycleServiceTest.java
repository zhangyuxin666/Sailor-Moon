package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLifecycleServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resumeRequiresSupplementalInputWhenRunIsWaiting() {
        FakeDb db = new FakeDb(run("waiting_input", null));
        RunLifecycleService service = new RunLifecycleService(db, mapper);

        assertThatThrownBy(() -> service.resume("run1"))
                .isInstanceOf(ApiException.class).hasMessageContaining("补充信息");
        assertThat(db.updates).isEmpty();
    }

    @Test
    void resumeUsesMergedPlanClearsOldResolutionAndStartsFreshRetryBudget() throws Exception {
        FakeDb db = new FakeDb(run("waiting_input", null));
        RunLifecycleService service = new RunLifecycleService(db, mapper);
        service.saveInput("run1", mapper.readTree("{\"event_time\":\"2026-10-25T19:00:00\"}"));

        Map<String, Object> result = service.resume("run1");

        assertThat(result).containsEntry("status", "pending").containsEntry("idempotent", false);
        assertThat(mapper.readTree(String.valueOf(db.activity.get("plan_json"))).path("event_time").asText())
                .isEqualTo("2026-10-25T19:00:00");
        assertThat(db.run.get("resolution_json")).isNull();
        assertThat(db.run.get("plan_json")).isEqualTo(db.activity.get("plan_json"));
        assertThat(db.updates).anyMatch(sql -> sql.contains("attempts=0"));
        assertThat(db.queries).anyMatch(sql -> sql.contains("agent_runs") && sql.endsWith("FOR UPDATE"));
        assertThat(db.queries).anyMatch(sql -> sql.contains("background_jobs") && sql.endsWith("FOR UPDATE"));
        int writes = db.updates.size();
        assertThat(service.resume("run1")).containsEntry("idempotent", true);
        assertThat(db.updates).hasSize(writes);
    }

    @Test
    void partialAnswersCannotResumeUntilAllMissingInformationIsSatisfied() throws Exception {
        FakeDb db = new FakeDb(run("waiting_input", null));
        db.run.put("resolution_json", "{\"data\":{\"missing_information\":[\"请确认活动时间\",\"Which room?\"]}}");
        RunLifecycleService service = new RunLifecycleService(db, mapper);

        Map<String, Object> first = service.saveInput("run1",
                mapper.readTree("{\"event_time\":\"2026-10-25T19:00:00\"}"));
        assertThat(first.get("missing_information")).isEqualTo(List.of("Which room?"));
        assertThatThrownBy(() -> service.resume("run1")).isInstanceOf(ApiException.class).hasMessageContaining("Which room?");

        Map<String, Object> second = service.saveInput("run1",
                mapper.readTree("{\"answers\":{\"Which room?\":\"Room 204\"}}"));
        assertThat(second.get("missing_information")).isEqualTo(List.of());
        JsonNode plan = (JsonNode) second.get("plan");
        assertThat(plan.path("description").asText()).contains("Room 204");
        service.resume("run1");
        String description = mapper.readTree(String.valueOf(db.activity.get("plan_json"))).path("description").asText();
        assertThat(description.indexOf("Room 204")).isEqualTo(description.lastIndexOf("Room 204"));
    }

    @Test
    void supplementalAnswersConfirmedBeforePublicationSurviveLaterInput() throws Exception {
        FakeDb db = new FakeDb(run("waiting_input", null));
        com.fasterxml.jackson.databind.node.ObjectNode plan =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(String.valueOf(db.activity.get("plan_json")));
        plan.put("description_before_input", "Reviewed description");
        plan.put("description", "Reviewed description\nWhich room?：Room 204");
        plan.set("supplemental_information", mapper.readTree("{\"Which room?\":\"Room 204\"}"));
        db.activity.put("plan_json", plan.toString());
        RunLifecycleService service = new RunLifecycleService(db, mapper);

        Map<String, Object> result = service.saveInput("run1",
                mapper.readTree("{\"event_time\":\"2026-10-25T19:00:00\"}"));

        assertThat(((JsonNode) result.get("plan")).path("description").asText()).contains("Room 204");
        assertThat(((JsonNode) result.get("input")).path("answers").path("Which room?").asText())
                .isEqualTo("Room 204");
    }

    @Test
    void answerTextCannotReplaceTheRequiredStructuredEventTime() throws Exception {
        FakeDb db = new FakeDb(run("waiting_input", null));
        db.run.put("resolution_json", "{\"data\":{\"missing_information\":[\"请确认活动时间\"]}}");
        RunLifecycleService service = new RunLifecycleService(db, mapper);

        Map<String, Object> result = service.saveInput("run1",
                mapper.readTree("{\"answers\":{\"请确认活动时间\":\"tomorrow\"}}"));

        assertThat(result.get("missing_information")).isEqualTo(List.of("请确认活动时间"));
        assertThatThrownBy(() -> service.resume("run1")).isInstanceOf(ApiException.class);
    }

    @Test
    void inputIsRejectedOutsideWaitingStateAndInvalidInputDoesNotWrite() throws Exception {
        FakeDb db = new FakeDb(run("failed", null));
        RunLifecycleService service = new RunLifecycleService(db, mapper);
        assertThatThrownBy(() -> service.saveInput("run1", mapper.readTree("{\"title\":\"Changed\"}")))
                .isInstanceOf(ApiException.class);
        db.run.put("status", "waiting_input");
        assertThatThrownBy(() -> service.saveInput("run1", mapper.readTree("{\"event_time\":\"tomorrow\"}")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.saveInput("run1", mapper.readTree("{\"unexpected\":true}")))
                .isInstanceOf(ApiException.class);
        assertThat(db.updates).isEmpty();
    }

    @Test
    void failedRecoveryReusesSavedPlanAndRejectsJobStillRunning() {
        FakeDb db = new FakeDb(run("failed", "{\"title\":\"Old input\"}"));
        String snapshot = String.valueOf(db.activity.get("plan_json"));
        RunLifecycleService service = new RunLifecycleService(db, mapper);
        db.jobStatus = "running";
        assertThatThrownBy(() -> service.resume("run1")).isInstanceOf(ApiException.class).hasMessageContaining("收尾");
        assertThat(db.updates).isEmpty();

        db.jobStatus = "failed";
        assertThat(service.resume("run1")).containsEntry("status", "pending");
        assertThat(db.activity.get("plan_json")).isEqualTo(snapshot);
        assertThat(db.updates).noneMatch(sql -> sql.startsWith("UPDATE activities SET title="));
    }

    @Test
    void pauseForInputFinishesTheJobWithoutRunningOrchestration() {
        FakeDb db = new FakeDb(run("running", null));
        RunLifecycleService service = new RunLifecycleService(db, mapper);
        service.pauseForInput("run1", "a1", List.of("请确认活动时间"));

        assertThat(db.updates).anyMatch(sql -> sql.startsWith("UPDATE agent_runs SET status='waiting_input'"));
        assertThat(db.updates).anyMatch(sql -> sql.startsWith("UPDATE activities SET status='waiting_input'"));
        assertThat(db.updates).anyMatch(sql -> sql.startsWith("UPDATE background_jobs SET status='succeeded'"));
    }

    @Test
    void historicalWaitingRunWithoutPlanCannotSilentlyLoseTaskAndFormDecisions() throws Exception {
        FakeDb db = new FakeDb(run("waiting_input", null));
        db.activity.remove("plan_json");
        RunLifecycleService service = new RunLifecycleService(db, mapper);
        assertThatThrownBy(() -> service.saveInput("run1", mapper.readTree("{\"event_time\":\"2026-10-25T19:00:00\"}")))
                .isInstanceOf(ApiException.class).hasMessageContaining("重新生成草案");
        assertThat(db.updates).isEmpty();
    }

    private Map<String, Object> run(String status, String inputJson) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", "run1");
        run.put("kind", "activity");
        run.put("ref_id", "a1");
        run.put("status", status);
        run.put("input_json", inputJson);
        return run;
    }

    private static final class FakeDb extends Db {
        private final Map<String, Object> run;
        private final Map<String, Object> activity = new LinkedHashMap<>();
        private final List<String> updates = new ArrayList<>();
        private final List<String> queries = new ArrayList<>();
        private String jobStatus = "succeeded";

        private FakeDb(Map<String, Object> run) {
            super(new JdbcTemplate());
            this.run = run;
            activity.put("title", "Reviewed event");
            activity.put("raw_input", "Original request");
            activity.put("plan_json", """
                    {"title":"Reviewed event","description":"Reviewed description","event_time":"",
                     "requires_event_time":true,"form_fields":[],"tasks":[],"materials":["water"],
                     "remind_minutes_before":60,"plan_source":"confirmed_draft"}
                    """);
        }

        @Override
        public Optional<Map<String, Object>> one(String sql, Object... args) {
            queries.add(sql);
            if (sql.startsWith("SELECT * FROM agent_runs")) return Optional.of(run);
            if(sql.startsWith("SELECT status FROM activities"))return Optional.of(Map.of("status","waiting_input"));
            if (sql.startsWith("SELECT title, raw_input, plan_json FROM activities")) return Optional.of(activity);
            if (sql.startsWith("SELECT status FROM background_jobs")) return Optional.of(Map.of("status", jobStatus));
            throw new AssertionError("Unexpected query: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql + " " + Arrays.asList(args));
            if (sql.startsWith("UPDATE activities SET title=")) {
                activity.put("title", args[0]);
                activity.put("plan_json", args[1]);
            }
            if (sql.startsWith("UPDATE agent_runs SET input_json=")) {
                run.put("input_json", args[0]);
                run.put("plan_json", args[1]);
                run.put("resolution_json", args[2]);
            }
            if (sql.startsWith("UPDATE agent_runs SET plan_json=")) run.put("plan_json", args[0]);
            if (sql.startsWith("UPDATE agent_runs SET status='pending'")) {
                run.put("status", "pending");
                run.put("resolution_json", null);
            }
            if (sql.startsWith("UPDATE background_jobs SET status='queued'")) jobStatus = "queued";
            return 1;
        }
    }
}
