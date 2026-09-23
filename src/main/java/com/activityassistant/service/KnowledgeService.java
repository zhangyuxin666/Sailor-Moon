package com.activityassistant.service;

import com.activityassistant.integration.AiClient;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeService {
    private final Db db;
    private final AuthService auth;
    private final AiClient ai;
    private final ObjectMapper mapper;

    public KnowledgeService(Db db, AuthService auth, AiClient ai, ObjectMapper mapper) {
        this.db = db;
        this.auth = auth;
        this.ai = ai;
        this.mapper = mapper;
    }

    public Map<String, Object> add(AccountPrincipal account, String source, String content,
                                   Map<String, Object> metadata) {
        requireManager(account);
        String organizationId = organization(account);
        String contentHash = sha256(source.trim() + "\n" + content.trim());
        Map<String, Object> existing = db.one("SELECT id::text, source, created_at FROM rag_documents " +
                "WHERE organization_id=? AND content_hash=?", organizationId, contentHash).orElse(null);
        if (existing != null) {
            Map<String, Object> result = new LinkedHashMap<>(existing);
            result.put("status", "indexed");
            result.put("idempotent", true);
            return result;
        }

        List<Double> embedding = ai.embedding(content);
        if (embedding.size() != 1536)
            throw ApiException.badRequest("嵌入向量维度必须为 1536，实际为 " + embedding.size());
        String id = UUID.randomUUID().toString();
        try {
            db.update("INSERT INTO rag_documents (id, organization_id, source, content, metadata_json, embedding, content_hash, created_by) " +
                            "VALUES (CAST(? AS uuid), ?, ?, ?, CAST(? AS jsonb), CAST(? AS vector), ?, ?)",
                    id, organizationId, source.trim(), content.trim(), json(metadata == null ? Map.of() : metadata),
                    vector(embedding), contentHash, account.id());
        } catch (DataIntegrityViolationException race) {
            Map<String, Object> concurrent = db.required("SELECT id::text, source, created_at FROM rag_documents " +
                    "WHERE organization_id=? AND content_hash=?", organizationId, contentHash);
            Map<String, Object> result = new LinkedHashMap<>(concurrent);
            result.put("status", "indexed");
            result.put("idempotent", true);
            return result;
        }
        return Map.of("id", id, "source", source.trim(), "status", "indexed", "idempotent", false);
    }

    public JsonNode search(AccountPrincipal account, String query, int topK) {
        String organizationId = organization(account);
        return ai.ragSearch(organizationId, query, Math.min(Math.max(topK, 1), 20));
    }

    private String organization(AccountPrincipal account) {
        String organizationId = auth.organizationOf(account.id());
        if (organizationId == null) throw ApiException.forbidden("账号尚未加入组织");
        return organizationId;
    }

    private void requireManager(AccountPrincipal account) {
        if (!account.isManager()) throw ApiException.forbidden("只有管理者可以维护知识库");
    }

    private String vector(List<Double> values) {
        return "[" + values.stream().map(value -> String.format(java.util.Locale.ROOT, "%.9f", value))
                .reduce((left, right) -> left + "," + right).orElse("") + "]";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("metadata 无法序列化", exception);
        }
    }
}
