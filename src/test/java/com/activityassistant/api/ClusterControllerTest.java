package com.activityassistant.api;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClusterControllerTest {
    @Test
    void reportsConfiguredInstanceAndLocalPort() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getLocalPort()).thenReturn(8080);

        var response = new ClusterController("app-1").node(request);

        assertThat(response)
                .containsEntry("instance", "app-1")
                .containsEntry("port", 8080);
    }
}
