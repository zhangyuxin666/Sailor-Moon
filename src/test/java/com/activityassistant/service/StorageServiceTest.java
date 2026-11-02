package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.support.MissingChunksException;
import com.activityassistant.support.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageServiceTest {
    @TempDir Path directory;
    StorageService storage() throws Exception {
        AppProperties properties = mock(AppProperties.class);
        when(properties.storageLocalPath()).thenReturn(directory.toString());
        return new StorageService(properties);
    }
    @Test void outOfOrderChunksMergeInIndexOrderAndMatchHash() throws Exception {
        StorageService storage = storage();
        storage.writeChunk("session", 1, new byte[]{3,4});
        storage.writeChunk("session", 0, new byte[]{1,2});
        String digest = storage.mergeChunks("session", 2, "files/result", Map.of(0,Sha256.of(new byte[]{1,2}),1,Sha256.of(new byte[]{3,4})));
        assertThat(storage.read("files/result")).containsExactly(1,2,3,4);
        assertThat(digest).isEqualTo(Sha256.of(new byte[]{1,2,3,4}));
    }
    @Test void onDiskCorruptionReturnsExactRepairIndexes() throws Exception {
        StorageService storage = storage();
        byte[] content = {1,2,3};
        storage.writeChunk("session",0,content); storage.writeChunk("session",1,content);
        Files.write(storage.chunkPath("session",1),new byte[]{9,9,9});
        assertThatThrownBy(() -> storage.mergeChunks("session",2,"files/result",Map.of(0,Sha256.of(content),1,Sha256.of(content))))
                .isInstanceOfSatisfying(MissingChunksException.class, e -> assertThat(e.missing()).containsExactly(1));
        assertThat(directory.resolve("files/result")).doesNotExist();
    }
    @Test void missingDiskChunkIsRepairableEvenWhenDatabaseClaimsItExists() throws Exception {
        StorageService storage = storage(); storage.writeChunk("s",0,new byte[]{1});
        assertThatThrownBy(() -> storage.mergeChunks("s",2,"files/result",Map.of(0,Sha256.of(new byte[]{1}),1,"missing")))
                .isInstanceOfSatisfying(MissingChunksException.class,e -> assertThat(e.missing()).containsExactly(1));
    }
    @Test void replacingOneChunkDoesNotAppendOrLeakTemporaryFiles() throws Exception {
        StorageService storage = storage(); storage.writeChunk("s",0,new byte[]{1,2}); storage.writeChunk("s",0,new byte[]{3});
        assertThat(Files.readAllBytes(storage.chunkPath("s",0))).containsExactly(3);
        try (var paths=Files.list(storage.chunkPath("s",0).getParent())) { assertThat(paths.toList()).hasSize(1); }
    }
    @Test void rejectsEscapingStorageRoot() throws Exception {
        StorageService storage = storage();
        assertThatThrownBy(() -> storage.pathOf("../outside")).hasMessageContaining("无效");
    }
}
