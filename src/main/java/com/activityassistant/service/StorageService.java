package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.support.ApiException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class StorageService {
    private final Path root;

    public StorageService(AppProperties properties) throws IOException {
        this.root = Path.of(properties.storageLocalPath()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public String save(byte[] content, String filename, String prefix) {
        try {
            String clean = Path.of(filename).getFileName().toString();
            String key = prefix + "/" + UUID.randomUUID().toString().replace("-", "") + "_" + clean;
            Path target = safe(key);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
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

    public void delete(String key) {
        try {
            Files.deleteIfExists(safe(key));
        } catch (IOException ignored) {
        }
    }

    private Path safe(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw ApiException.badRequest("无效的文件路径");
        return path;
    }
}
