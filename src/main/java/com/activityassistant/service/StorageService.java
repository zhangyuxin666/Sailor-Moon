package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Sha256;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 本地磁盘存储。
 *
 * <p>目录约定：
 * <pre>
 * {root}/files/{accountId}/{uuid}_{name}   最终文件
 * {root}/chunks/{uploadId}/{index}.part    分片临时文件
 * </pre>
 */
@Service
public class StorageService {
    private final Path root;

    public StorageService(AppProperties properties) throws IOException {
        this.root = Path.of(properties.storageLocalPath()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public Path root() {
        return root;
    }

    public String save(byte[] content, String filename, String prefix) {
        try {
            String key = nextKey(prefix, filename);
            Path target = safe(key);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return key;
        } catch (IOException exception) {
            throw new IllegalStateException("文件保存失败", exception);
        }
    }

    /** 流式写入，避免把大文件整体读进内存。 */
    public String saveStream(InputStream input, String filename, String prefix) {
        try {
            String key = nextKey(prefix, filename);
            Path target = safe(key);
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return key;
        } catch (IOException exception) {
            throw new IllegalStateException("文件保存失败", exception);
        }
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(safe(key));
        } catch (IOException exception) {
            throw ApiException.notFound("文件不存在");
        }
    }

    public InputStream open(String key) {
        try {
            return Files.newInputStream(safe(key));
        } catch (IOException exception) {
            throw ApiException.notFound("文件不存在");
        }
    }

    public Path pathOf(String key) {
        Path path = safe(key);
        if (!Files.isRegularFile(path)) throw ApiException.notFound("文件不存在");
        return path;
    }

    public long size(String key) {
        try {
            return Files.size(safe(key));
        } catch (IOException exception) {
            throw ApiException.notFound("文件不存在");
        }
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(safe(key));
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- chunks

    public void writeChunk(String uploadId, int index, byte[] data) {
        Path target = chunkPath(uploadId, index);
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(index + ".part.tmp");
            Files.write(temp, data);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("分片写入失败", exception);
        }
    }

    public Path chunkPath(String uploadId, int index) {
        return safe("chunks/" + uploadId + "/" + String.format("%06d", index) + ".part");
    }

    public List<Integer> chunkIndexes(String uploadId) {
        Path directory = safe("chunks/" + uploadId);
        if (!Files.isDirectory(directory)) return List.of();
        List<Integer> indexes = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".part"))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        try {
                            indexes.add(Integer.parseInt(name.substring(0, name.indexOf('.'))));
                        } catch (NumberFormatException ignored) {
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("分片目录读取失败", exception);
        }
        indexes.sort(Integer::compareTo);
        return indexes;
    }

    /** 顺序合并分片，返回合并后文件的 SHA-256。 */
    public String mergeChunks(String uploadId, int totalChunks, String targetKey) {
        Path target = safe(targetKey);
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".merging");
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (int index = 0; index < totalChunks; index++) {
                    Path chunk = chunkPath(uploadId, index);
                    if (!Files.isRegularFile(chunk)) throw ApiException.badRequest("分片 " + index + " 缺失");
                    try (FileChannel in = FileChannel.open(chunk, StandardOpenOption.READ)) {
                        long position = 0;
                        long remaining = in.size();
                        while (remaining > 0) {
                            long written = in.transferTo(position, remaining, out);
                            if (written <= 0) throw new IOException("分片读取中断");
                            position += written;
                            remaining -= written;
                        }
                    }
                }
            }
            String digest = Sha256.of(temp);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return digest;
        } catch (IOException exception) {
            throw new IllegalStateException("分片合并失败", exception);
        }
    }

    public void deleteUpload(String uploadId) {
        Path directory = safe("chunks/" + uploadId);
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- helpers

    private String nextKey(String prefix, String filename) {
        String clean = Path.of(filename).getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (clean.isBlank()) clean = "file";
        return prefix + "/" + UUID.randomUUID().toString().replace("-", "") + "_" + clean;
    }

    private Path safe(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw ApiException.badRequest("无效的文件路径");
        return path;
    }
}
