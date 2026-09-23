package com.activityassistant.api;

import com.activityassistant.persistence.Db;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PageControllerTest {
    private final PageController controller = new PageController(mock(Db.class));

    @Test
    void anonymousRootRedirectsWithoutCaching() {
        var response = controller.root(null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasPath("/login");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void loginPageIsServedAsHtml() {
        var response = controller.spaPage();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).hasToString("text/html");
        assertThat(response.getBody()).isNotNull();
    }
}
