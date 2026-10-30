package com.activityassistant.integration;

import com.activityassistant.config.AppProperties;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
            JsonNode response = client.post().uri("/internal/send-group")
                    .header("X-Gateway-Token",token).header("Idempotency-Key",idempotencyKey)
                    .body(body).retrieve().body(JsonNode.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "sent");
            if (response != null) result.put("id", response.path("id").asText(null));
            return result;
        } catch(RestClientResponseException error) {
            boolean rejected=error.getStatusCode().value()==401||error.getStatusCode().value()==422;
            try{rejected |= "failed".equals(new com.fasterxml.jackson.databind.ObjectMapper().readTree(error.getResponseBodyAsString()).path("outcome").asText());}catch(Exception ignored){}
            if(rejected)throw new NotSent(error.getStatusCode().value()==401||error.getStatusCode().value()==422?HttpStatus.BAD_REQUEST:HttpStatus.BAD_GATEWAY);
            throw new ApiException(HttpStatus.CONFLICT,"投递结果未知，已停止自动重发，请核对投递记录");
        } catch(Exception error) {
            throw new ApiException(HttpStatus.CONFLICT,"投递结果未知，已停止自动重发，请核对投递记录");
        }
    }
    public static final class NotSent extends ApiException {
        public NotSent(){this(HttpStatus.BAD_GATEWAY);}
        public NotSent(HttpStatus status){super(status,"QQ Gateway 确认未发送，请检查配置和内容后重试");}
    }
    public Map<String,Object> delivery(String key) {
        try {
            JsonNode response=client.get().uri("/internal/deliveries/{key}",key).header("X-Gateway-Token",token).retrieve().body(JsonNode.class);
            return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(response,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        }catch(Exception error){return Map.of("status","unknown");}
    }

    public boolean healthy() {
        try {
            JsonNode response = client.get().uri("/health").retrieve().body(JsonNode.class);
            return response != null && "ok".equals(response.path("status").asText());
        } catch (RestClientException exception) {
            return false;
        }
    }

}
