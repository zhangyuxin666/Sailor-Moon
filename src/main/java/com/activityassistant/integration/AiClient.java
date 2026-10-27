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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AiClient {
    private final RestClient client;
    private final String token;

    public AiClient(RestClient.Builder builder, AppProperties properties) {
        this.client = builder.baseUrl(properties.aiBaseUrl()).build();
        this.token = properties.aiInternalToken();
    }

    public JsonNode plan(String rawInput, List<Map<String, Object>> availableAssignees, String organizationId) {
        return post("/internal/ai/plan", Map.of(
                "raw_input", rawInput, "available_assignees", availableAssignees,
                "organization_id", organizationId == null ? "" : organizationId));
    }

    public JsonNode analyzeWorkRequest(String rawInput, String classContext, String organizationId) {
        return post("/internal/ai/analyze-work-request", Map.of(
                "raw_input", rawInput, "class_context", classContext,
                "organization_id", organizationId == null ? "" : organizationId));
    }

    public JsonNode recapAggregates(Map<String,Object> aggregates){
        return post("/internal/ai/recap",Map.of("activity_title","活动统计","stats",aggregates,"task_summary","","organization_id",""));
    }
    public JsonNode embeddingResult(String text){return post("/internal/ai/embed",Map.of("text",text));}
    public String reply(String message, String activityContext, String organizationId) {
        JsonNode response = post("/internal/ai/reply", Map.of(
                "message", message, "activity_context", activityContext,
                "organization_id", organizationId == null ? "" : organizationId));
        return response.path("reply").asText();
    }

    public List<Double> embedding(String text) {
        JsonNode response = embeddingResult(text);
        if (!response.path("embedding").isArray())
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务未返回有效向量");
        List<Double> values = new ArrayList<>();
        response.path("embedding").forEach(value -> values.add(value.asDouble()));
        return values;
    }

    public JsonNode ragSearch(String organizationId, String query, int topK) {
        return post("/internal/rag/search", Map.of(
                "organization_id", organizationId,
                "query", query,
                "top_k", topK));
    }

    private JsonNode post(String path, Map<String, ?> body) {
        try {
            return RetryExecutor.execute(() -> {
                JsonNode response = client.post().uri(path).header("X-Internal-Token", token)
                        .body(body).retrieve().body(JsonNode.class);
                if (response == null) throw new RestClientException("AI 服务返回空响应");
                return response;
            }, this::isTransient, 3, Duration.ofMillis(200));
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务暂时不可用，请稍后重试");
        }
    }

    private boolean isTransient(Exception exception) {
        if (exception instanceof ResourceAccessException) return true;
        if (exception instanceof RestClientResponseException response)
            return response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429;
        return false;
    }
}
