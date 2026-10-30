package com.activityassistant.api;

import com.activityassistant.persistence.ReplicaReads;
import com.activityassistant.support.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class DatabaseRoutingController {
    private final ReplicaReads reads;
    public DatabaseRoutingController(ReplicaReads reads) { this.reads = reads; }

    @GetMapping("/cluster/database")
    public Map<String,Object> database(Authentication authentication) {
        if (!AuthController.required(authentication).isManager())
            throw ApiException.forbidden("只有管理者可以查看数据库分流状态");
        return reads.status();
    }
}
