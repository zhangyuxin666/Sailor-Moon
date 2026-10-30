package com.activityassistant.support;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    @Test void unexpectedErrorsAlwaysReturnSafeNonemptyMessages() {
        for (String raw : new String[]{"", "   ", "Authorization: Bearer fake-secret postgresql://fake-password"}) {
            var response = handler.unexpected(new RuntimeException(raw));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().get("detail")).isNotBlank().doesNotContain("fake-secret", "fake-password", "Bearer", "postgresql");
        }
    }
    @Test void businessErrorsKeepActionableClientDetailsAndHideUpstreamFailures() {
        var failure = handler.api(new ApiException(HttpStatus.BAD_GATEWAY, "https://ai.invalid?token=fake-secret"));
        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(failure.getBody().get("detail")).doesNotContain("https:", "fake-secret");
        assertThat(handler.api(ApiException.badRequest("仍需补充信息：集合地点")).getBody())
                .containsEntry("detail", "仍需补充信息：集合地点");
    }
}
