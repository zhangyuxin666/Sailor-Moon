package com.activityassistant.api;

import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.FileService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * 文件中心：分片上传 / 断点续传 / 批量上传 / Range 下载 / 并发下载。
 */
@RestController
@RequestMapping("/files")
public class FileController {
    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    public record UploadInitRequest(String filename, String contentType, long size, String sha256, Integer chunkSize) {
    }

    /** 创建分片上传会话；若服务端已有相同文件则直接秒传。 */
    @PostMapping("/uploads")
    Map<String, Object> init(Authentication authentication, @RequestBody UploadInitRequest body) {
        AccountPrincipal account = AuthController.required(authentication);
        return files.init(account, body.filename(), body.contentType(), body.size(), body.sha256(), body.chunkSize());
    }

    /** 查询已上传分片，用于断点续传。 */
    @GetMapping("/uploads/{uploadId}")
    Map<String, Object> status(Authentication authentication, @PathVariable String uploadId) {
        return files.status(AuthController.required(authentication), uploadId);
    }

    /** 上传单个分片，X-Chunk-Sha256 用于校验；不匹配返回 400 让客户端重传。 */
    @PutMapping(value = "/uploads/{uploadId}/chunks/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    Map<String, Object> putChunk(Authentication authentication, @PathVariable String uploadId,
                                 @PathVariable int index,
                                 @RequestHeader(value = "X-Chunk-Sha256", required = false) String sha256,
                                 @RequestBody byte[] content) {
        return files.putChunk(AuthController.required(authentication), uploadId, index, content, sha256);
    }

    /** 合并分片并校验整文件哈希。 */
    @PostMapping("/uploads/{uploadId}/complete")
    Map<String, Object> complete(Authentication authentication, @PathVariable String uploadId) {
        return files.complete(AuthController.required(authentication), uploadId);
    }

    /** 取消上传，清理已落盘分片。 */
    @DeleteMapping("/uploads/{uploadId}")
    ResponseEntity<Void> abort(Authentication authentication, @PathVariable String uploadId) {
        files.abort(AuthController.required(authentication), uploadId);
        return ResponseEntity.noContent().build();
    }

    /** 小文件批量上传：一次请求多个文件，逐个返回结果。 */
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> batch(Authentication authentication,
                              @RequestParam("files") List<MultipartFile> files) {
        AccountPrincipal account = AuthController.required(authentication);
        List<Map<String, Object>> results = this.files.batch(account, files);
        long failed = results.stream().filter(item -> Boolean.FALSE.equals(item.get("ok"))).count();
        return Map.of("total", results.size(), "failed", failed, "results", results);
    }

    /** 我的文件列表。路径避开 SPA 页面路由 `/files`。 */
    @GetMapping("/mine")
    Map<String, Object> list(Authentication authentication) {
        return Map.of("files", files.list(AuthController.required(authentication)));
    }

    /** 删除文件。 */
    @DeleteMapping("/{fileId}")
    ResponseEntity<Void> delete(Authentication authentication, @PathVariable String fileId) {
        files.delete(AuthController.required(authentication), fileId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 下载。支持 Range，断点续传与多线程并发下载都靠它。
     *
     * <p>这里同步写响应流而不是返回 Resource/StreamingResponseBody：
     * 异步派发会绕过会话过滤器，返回 403；同步写流最稳，也不会有大文件内存占用。
     */
    @GetMapping("/{fileId}/download")
    void download(Authentication authentication, @PathVariable String fileId,
                  @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                  HttpServletResponse response) throws IOException {
        AccountPrincipal account = AuthController.required(authentication);
        Map<String, String> meta = files.downloadHeaders(account, fileId);
        Path path = files.path(account, fileId);
        long length = Files.size(path);

        MediaType type = MediaType.parseMediaType(meta.get("content_type"));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(meta.get("filename"), StandardCharsets.UTF_8).build();
        String etag = meta.get("sha256").isBlank() ? null : "\"" + meta.get("sha256") + "\"";

        long start = 0;
        long end = length - 1;
        boolean partial = range != null && !range.isBlank();
        if (partial) {
            long[] bounds = parseRange(range, length);
            if (bounds == null) {
                response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
                response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
                return;
            }
            start = bounds[0];
            end = bounds[1];
        }

        long count = end - start + 1;
        response.setStatus(partial ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
        response.setContentType(type.toString());
        response.setContentLengthLong(count);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (partial) response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        if (etag != null) response.setHeader(HttpHeaders.ETAG, etag);

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
             WritableByteChannel output = Channels.newChannel(response.getOutputStream())) {
            long position = start;
            long remaining = count;
            while (remaining > 0) {
                long transferred = channel.transferTo(position, remaining, output);
                if (transferred <= 0) break;
                position += transferred;
                remaining -= transferred;
            }
        }
    }

    /**
     * 解析单段 Range，返回 {start, end}；不合法或不可满足时返回 null。
     * 支持 "bytes=start-end"、"bytes=start-" 和 "bytes=-suffix"。
     */
    static long[] parseRange(String header, long length) {
        String value = header.strip();
        if (!value.startsWith("bytes=")) return null;
        value = value.substring("bytes=".length()).strip();
        if (value.contains(",")) return null; // 暂不支持多段 Range
        int dash = value.indexOf('-');
        if (dash < 0) return null;
        String startText = value.substring(0, dash).strip();
        String endText = value.substring(dash + 1).strip();
        try {
            long start;
            long end;
            if (startText.isEmpty()) {
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) return null;
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? length - 1 : Long.parseLong(endText);
            }
            if (start < 0 || start >= length || end < start) return null;
            return new long[]{start, Math.min(end, length - 1)};
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
