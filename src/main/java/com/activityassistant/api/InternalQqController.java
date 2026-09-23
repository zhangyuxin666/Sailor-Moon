package com.activityassistant.api;

import com.activityassistant.config.AppProperties;
import com.activityassistant.service.QqService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class InternalQqController {
    private final QqService qq;
    private final AppProperties properties;

    public InternalQqController(QqService qq, AppProperties properties) {
        this.qq = qq;
        this.properties = properties;
    }

    @PostMapping("/internal/qq/events")
    ResponseEntity<Map<String, Object>> inbound(
            @RequestHeader(value = "X-Gateway-Token", defaultValue = "") String token,
            @Valid @RequestBody ApiModels.QqInboundRequest body) {
        if (!same(token, properties.qqGatewayToken()))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("detail", "invalid gateway token"));
        String reply = qq.processInbound(body.groupOpenid(), body.senderOpenid(), body.senderName(),
                body.content(), body.messageId(), body.timestamp());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply);
        return ResponseEntity.ok(result);
    }

    private boolean same(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
