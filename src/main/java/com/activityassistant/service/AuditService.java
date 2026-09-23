package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.support.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuditService {
    private final Db db;
    private final ObjectMapper objectMapper;

    public AuditService(Db db, ObjectMapper objectMapper) {
        this.db = db;
        this.objectMapper = objectMapper;
    }

    public void write(String action, String accountId, String organizationId,
                      String resourceType, String resourceId, Map<String, ?> detail) {
        String json = null;
        try {
            if (detail != null) json = objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException exception) {
            json = "{}";
        }
        db.update("INSERT INTO audit_logs (id, organization_id, account_id, action, resource_type, resource_id, detail_json, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Ids.shortId(), organizationId, accountId, action, resourceType, resourceId, json, LocalDateTime.now().toString());
    }
}
