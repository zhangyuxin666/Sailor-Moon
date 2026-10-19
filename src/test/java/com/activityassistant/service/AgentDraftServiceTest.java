package com.activityassistant.service;

import com.activityassistant.integration.AiClient;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDraftServiceTest {
    @Test
    void publishPausesActivityWhenRequiredInformationIsStillMissing() {
        Db db = mock(Db.class);
        ClassroomService classroom = mock(ClassroomService.class);
        ActivityService activities = mock(ActivityService.class);
        AiClient ai = mock(AiClient.class);
        AuditService audits = mock(AuditService.class);
        QqService qq = mock(QqService.class);
        RunLifecycleService lifecycle = mock(RunLifecycleService.class);
        AgentDraftService service = new AgentDraftService(
                db, classroom, activities, ai, audits, qq, new ObjectMapper(), lifecycle);
        AccountPrincipal account = new AccountPrincipal(
                "u1", "manager", "Manager", "", "manager", "active", "2026-10-18", 0);
        Map<String, Object> draftRow = new LinkedHashMap<>();
        draftRow.put("id", "d1");
        draftRow.put("organization_id", "org1");
        draftRow.put("class_id", "c1");
        draftRow.put("creator_id", "u1");
        draftRow.put("raw_input", "组织秋游");
        draftRow.put("intent_type", "activity");
        draftRow.put("complexity", "complex");
        draftRow.put("status", "draft");
        draftRow.put("class_name", "软件工程");
        draftRow.put("draft_json", """
                {
                  "intent_type":"activity",
                  "complexity":"complex",
                  "title":"秋游",
                  "description":"组织秋游",
                  "event_time":"",
                  "missing_information":["请确认活动时间"],
                  "workflow":{"create_calendar":true,"schedule_reminder":true,"publish_message":true}
                }
                """);
        when(db.one(anyString(), any(Object[].class))).thenReturn(Optional.of(draftRow));
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);
        when(activities.queue(eq(account), anyString(), eq(true), eq("c1"), any()))
                .thenReturn(Map.of("activity_id", "a1", "run_id", "run1", "status", "queued"));

        Map<String, Object> result = service.publish(account, "d1");

        verify(lifecycle).pauseForInput("run1", "a1", List.of("请确认活动时间"));
        assertThat(result)
                .containsEntry("status", "published")
                .containsEntry("run_id", "run1")
                .containsEntry("run_status", "waiting_input");
        assertThat(result.get("missing_information")).isEqualTo(List.of("请确认活动时间"));
    }
}
