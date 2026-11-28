package com.activityassistant.service;

import com.activityassistant.persistence.mapper.AuditMapper;
import com.activityassistant.support.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuditService {
    private final AuditMapper mapper;
    private final ObjectMapper objectMapper;

    public AuditService(AuditMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
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
        mapper.insert(new AuditMapper.Entry(Ids.shortId(), organizationId, accountId, action,
                resourceType, resourceId, json, LocalDateTime.now().toString()));
    }
}
