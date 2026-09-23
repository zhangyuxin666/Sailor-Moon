package com.activityassistant.security;

import java.util.LinkedHashMap;
import java.util.Map;

public record AccountPrincipal(
        String id,
        String username,
        String displayName,
        String studentNo,
        String role,
        String status,
        String createdAt,
        int forcePasswordChange
) {
    public Map<String, Object> toPublicMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("username", username);
        result.put("display_name", displayName);
        result.put("student_no", studentNo);
        result.put("role", role);
        result.put("status", status);
        result.put("created_at", createdAt);
        result.put("force_password_change", forcePasswordChange);
        return result;
    }

    public boolean isManager() {
        return "manager".equals(role);
    }
}
