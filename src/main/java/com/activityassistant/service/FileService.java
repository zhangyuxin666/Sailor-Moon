package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.ChunkChecksumException;
import com.activityassistant.support.Ids;
import com.activityassistant.support.MissingChunksException;
import com.activityassistant.support.Sha256;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件中心：分片上传、断点续传、分片校验、批量上传与下载。
 */
@Service
public class FileService {
    /** 单个文件上限 2 GB。 */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MIN_CHUNK_BYTES = 256 * 1024;
    private static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;
    private static final int DEFAULT_CHUNK_BYTES = 2 * 1024 * 1024;

    private final Db db;
    private final StorageService storage;
    private final AppProperties properties;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public FileService(Db db, StorageService storage, AppProperties properties) {
        this.db = db;
        this.storage = storage;
        this.properties = properties;
    }

    // ------------------------------------------------------------ 分片上传

    public Map<String, Object> init(AccountPrincipal account, String filename, String contentType,
                                    long size, String sha256, Integer requestedChunkSize) {
        if (filename == null || filename.isBlank()) throw ApiException.badRequest("文件名不能为空");
        if (size <= 0) throw ApiException.badRequest("文件大小必须大于 0");
        if (size > MAX_FILE_BYTES) throw ApiException.badRequest("单个文件不能超过 2GB");

        int chunkSize = requestedChunkSize == null ? DEFAULT_CHUNK_BYTES
                : Math.max(MIN_CHUNK_BYTES, Math.min(MAX_CHUNK_BYTES, requestedChunkSize));
        int totalChunks = (int) ((size + chunkSize - 1) / chunkSize);
        String digest = normalizeSha(sha256);

        // 秒传：同一账号下已经存在完全相同的文件，直接复用
        if (digest != null) {
            Map<String, Object> existing = db.one(
                    "SELECT id, original_filename, size_bytes, sha256 FROM file_assets WHERE account_id=? AND sha256=? AND size_bytes=?",
                    account.id(), digest, size).orElse(null);
            if (existing != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("upload_id", null);
                result.put("file_id", existing.get("id"));
                result.put("instant", true);
                result.put("sha256", digest);
                result.put("message", "秒传成功，服务端已存在相同文件");
                return result;
            }
        }

        requireQuota(account, size);

        String id = Ids.shortId();
        String now = Instant.now().toString();
        db.update("INSERT INTO upload_sessions(id, account_id, original_filename, content_type, size_bytes, " +
                        "chunk_size, total_chunks, file_sha256, status, created_at, updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, account.id(), filename.strip(), normalizeType(contentType), size, chunkSize, totalChunks,
                digest, "uploading", now, now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("upload_id", id);
        result.put("filename", filename.strip());
        result.put("size", size);
        result.put("chunk_size", chunkSize);
        result.put("total_chunks", totalChunks);
        result.put("uploaded", List.of());
        result.put("instant", false);
        return result;
    }

    public Map<String, Object> status(AccountPrincipal account, String uploadId) {
        Map<String, Object> session = session(account, uploadId);
        List<Integer> uploaded = uploadedIndexes(uploadId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("upload_id", uploadId);
        result.put("filename", session.get("original_filename"));
        result.put("size", session.get("size_bytes"));
        result.put("chunk_size", session.get("chunk_size"));
        result.put("total_chunks", session.get("total_chunks"));
        result.put("status", session.get("status"));
        result.put("uploaded", uploaded);
        result.put("uploaded_bytes", uploadedSize(uploaded, session));
        return result;
    }

    public Map<String, Object> putChunk(AccountPrincipal account, String uploadId, int index,
                                        byte[] content, String declaredSha) {
        Map<String, Object> session = session(account, uploadId);
        if (!"uploading".equals(String.valueOf(session.get("status")))) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "该上传任务已结束，无法继续上传分片");
        }

        long size = ((Number) session.get("size_bytes")).longValue();
        int chunkSize = ((Number) session.get("chunk_size")).intValue();
        int totalChunks = ((Number) session.get("total_chunks")).intValue();
        if (index < 0 || index >= totalChunks) throw ApiException.badRequest("分片序号超出范围");

        long expectedSize = Math.min(chunkSize, size - (long) index * chunkSize);
        if (content == null || content.length != expectedSize) {
            throw ApiException.badRequest("分片 " + index + " 大小不正确，期望 " + expectedSize + " 字节");
        }

        String actual = Sha256.of(content);
        if (declaredSha != null && !declaredSha.isBlank() && !Sha256.matches(declaredSha.strip(), actual)) {
            // 分片损坏：不落盘，直接告诉客户端重传这一片
            throw new ChunkChecksumException(index, declaredSha.strip(), actual);
        }

        storage.writeChunk(uploadId, index, content);
        db.update("INSERT INTO upload_chunks(session_id, chunk_index, size_bytes, sha256, created_at) " +
                        "VALUES(?,?,?,?,?) ON CONFLICT (session_id, chunk_index) " +
                        "DO UPDATE SET size_bytes=EXCLUDED.size_bytes, sha256=EXCLUDED.sha256, created_at=EXCLUDED.created_at",
                uploadId, index, content.length, actual, Instant.now().toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("size", content.length);
        result.put("sha256", actual);
        result.put("received", uploadedIndexes(uploadId).size());
        return result;
    }

    public Map<String, Object> complete(AccountPrincipal account, String uploadId) {
        Object lock = locks.computeIfAbsent(uploadId, key -> new Object());
        synchronized (lock) {
            try {
                Map<String, Object> session = session(account, uploadId);
                int totalChunks = ((Number) session.get("total_chunks")).intValue();

                List<Integer> uploaded = uploadedIndexes(uploadId);
                List<Integer> missing = new ArrayList<>();
                for (int index = 0; index < totalChunks; index++) {
                    if (!uploaded.contains(index)) missing.add(index);
                }
                if (!missing.isEmpty()) throw new MissingChunksException(missing);

                int updated = db.update("UPDATE upload_sessions SET status='merging', updated_at=? " +
                        "WHERE id=? AND status='uploading'", Instant.now().toString(), uploadId);
                if (updated != 1) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "该上传任务已处理");

                String filename = String.valueOf(session.get("original_filename"));
                String key = storageKey(account.id(), filename);
                String digest;
                try {
                    digest = storage.mergeChunks(uploadId, totalChunks, key);
                } catch (RuntimeException exception) {
                    db.update("UPDATE upload_sessions SET status='uploading', updated_at=? WHERE id=?",
                            Instant.now().toString(), uploadId);
                    throw exception;
                }

                String expected = (String) session.get("file_sha256");
                if (expected != null && !Sha256.matches(expected, digest)) {
                    storage.delete(key);
                    db.update("UPDATE upload_sessions SET status='failed', updated_at=? WHERE id=?",
                            Instant.now().toString(), uploadId);
                    throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                            "整文件 SHA-256 校验失败（期望 " + expected.substring(0, 12) + "…，实际 "
                                    + digest.substring(0, 12) + "…），请重新上传");
                }

                String fileId = Ids.shortId();
                long size = ((Number) session.get("size_bytes")).longValue();
                db.update("INSERT INTO file_assets(id, todo_id, account_id, storage_key, original_filename, " +
                                "content_type, size_bytes, created_at, sha256, upload_id, upload_mode) " +
                                "VALUES(?,NULL,?,?,?,?,?,?,?,?, 'chunked')",
                        fileId, account.id(), key, filename, session.get("content_type"), size,
                        Instant.now().toString(), digest, uploadId);
                db.update("UPDATE upload_sessions SET status='done', updated_at=? WHERE id=?",
                        Instant.now().toString(), uploadId);
                storage.deleteUpload(uploadId);
                locks.remove(uploadId);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("file_id", fileId);
                result.put("filename", filename);
                result.put("size", size);
                result.put("sha256", digest);
                return result;
            } finally {
                locks.remove(uploadId);
            }
        }
    }

    public void abort(AccountPrincipal account, String uploadId) {
        session(account, uploadId);
        db.update("UPDATE upload_sessions SET status='aborted', updated_at=? WHERE id=?",
                Instant.now().toString(), uploadId);
        storage.deleteUpload(uploadId);
    }

    // ------------------------------------------------------------ 批量 / 列表

    public List<Map<String, Object>> batch(AccountPrincipal account, List<MultipartFile> files) {
        List<Map<String, Object>> results = new ArrayList<>();
        long limit = (long) properties.uploadMaxMb() * 1024 * 1024;
        for (MultipartFile file : files) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filename", file.getOriginalFilename());
            item.put("size", file.getSize());
            try {
                if (file.isEmpty()) throw ApiException.badRequest("空文件");
                if (file.getSize() > limit) {
                    throw ApiException.badRequest("单个文件不能超过 " + properties.uploadMaxMb() + "MB");
                }
                requireQuota(account, file.getSize());
                String filename = file.getOriginalFilename();
                String key;
                String digest;
                try (InputStream input = file.getInputStream()) {
                    key = storage.saveStream(input, filename, "files/" + account.id());
                    digest = Sha256.of(storage.pathOf(key));
                }
                String fileId = Ids.shortId();
                db.update("INSERT INTO file_assets(id, todo_id, account_id, storage_key, original_filename, " +
                                "content_type, size_bytes, created_at, sha256, upload_mode) " +
                                "VALUES(?,NULL,?,?,?,?,?,?,?, 'batch')",
                        fileId, account.id(), key, filename, file.getContentType(), file.getSize(),
                        Instant.now().toString(), digest);
                item.put("ok", true);
                item.put("file_id", fileId);
                item.put("sha256", digest);
            } catch (ApiException exception) {
                item.put("ok", false);
                item.put("error", exception.getMessage());
            } catch (IOException exception) {
                item.put("ok", false);
                item.put("error", "读取上传文件失败");
            }
            results.add(item);
        }
        return results;
    }

    public List<Map<String, Object>> list(AccountPrincipal account) {
        return db.list("SELECT id, original_filename, size_bytes, content_type, sha256, upload_mode, created_at " +
                "FROM file_assets WHERE account_id=? ORDER BY created_at DESC LIMIT 200", account.id());
    }

    public Map<String, Object> owned(AccountPrincipal account, String fileId) {
        Map<String, Object> asset = db.one("SELECT * FROM file_assets WHERE id=?", fileId)
                .orElseThrow(() -> ApiException.notFound("文件不存在"));
        if (!account.isManager() && !account.id().equals(String.valueOf(asset.get("account_id")))) {
            throw ApiException.forbidden("无权访问该文件");
        }
        return asset;
    }

    public Path path(AccountPrincipal account, String fileId) {
        return storage.pathOf(String.valueOf(owned(account, fileId).get("storage_key")));
    }

    public void delete(AccountPrincipal account, String fileId) {
        Map<String, Object> asset = owned(account, fileId);
        if (!account.isManager() && !account.id().equals(String.valueOf(asset.get("account_id")))) {
            throw ApiException.forbidden("无权删除该文件");
        }
        storage.delete(String.valueOf(asset.get("storage_key")));
        db.update("DELETE FROM file_assets WHERE id=?", fileId);
    }

    // ------------------------------------------------------------ helpers

    private Map<String, Object> session(AccountPrincipal account, String uploadId) {
        Map<String, Object> session = db.one("SELECT * FROM upload_sessions WHERE id=?", uploadId)
                .orElseThrow(() -> ApiException.notFound("上传任务不存在"));
        if (!account.id().equals(String.valueOf(session.get("account_id")))) {
            throw ApiException.forbidden("无权访问该上传任务");
        }
        return session;
    }

    private List<Integer> uploadedIndexes(String uploadId) {
        return db.list("SELECT chunk_index FROM upload_chunks WHERE session_id=? ORDER BY chunk_index", uploadId)
                .stream().map(row -> ((Number) row.get("chunk_index")).intValue()).toList();
    }

    private long uploadedSize(List<Integer> uploaded, Map<String, Object> session) {
        long chunkSize = ((Number) session.get("chunk_size")).longValue();
        long size = ((Number) session.get("size_bytes")).longValue();
        long total = 0;
        for (int index : uploaded) {
            total += Math.min(chunkSize, size - (long) index * chunkSize);
        }
        return total;
    }

    private void requireQuota(AccountPrincipal account, long incoming) {
        long used = ((Number) db.required(
                "SELECT COALESCE(SUM(size_bytes),0) AS used FROM file_assets WHERE account_id=?",
                account.id()).get("used")).longValue();
        long quota = (long) properties.userStorageQuotaMb() * 1024 * 1024;
        if (used + incoming > quota) {
            throw ApiException.badRequest("超出个人存储配额（已用 " + used / 1024 / 1024 + "MB，上限 "
                    + properties.userStorageQuotaMb() + "MB）");
        }
    }

    private String storageKey(String accountId, String filename) {
        return "files/" + accountId + "/" + java.util.UUID.randomUUID().toString().replace("-", "") + "_"
                + Path.of(filename).getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String normalizeType(String contentType) {
        if (contentType == null || contentType.isBlank()) return "application/octet-stream";
        return contentType.strip();
    }

    private String normalizeSha(String sha256) {        if (sha256 == null) return null;
        String value = sha256.strip().toLowerCase();
        return value.matches("[0-9a-f]{64}") ? value : null;
    }

    /** 供下载接口做 ETag。 */
    public Map<String, String> downloadHeaders(AccountPrincipal account, String fileId) {
        Map<String, Object> asset = owned(account, fileId);
        Map<String, String> headers = new HashMap<>();
        headers.put("filename", String.valueOf(asset.get("original_filename")));
        headers.put("content_type", normalizeType(asset.get("content_type") == null ? null
                : String.valueOf(asset.get("content_type"))));
        headers.put("sha256", asset.get("sha256") == null ? "" : String.valueOf(asset.get("sha256")));
        return headers;
    }
}
