package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface KnowledgeMapper {
    @Delete("""
            DELETE
            FROM rag_document_chunks
            WHERE document_id=CAST(#{documentId} AS uuid)
            """)
    int deleteChunks(@Param("documentId") String documentId);

    @Insert("""
            INSERT INTO rag_document_chunks(id,document_id,document_version,chunk_index,content,embedding,embedding_profile) VALUES(CAST(#{id} AS uuid),CAST(#{documentId} AS uuid),#{version},#{index},#{content},CAST(#{embedding} AS vector),#{profile})
            """)
    int insertChunk(@Param("id") String id, @Param("documentId") String documentId, @Param("version") int version, @Param("index") int index, @Param("content") String content, @Param("embedding") String embedding, @Param("profile") String profile);

    @Select("""
            SELECT id::text,source,version,embedding_profile
            FROM rag_documents
            WHERE organization_id=#{organizationId} AND (content_hash=#{hash} OR(content_hash IS NULL AND btrim(source)=#{source} AND btrim(content)=#{content}))
            ORDER BY created_at,id LIMIT 1
            """)
    Map<String, Object> findDuplicateRow(@Param("organizationId") String organizationId, @Param("hash") String hash, @Param("source") String source, @Param("content") String content);

    default Optional<Map<String, Object>> findDuplicate(String organizationId, String hash, String source, String content) {
        return Optional.ofNullable(findDuplicateRow(organizationId, hash, source, content));
    }

    @Insert("""
            INSERT INTO rag_documents(id,organization_id,source,content,metadata_json,embedding,content_hash,created_by,embedding_profile) VALUES(CAST(#{id} AS uuid),#{organizationId},#{source},#{content},CAST(#{metadata} AS jsonb),CAST(#{embedding} AS vector),#{hash},#{accountId},#{profile})
            ON CONFLICT DO NOTHING
            """)
    int insertDocument(@Param("id") String id, @Param("organizationId") String organizationId, @Param("source") String source, @Param("content") String content, @Param("metadata") String metadata, @Param("embedding") String embedding, @Param("hash") String hash, @Param("accountId") String accountId, @Param("profile") String profile);

    @Select("""
            SELECT id::text,source,version,embedding_profile
            FROM rag_documents
            WHERE organization_id=#{organizationId} AND content_hash=#{hash}
            """)
    Map<String, Object> findByHash(@Param("organizationId") String organizationId, @Param("hash") String hash);

    @Select("""
            SELECT id::text,organization_id,source,version,embedding_profile,created_at,updated_at
            FROM rag_documents
            WHERE organization_id=#{organizationId} OR organization_id IS NULL
            ORDER BY updated_at DESC,id LIMIT 200
            """)
    List<Map<String, Object>> listVisible(@Param("organizationId") String organizationId);

    @Select("""
            SELECT id::text,organization_id,source,content,metadata_json::text,version,embedding_profile,created_at,updated_at
            FROM rag_documents
            WHERE id=CAST(#{id} AS uuid) AND (organization_id=#{organizationId} OR organization_id IS NULL)
            """)
    Map<String, Object> findVisibleRow(@Param("id") String id, @Param("organizationId") String organizationId);

    default Optional<Map<String, Object>> findVisible(String id, String organizationId) {
        return Optional.ofNullable(findVisibleRow(id, organizationId));
    }

    @Update("""
            UPDATE rag_documents
            SET source=#{source},content=#{content},metadata_json=CAST(#{metadata} AS jsonb),embedding=CAST(#{embedding} AS vector),content_hash=#{hash},embedding_profile=#{profile},version=version+1,updated_at=clock_timestamp()
            WHERE id=CAST(#{id} AS uuid) AND organization_id=#{organizationId} AND version=#{version}
            """)
    int updateDocument(@Param("source") String source, @Param("content") String content, @Param("metadata") String metadata, @Param("embedding") String embedding, @Param("hash") String hash, @Param("profile") String profile, @Param("id") String id, @Param("organizationId") String organizationId, @Param("version") int version);

    @Update("""
            UPDATE rag_documents
            SET embedding=CAST(#{embedding} AS vector),embedding_profile=#{profile},updated_at=clock_timestamp()
            WHERE id=CAST(#{id} AS uuid) AND organization_id=#{organizationId} AND version=#{version}
            """)
    int updateEmbedding(@Param("embedding") String embedding, @Param("profile") String profile, @Param("id") String id, @Param("organizationId") String organizationId, @Param("version") int version);

    @Delete("""
            DELETE
            FROM rag_documents
            WHERE id=CAST(#{id} AS uuid) AND organization_id=#{organizationId} AND version=#{version}
            """)
    int deleteDocument(@Param("id") String id, @Param("organizationId") String organizationId, @Param("version") int version);
}
