package com.activityassistant.integration;

import com.activityassistant.config.AppProperties;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.RetryExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class QqGatewayClient {
    private final RestClient client;
    private final String token;

    public QqGatewayClient(RestClient.Builder builder, AppProperties properties) {
        this.client = builder.baseUrl(properties.qqGatewayBaseUrl()).build();
        this.token = properties.qqGatewayToken();
    }

    public Map<String, Object> sendGroup(String groupOpenid, String content, String replyTo, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("group_openid", groupOpenid);
        body.put("content", content);
        if (replyTo != null) body.put("reply_to", replyTo);
        try {
            JsonNode response = RetryExecutor.execute(() -> client.post().uri("/internal/send-group")
                            .header("X-Gateway-Token", token)
                            .header("Idempotency-Key", idempotencyKey)
                            .body(body).retrieve().body(JsonNode.class),
                    this::isTransient, 3, Duration.ofMillis(250));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "sent");
            if (response != null) result.put("id", response.path("id").asText(null));
            return result;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "QQ Gateway 发送失败：" + exception.getMessage());
        }
    }

    public boolean healthy() {
        try {
            JsonNode response = client.get().uri("/health").retrieve().body(JsonNode.class);
            return response != null && "ok".equals(response.path("status").asText());
        } catch (RestClientException exception) {
            return false;
        }
    }

    private boolean isTransient(Exception exception) {
        if (exception instanceof ResourceAccessException) return true;
        if (exception instanceof RestClientResponseException response)
            return response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429;
        return false;
    }
}
