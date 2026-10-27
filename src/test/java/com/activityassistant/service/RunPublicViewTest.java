package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunPublicViewTest {
    @SuppressWarnings("unchecked")
    @Test void publicRunExposesParsedStateWithoutRawInternalJsonOrException() {
        Db db = mock(Db.class);
        AgentRunService runs = new AgentRunService(db, new ObjectMapper().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE));
        BackgroundJobService jobs = new BackgroundJobService(db, runs);
        when(db.one("SELECT * FROM background_jobs WHERE id=?", "run")).thenReturn(Optional.of(Map.of("id", "run", "kind", "activity", "ref_id", "activity", "status", "failed", "attempts", 3, "error", "Authorization: Bearer fake-secret")));
        when(db.one("SELECT * FROM agent_runs WHERE kind=? AND ref_id=?", "activity", "activity")).thenReturn(Optional.of(Map.of("id", "run", "status", "waiting_input", "error", "postgresql://fake-password", "input_json", "{\"answers\":{\"场地\":\"教室\"}}", "plan_json", "{\"title\":\"已确认的标题\"}", "context_json", "invalid-json", "resolution_json", "{\"status\":\"waiting_input\",\"data\":{\"missing_information\":[\"场地\"]},\"citations\":[],\"next_actions\":[\"input\"],\"error\":\"fake-secret\"}")));
        Map<String, Object> result = jobs.get("run");
        assertThat(result).containsEntry("attempts", 3);
        Map<String, Object> agent = (Map<String, Object>)result.get("agent_run");
        assertThat(agent).containsEntry("status", "waiting_input");
        assertThat(agent.keySet()).noneMatch(key -> key.endsWith("_json"));
        assertThat((Map<String, Object>)result.get("plan")).containsEntry("title", "已确认的标题");
        assertThat(result.toString()).doesNotContain("fake-secret", "fake-password", "postgresql");
        assertThat(result.get("result")).isNotNull();
    }
    @Test void completionPersistsStructuredSuccessResultForLaterReads() {
        Db db = mock(Db.class);
        AgentRunService runs = new AgentRunService(db, new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE));
        runs.complete("activity", Map.of("activity_id", "activity", "form_id", "form"));
        org.mockito.ArgumentCaptor<String> resolution = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                resolution.capture(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("activity"));
        Map<String, Object> view = runs.protocolOf(Map.of("resolution_json", resolution.getValue()));
        com.activityassistant.orchestration.AgentResult result = (com.activityassistant.orchestration.AgentResult)view.get("result");
        assertThat(result.status()).isEqualTo(com.activityassistant.orchestration.RunStatus.SUCCEEDED);
        assertThat(result.data()).containsEntry("form_id", "form");
        assertThat(result.error()).isNull();
    }

}
