package com.activityassistant.service;

import com.activityassistant.persistence.mapper.BackgroundJobMapper;
import com.activityassistant.persistence.mapper.AgentRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunPublicViewTest {
    @SuppressWarnings("unchecked")
    @Test void publicRunExposesParsedStateWithoutRawInternalJsonOrException() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunService runs = new AgentRunService(runMapper, new ObjectMapper().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE));
        BackgroundJobMapper jobMapper = mock(BackgroundJobMapper.class);
        BackgroundJobService jobs = new BackgroundJobService(runs, jobMapper);
        when(jobMapper.findById("run")).thenReturn(Optional.of(Map.of("id", "run", "kind", "activity", "ref_id", "activity", "status", "failed", "attempts", 3, "error", "Authorization: Bearer fake-secret")));
        when(runMapper.findByRef("activity", "activity")).thenReturn(Optional.of(Map.of("id", "run", "status", "waiting_input", "error", "postgresql://fake-password", "input_json", "{\"answers\":{\"场地\":\"教室\"}}", "plan_json", "{\"title\":\"已确认的标题\"}", "context_json", "invalid-json", "resolution_json", "{\"status\":\"waiting_input\",\"data\":{\"missing_information\":[\"场地\"]},\"citations\":[],\"next_actions\":[\"input\"],\"error\":\"fake-secret\"}")));
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
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunService runs = new AgentRunService(runMapper, new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE));
        runs.complete("activity", Map.of("activity_id", "activity", "form_id", "form"));
        org.mockito.ArgumentCaptor<String> resolution = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(runMapper).complete(org.mockito.ArgumentMatchers.anyString(),
                resolution.capture(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("activity"));
        Map<String, Object> view = runs.protocolOf(Map.of("resolution_json", resolution.getValue()));
        com.activityassistant.orchestration.AgentResult result = (com.activityassistant.orchestration.AgentResult)view.get("result");
        assertThat(result.status()).isEqualTo(com.activityassistant.orchestration.RunStatus.SUCCEEDED);
        assertThat(result.data()).containsEntry("form_id", "form");
        assertThat(result.error()).isNull();
    }

}
