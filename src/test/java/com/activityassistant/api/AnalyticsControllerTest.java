package com.activityassistant.api;

import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsControllerTest {
    @Test
    void forwardsAuthenticatedManagerAndFiltersToService() {
        AnalyticsService service = mock(AnalyticsService.class);
        AnalyticsController controller = new AnalyticsController(service);
        AccountPrincipal manager = new AccountPrincipal("a1", "manager", "管理员", null,
                "manager", "active", "2026-01-01", 0);
        var authentication = new UsernamePasswordAuthenticationToken(manager, "token");
        when(service.overview(manager, 30, "class-1")).thenReturn(Map.of("days", 30));

        Map<String, Object> result = controller.overview(authentication, 30, "class-1");

        assertThat(result).containsEntry("days", 30);
        verify(service).overview(manager, 30, "class-1");
    }
}
