package com.activityassistant.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一上下文与统一结果的线上格式。
 *
 * 生产环境的 ObjectMapper 在 application.yml 里配置了 SNAKE_CASE，
 * 这里用同样的策略构造 mapper，锁定跨服务字段名。
 */
class AgentProtocolTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void contextUsesSnakeCaseKeysOnTheWire() throws Exception {
        AgentContext context = new AgentContext("u1", "org1", "class1", "act1", "run1", "req1");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(context));

        assertThat(json.path("user_id").asText()).isEqualTo("u1");
        assertThat(json.path("organization_id").asText()).isEqualTo("org1");
        assertThat(json.path("class_id").asText()).isEqualTo("class1");
        assertThat(json.path("activity_id").asText()).isEqualTo("act1");
        assertThat(json.path("run_id").asText()).isEqualTo("run1");
        assertThat(json.path("request_id").asText()).isEqualTo("req1");
    }

    @Test
    void resultUsesWireStatusAndKeepsDataKeysUntouched() throws Exception {
        AgentResult result = new AgentResult(
                RunStatus.WAITING_INPUT,
                Map.of("missing_information", List.of("活动时间")),
                List.of("kb:1"),
                List.of("ask_user"),
                null);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(result));

        assertThat(json.path("status").asText()).isEqualTo("waiting_input");
        assertThat(json.path("next_actions").get(0).asText()).isEqualTo("ask_user");
        assertThat(json.path("citations").get(0).asText()).isEqualTo("kb:1");
        // data 是业务数据，键名由各智能体决定，不能被命名策略改写。
        assertThat(json.path("data").path("missing_information").get(0).asText()).isEqualTo("活动时间");
    }

    @Test
    void resultNormalizesMissingCollections() {
        AgentResult result = new AgentResult(RunStatus.SUCCEEDED, null, null, null, null);

        assertThat(result.data()).isEmpty();
        assertThat(result.citations()).isEmpty();
        assertThat(result.nextActions()).isEmpty();
        assertThat(result.error()).isNull();
    }

    @Test
    void resultRoundTripsThroughJson() throws Exception {
        AgentResult original = new AgentResult(RunStatus.FAILED, Map.of("step", "create_form"),
                List.of(), List.of("retry"), "boom");

        AgentResult parsed = mapper.readValue(mapper.writeValueAsString(original), AgentResult.class);

        assertThat(parsed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(parsed.error()).isEqualTo("boom");
        assertThat(parsed.data()).containsEntry("step", "create_form");
        assertThat(parsed.nextActions()).containsExactly("retry");
    }
}
