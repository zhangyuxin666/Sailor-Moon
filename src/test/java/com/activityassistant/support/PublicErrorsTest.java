package com.activityassistant.support;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PublicErrorsTest {
    @Test
    void credentialsResponsesAndSqlNeverReachPublicMessages() {
        for (String raw : new String[] {
                "Authorization: Bearer secret-value",
                "502 from https://ai.invalid?api_key=secret-value body=secret-value",
                "SELECT password_hash FROM accounts jdbc:postgresql://user:secret-value@db",
                "AI 服务 unavailable header X-Internal-Token: secret-value",
                "负责人错误 token=secret-value"
        }) {
            assertThat(PublicErrors.message(raw)).doesNotContain("secret-value", "jdbc:", "SELECT", "https://", "Bearer");
        }
    }

    @Test
    void emptyErrorsStayEmptyAndFailuresRemainActionable() {
        assertThat(PublicErrors.message(null)).isNull();
        assertThat(PublicErrors.message("")).isNull();
        assertThat(PublicErrors.message("QQ Gateway 502")).contains("检查群连接");
        assertThat(PublicErrors.message("活动时间格式无效")).contains("补充");
        assertThat(PublicErrors.failure(new IllegalStateException("unexpected"))).contains("重试");
        assertThat(PublicErrors.failure(new RuntimeException(""))).contains("重试");
        assertThat(PublicErrors.failure(new RuntimeException("   "))).contains("重试");
        assertThat(PublicErrors.failure(new RuntimeException())).contains("重试");
        assertThat(PublicErrors.failure(null)).contains("重试");
    }
}
