package com.activityassistant.service;

import com.activityassistant.integration.AiClient;
import com.activityassistant.persistence.mapper.KnowledgeMapper;
import com.activityassistant.security.AccountPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeChunkServiceTest {
    private final KnowledgeMapper db = mock(KnowledgeMapper.class);
    private final AuthService auth = mock(AuthService.class);
    private final AiClient ai = mock(AiClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final KnowledgeService service = new KnowledgeService(db, auth, ai, mapper);
    private final AccountPrincipal manager = new AccountPrincipal(
            "manager-1", "manager", "管理员", "", "manager", "active", "now", 0);

    @Test
    void addingDocumentPersistsOrderedChunksAndTheirEmbeddings() {
        when(auth.organizationOf(manager.id())).thenReturn("org-1");
        when(db.findDuplicate(eq("org-1"), anyString(), eq("规范"), eq("第一段。第二段。")))
                .thenReturn(Optional.empty());
        when(db.insertDocument(anyString(), eq("org-1"), eq("规范"), eq("第一段。第二段。"),
                anyString(), anyString(), anyString(), eq(manager.id()), eq("local_hash_v1:1536")))
                .thenReturn(1);
        when(db.findByHash(eq("org-1"), anyString()))
                .thenReturn(Map.of("id", "doc-1", "source", "规范", "version", 1,
                        "embedding_profile", "local_hash_v1:1536"));
        when(ai.embeddingResult("第一段。第二段。")).thenReturn(embeddingResult());
        when(ai.chunkEmbed("第一段。第二段。", 500, 80)).thenReturn(chunkResult("第一段。", "第二段。"));

        Map<String, Object> result = service.add(manager, "规范", "第一段。第二段。", Map.of());

        assertThat(result).containsEntry("status", "indexed");
        verify(ai).chunkEmbed("第一段。第二段。", 500, 80);
        verify(db).deleteChunks(anyString());
        verify(db).insertChunk(anyString(), anyString(), eq(1), eq(0), eq("第一段。"), anyString(), eq("local_hash_v1:1536"));
        verify(db).insertChunk(anyString(), anyString(), eq(1), eq(1), eq("第二段。"), anyString(), eq("local_hash_v1:1536"));
    }

    @Test
    void reindexingExistingDocumentReplacesChunksWithoutChangingVersion() {
        String documentId = "9e3dce75-d786-4c0d-92a4-a4d335d76b45";
        when(auth.organizationOf(manager.id())).thenReturn("org-1");
        when(db.findVisible(documentId, "org-1"))
                .thenReturn(Optional.of(Map.of(
                        "id", documentId, "organization_id", "org-1", "source", "旧规范",
                        "content", "需要重新建立分片。", "metadata_json", "{}", "version", 4,
                        "embedding_profile", "legacy_unknown")));
        when(db.updateEmbedding(anyString(), eq("local_hash_v1:1536"), eq(documentId), eq("org-1"), eq(4)))
                .thenReturn(1);
        when(ai.embeddingResult("需要重新建立分片。")).thenReturn(embeddingResult());
        when(ai.chunkEmbed("需要重新建立分片。", 500, 80))
                .thenReturn(chunkResult("需要重新建立分片。"));

        Map<String, Object> result = service.reindex(manager, documentId);

        assertThat(result).containsEntry("status", "indexed").containsEntry("chunk_count", 1);
        verify(db).updateEmbedding(anyString(), eq("local_hash_v1:1536"), eq(documentId), eq("org-1"), eq(4));
        verify(db).deleteChunks(documentId);
        verify(db).insertChunk(anyString(), eq(documentId), eq(4), eq(0), eq("需要重新建立分片。"),
                anyString(), eq("local_hash_v1:1536"));
    }

    private ObjectNode embeddingResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("embedding_profile", "local_hash_v1:1536");
        result.set("embedding", vector());
        return result;
    }

    private ObjectNode chunkResult(String... contents) {
        ObjectNode result = mapper.createObjectNode();
        result.put("embedding_profile", "local_hash_v1:1536");
        ArrayNode chunks = result.putArray("chunks");
        for (int index = 0; index < contents.length; index++) {
            ObjectNode chunk = chunks.addObject();
            chunk.put("chunk_index", index);
            chunk.put("content", contents[index]);
            chunk.set("embedding", vector());
        }
        return result;
    }

    private JsonNode vector() {
        ArrayNode values = mapper.createArrayNode();
        for (int index = 0; index < 1536; index++) values.add(index == 0 ? 1.0 : 0.0);
        return values;
    }
}
