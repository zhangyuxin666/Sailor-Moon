package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
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
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("补充信息");

        assertThat(db.updates).isEmpty();
    }

    @Test
    void resumeRequeuesActivityAndBackgroundJobAfterInput() {
        FakeDb db = new FakeDb(run("waiting_input", "{\"event_time\":\"2026-10-20T19:00:00\"}"));
        RunLifecycleService service = new RunLifecycleService(db, mapper);

        Map<String, Object> result = service.resume("run1");

        assertThat(result).containsEntry("run_id", "run1").containsEntry("status", "pending");
        assertThat(db.updates).anyMatch(sql -> sql.startsWith("UPDATE agent_runs SET status='pending'"));
        assertThat(db.updates).anyMatch(sql -> sql.startsWith("UPDATE activities SET status='queued'"));
        assertThat(db.updates).anyMatch(sql -> sql.startsWith("UPDATE background_jobs SET status='queued'"));
    }

    @Test
    void resumeIsIdempotentWhenRunIsAlreadyPending() {
        FakeDb db = new FakeDb(run("pending", null));
        RunLifecycleService service = new RunLifecycleService(db, mapper);

        Map<String, Object> result = service.resume("run1");

        assertThat(result).containsEntry("status", "pending").containsEntry("idempotent", true);
        assertThat(db.updates).isEmpty();
    }

    @Test
    void inputIsSavedOnlyWhileRunIsWaiting() {
        FakeDb db = new FakeDb(run("waiting_input", null));
        RunLifecycleService service = new RunLifecycleService(db, mapper);
        JsonNode input = mapper.createObjectNode().put("event_time", "2026-10-20T19:00:00");

        Map<String, Object> result = service.saveInput("run1", input);

        assertThat(result).containsEntry("run_id", "run1").containsEntry("status", "waiting_input");
        assertThat(db.updates).anyMatch(sql -> sql.startsWith("UPDATE agent_runs SET input_json=?"));
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
        private final List<String> updates = new ArrayList<>();

        private FakeDb(Map<String, Object> run) {
            super(new JdbcTemplate());
            this.run = run;
        }

        @Override
        public Optional<Map<String, Object>> one(String sql, Object... args) {
            if (sql.startsWith("SELECT * FROM agent_runs WHERE id=?")) return Optional.of(run);
            return Optional.empty();
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql + " " + List.of(args));
            return 1;
        }
    }
}
