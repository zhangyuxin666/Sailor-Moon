package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditMapper {
    record Entry(String id, String organizationId, String accountId, String action,
                 String resourceType, String resourceId, String detailJson, String createdAt) {}

    @Insert("""
            INSERT INTO audit_logs (id, organization_id, account_id, action,
                                    resource_type, resource_id, detail_json, created_at)
            VALUES (#{id}, #{organizationId}, #{accountId}, #{action},
                    #{resourceType}, #{resourceId}, #{detailJson}, #{createdAt})
            """)
    void insert(Entry entry);
}
