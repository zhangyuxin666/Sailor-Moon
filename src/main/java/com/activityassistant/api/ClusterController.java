package com.activityassistant.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ClusterController {
    private final String instanceName;

    public ClusterController(@Value("${app.instance-name:local}") String instanceName) {
        this.instanceName = instanceName;
    }

    @GetMapping("/cluster/node")
    Map<String, Object> node(HttpServletRequest request) {
        return Map.of(
                "instance", instanceName,
                "port", request.getLocalPort()
        );
    }
}
