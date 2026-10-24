package com.activityassistant.api;

import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.ActivityService;
import com.activityassistant.service.BackgroundJobService;
import com.activityassistant.service.ClassroomService;
import com.activityassistant.service.QqService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityControllerRunLifecycleTest {
    private final ActivityService activities = mock(ActivityService.class);
    private final ActivityController controller = new ActivityController(
            activities, mock(BackgroundJobService.class), mock(ClassroomService.class), mock(QqService.class));
    private final AccountPrincipal account = new AccountPrincipal(
            "u1", "manager", "Manager", "", "manager", "active", "2026-10-18", 0);
    private final UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(account, null, List.of());

    @Test
    void inputEndpointDelegatesToActivityService() {
        JsonNode input = new ObjectMapper().createObjectNode().put("event_time", "2026-10-20T19:00:00");
        when(activities.saveRunInput("run1", "manager", input)).thenReturn(Map.of("run_id", "run1"));

        Map<String, Object> result = controller.input(
                authentication, "run1", new ApiModels.RunInputRequest(input));

        assertThat(result).containsEntry("run_id", "run1");
        verify(activities).saveRunInput("run1", "manager", input);
    }

    @Test
    void resumeEndpointDelegatesToActivityService() {
        when(activities.resumeRun("run1", "manager")).thenReturn(Map.of("run_id", "run1", "status", "pending"));

        Map<String, Object> result = controller.resume(authentication, "run1");

        assertThat(result).containsEntry("status", "pending");
        verify(activities).resumeRun("run1", "manager");
    }
}
