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
public interface FileAssetMapper {
    @Select("""
            SELECT id, original_filename, size_bytes, sha256
            FROM file_assets
            WHERE account_id=#{accountId} AND sha256=#{sha256} AND size_bytes=#{size}
            """)
    Map<String, Object> findDuplicateRow(@Param("accountId") String accountId, @Param("sha256") String sha256, @Param("size") Object size);

    default Optional<Map<String, Object>> findDuplicate(String accountId, String sha256, Object size) {
        return Optional.ofNullable(findDuplicateRow(accountId, sha256, size));
    }

    @Insert("""
            INSERT INTO upload_sessions(id, account_id, original_filename, content_type, size_bytes, chunk_size, total_chunks, file_sha256, status, created_at, updated_at) VALUES(#{id},#{accountId},#{filename},#{contentType},#{size},#{chunkSize},#{totalChunks},#{sha256},#{status},#{createdAt},#{updatedAt})
            """)
    void createUpload(@Param("id") String id, @Param("accountId") String accountId, @Param("filename") String filename, @Param("contentType") Object contentType, @Param("size") Object size, @Param("chunkSize") Object chunkSize, @Param("totalChunks") Object totalChunks, @Param("sha256") String sha256, @Param("status") String status, @Param("createdAt") String createdAt, @Param("updatedAt") String updatedAt);

    @Insert("""
            INSERT INTO upload_chunks(session_id, chunk_index, size_bytes, sha256, created_at) VALUES(#{uploadId},#{index},#{size},#{sha256},#{createdAt})
            ON CONFLICT (session_id, chunk_index) DO UPDATE
            SET size_bytes=EXCLUDED.size_bytes, sha256=EXCLUDED.sha256, created_at=EXCLUDED.created_at
            """)
    void saveChunk(@Param("uploadId") String uploadId, @Param("index") Object index, @Param("size") Object size, @Param("sha256") String sha256, @Param("createdAt") String createdAt);

    @Select("""
            SELECT id, original_filename, size_bytes, sha256
            FROM file_assets
            WHERE upload_id=#{uploadId} AND account_id=#{accountId}
            """)
    Map<String, Object> findCompletedAssetRow(@Param("uploadId") String uploadId, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findCompletedAsset(String uploadId, String accountId) {
        return Optional.ofNullable(findCompletedAssetRow(uploadId, accountId));
    }

    @Update("""
            UPDATE upload_sessions
            SET status='merging', updated_at=#{now}
            WHERE id=#{uploadId} AND status='uploading'
            """)
    int startMerge(@Param("now") String now, @Param("uploadId") String uploadId);

    @Select("""
            SELECT chunk_index, sha256
            FROM upload_chunks
            WHERE session_id=#{uploadId}
            """)
    List<Map<String, Object>> listChecksums(@Param("uploadId") String uploadId);

    @Update("""
            UPDATE upload_sessions
            SET status='uploading', updated_at=#{now}
            WHERE id=#{uploadId}
            """)
    void restoreUploading(@Param("now") String now, @Param("uploadId") String uploadId);

    @Update("""
            UPDATE upload_sessions
            SET status='failed', updated_at=#{now}
            WHERE id=#{uploadId}
            """)
    void markFailed(@Param("now") String now, @Param("uploadId") String uploadId);

    @Insert("""
            INSERT INTO file_assets(id, todo_id, account_id, storage_key, original_filename, content_type, size_bytes, created_at, sha256, upload_id, upload_mode) VALUES(#{id},NULL,#{accountId},#{storageKey},#{filename},#{contentType},#{size},#{createdAt},#{sha256},#{uploadId}, 'chunked')
            """)
    void insertChunkedAsset(@Param("id") String id, @Param("accountId") String accountId, @Param("storageKey") String storageKey, @Param("filename") String filename, @Param("contentType") Object contentType, @Param("size") Object size, @Param("createdAt") String createdAt, @Param("sha256") String sha256, @Param("uploadId") String uploadId);

    @Update("""
            UPDATE upload_sessions
            SET status='done', updated_at=#{now}
            WHERE id=#{uploadId}
            """)
    void markDone(@Param("now") String now, @Param("uploadId") String uploadId);

    @Update("""
            UPDATE upload_sessions
            SET status='aborted', updated_at=#{now}
            WHERE id=#{uploadId}
            """)
    void markAborted(@Param("now") String now, @Param("uploadId") String uploadId);

    @Insert("""
            INSERT INTO file_assets(id, todo_id, account_id, storage_key, original_filename, content_type, size_bytes, created_at, sha256, upload_mode) VALUES(#{id},NULL,#{accountId},#{storageKey},#{filename},#{contentType},#{size},#{createdAt},#{sha256}, 'batch')
            """)
    void insertBatchAsset(@Param("id") String id, @Param("accountId") String accountId, @Param("storageKey") String storageKey, @Param("filename") String filename, @Param("contentType") Object contentType, @Param("size") Object size, @Param("createdAt") String createdAt, @Param("sha256") String sha256);

    @Select("""
            SELECT id, original_filename, size_bytes, content_type, sha256, upload_mode, created_at
            FROM file_assets
            WHERE account_id=#{accountId}
            ORDER BY created_at DESC LIMIT 200
            """)
    List<Map<String, Object>> listAssets(@Param("accountId") String accountId);

    @Select("""
            SELECT *
            FROM file_assets
            WHERE id=#{fileId}
            """)
    Map<String, Object> findAssetRow(@Param("fileId") String fileId);

    default Optional<Map<String, Object>> findAsset(String fileId) {
        return Optional.ofNullable(findAssetRow(fileId));
    }

    @Delete("""
            DELETE
            FROM file_assets
            WHERE id=#{fileId}
            """)
    void deleteAsset(@Param("fileId") String fileId);

    @Select("""
            SELECT *
            FROM upload_sessions
            WHERE id=#{uploadId}
            """)
    Map<String, Object> findUploadRow(@Param("uploadId") String uploadId);

    default Optional<Map<String, Object>> findUpload(String uploadId) {
        return Optional.ofNullable(findUploadRow(uploadId));
    }

    @Select("""
            SELECT *
            FROM upload_sessions
            WHERE id=#{uploadId} FOR UPDATE
            """)
    Map<String, Object> lockUploadRow(@Param("uploadId") String uploadId);

    default Optional<Map<String, Object>> lockUpload(String uploadId) {
        return Optional.ofNullable(lockUploadRow(uploadId));
    }

    @Select("""
            SELECT chunk_index
            FROM upload_chunks
            WHERE session_id=#{uploadId}
            ORDER BY chunk_index
            """)
    List<Map<String, Object>> listChunkIndexes(@Param("uploadId") String uploadId);

    @Select("""
            SELECT COALESCE(SUM(size_bytes),0) AS used
            FROM file_assets
            WHERE account_id=#{accountId}
            """)
    long storageUsed(@Param("accountId") String accountId);
}
