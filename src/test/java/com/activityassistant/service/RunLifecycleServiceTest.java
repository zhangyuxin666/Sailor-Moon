package com.activityassistant.service;

import com.activityassistant.persistence.mapper.RunLifecycleMapper;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
        assertThat(db.updates).anyMatch("resetJob"::equals);
        assertThat(db.queries).anyMatch("lockRun"::equals);
        assertThat(db.queries).anyMatch("lockJob"::equals);
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
        assertThat(db.updates).noneMatch("saveActivityPlan"::equals);
    }

    @Test
    void pauseForInputFinishesTheJobWithoutRunningOrchestration() {
        FakeDb db = new FakeDb(run("running", null));
        RunLifecycleService service = new RunLifecycleService(db, mapper);
        service.pauseForInput("run1", "a1", List.of("请确认活动时间"));

        assertThat(db.updates).anyMatch("waitForInput"::equals);
        assertThat(db.updates).anyMatch("waitActivity"::equals);
        assertThat(db.updates).anyMatch("finishJob"::equals);
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

    private static final class FakeDb implements RunLifecycleMapper {
        @Override public Map<String, Object> lockJobRow(String runId) {
            return lockJob(runId).orElse(null);
        }

        @Override public Map<String, Object> findRunRow(String runId) {
            return findRun(runId).orElse(null);
        }

        @Override public Map<String, Object> lockActivityStatusRow(String activityId) {
            return lockActivityStatus(activityId).orElse(null);
        }

        @Override public Map<String, Object> lockRunRow(String runId) {
            return lockRun(runId).orElse(null);
        }

        @Override public Map<String, Object> lockActivityPlanRow(String activityId) {
            return lockActivityPlan(activityId).orElse(null);
        }

        private final Map<String, Object> run;
        private final Map<String, Object> activity = new LinkedHashMap<>();
        private final List<String> updates = new ArrayList<>();
        private final List<String> queries = new ArrayList<>();
        private String jobStatus = "succeeded";

        private FakeDb(Map<String, Object> run) {
            super();
            this.run = run;
            activity.put("title", "Reviewed event");
            activity.put("raw_input", "Original request");
            activity.put("plan_json", """
                    {"title":"Reviewed event","description":"Reviewed description","event_time":"",
                     "requires_event_time":true,"form_fields":[],"tasks":[],"materials":["water"],
                     "remind_minutes_before":60,"plan_source":"confirmed_draft"}
                    """);
        }

        @Override public void saveActivityPlan(String title, String plan, String activityId) {
            updates.add("saveActivityPlan");
            activity.put("title", title); activity.put("plan_json", plan);
        }

        @Override public void saveInput(String input, String plan, String resolution, String now, String runId) {
            updates.add("saveInput");
            run.put("input_json", input); run.put("plan_json", plan); run.put("resolution_json", resolution);
        }

        @Override public Optional<Map<String, Object>> lockJob(String runId) {
            queries.add("lockJob");
            return Optional.of(Map.of("status", jobStatus));
        }

        @Override public void saveRunPlan(String plan, String now, String runId) {
            updates.add("saveRunPlan");
            run.put("plan_json", plan);
        }

        @Override public void resetRun(String now, String runId) {
            updates.add("resetRun");
            run.put("status", "pending"); run.put("resolution_json", null);
        }

        @Override public void queueActivity(String activityId) {
            updates.add("queueActivity");
        }

        @Override public int resetJob(String now, String runId) {
            updates.add("resetJob");
            jobStatus = "queued"; return 1;
        }

        @Override public void waitForInput(String resolution, String now, String runId) {
            updates.add("waitForInput");
        }

        @Override public void waitActivity(String activityId) {
            updates.add("waitActivity");
        }

        @Override public void finishJob(String finishedAt, String now, String runId) {
            updates.add("finishJob");
        }

        @Override public Optional<Map<String, Object>> findRun(String runId) {
            queries.add("findRun");
            return Optional.of(run);
        }

        @Override public Optional<Map<String, Object>> lockActivityStatus(String activityId) {
            queries.add("lockActivityStatus");
            return Optional.of(Map.of("status", "waiting_input"));
        }

        @Override public Optional<Map<String, Object>> lockRun(String runId) {
            queries.add("lockRun");
            return Optional.of(run);
        }

        @Override public Optional<Map<String, Object>> lockActivityPlan(String activityId) {
            queries.add("lockActivityPlan");
            return Optional.of(activity);
        }
    }
}
