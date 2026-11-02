package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ChunkChecksumException;
import com.activityassistant.support.MissingChunksException;
import com.activityassistant.support.Sha256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileServiceTest {
    final Db db=mock(Db.class);
    final StorageService storage=mock(StorageService.class);
    final AppProperties properties=mock(AppProperties.class);
    final FileService service=new FileService(db,storage,properties);
    final AccountPrincipal owner=new AccountPrincipal("owner","user","User","","participant","active","2026",0);
    final Map<String,Object> session=new HashMap<>(Map.of("account_id","owner","status","uploading","size_bytes",6L,
            "chunk_size",4,"total_chunks",2,"original_filename","sample.bin","content_type","application/octet-stream"));
    @BeforeEach void setup() {
        when(db.one("SELECT * FROM upload_sessions WHERE id=? FOR UPDATE","upload")).thenAnswer(inv -> Optional.of(session));
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        when(db.list("SELECT chunk_index FROM upload_chunks WHERE session_id=? ORDER BY chunk_index","upload"))
                .thenReturn(List.of(Map.of("chunk_index",0),Map.of("chunk_index",1)));
        when(db.list("SELECT chunk_index, sha256 FROM upload_chunks WHERE session_id=?","upload"))
                .thenReturn(List.of(Map.of("chunk_index",0,"sha256","first"),Map.of("chunk_index",1,"sha256","last")));
    }
    @Test void corruptChunkReturnsSpecificIndexAndDoesNotWriteDisk() {
        assertThatThrownBy(() -> service.putChunk(owner,"upload",0,new byte[]{1,2,3,4},Sha256.of(new byte[]{9})))
                .isInstanceOfSatisfying(ChunkChecksumException.class,e -> assertThat(e.chunkIndex()).isZero());
        verifyNoInteractions(storage);
    }
    @Test void validChunkIsWrittenUnderDatabaseSessionLock() {
        byte[] bytes={1,2,3,4};
        service.putChunk(owner,"upload",0,bytes,Sha256.of(bytes));
        verify(db).one("SELECT * FROM upload_sessions WHERE id=? FOR UPDATE","upload");
        verify(storage).writeChunk("upload",0,bytes);
    }
    @Test void missingChecksumCannotSkipVerification() {
        assertThatThrownBy(() -> service.putChunk(owner,"upload",0,new byte[]{1,2,3,4},null)).hasMessageContaining("X-Chunk-Sha256");
        verifyNoInteractions(storage);
    }
    @Test void invalidTailLengthIsRejected() {
        assertThatThrownBy(() -> service.putChunk(owner,"upload",1,new byte[]{1,2,3,4},Sha256.of(new byte[]{1})))
                .hasMessageContaining("大小不正确");
    }
    @Test void anotherAccountCannotResumeOrOverwriteSession() {
        session.put("account_id","other");
        assertThatThrownBy(() -> service.putChunk(owner,"upload",0,new byte[]{1,2,3,4},Sha256.of(new byte[]{1})))
                .hasMessageContaining("无权");
        verifyNoInteractions(storage);
    }
    @Test void missingDatabaseChunkPreventsMergeAndReturnsOnlyMissingIndex() {
        when(db.list("SELECT chunk_index FROM upload_chunks WHERE session_id=? ORDER BY chunk_index","upload"))
                .thenReturn(List.of(Map.of("chunk_index",0)));
        assertThatThrownBy(() -> service.complete(owner,"upload"))
                .isInstanceOfSatisfying(MissingChunksException.class,e -> assertThat(e.missing()).containsExactly(1));
        verifyNoInteractions(storage);
    }
    @Test void corruptDiskManifestLeavesSessionRetryableWithoutCreatingAsset() {
        when(storage.mergeChunks(eq("upload"),eq(2),anyString(),anyMap())).thenThrow(new MissingChunksException(List.of(1)));
        assertThatThrownBy(() -> service.complete(owner,"upload")).isInstanceOf(MissingChunksException.class);
        verify(db).update(eq("UPDATE upload_sessions SET status='uploading', updated_at=? WHERE id=?"),any(),eq("upload"));
        verify(db,never()).update(startsWith("INSERT INTO file_assets"),any(Object[].class));
    }
    @Test void completionRetryReturnsOriginalAssetAndNeverMergesAgain() {
        session.put("status","done");
        when(db.one("SELECT id, original_filename, size_bytes, sha256 FROM file_assets WHERE upload_id=? AND account_id=?","upload","owner"))
                .thenReturn(Optional.of(Map.of("id","original","original_filename","sample.bin","size_bytes",6L,"sha256","hash")));
        assertThat(service.complete(owner,"upload")).containsEntry("file_id","original");
        verifyNoInteractions(storage);
    }
    @Test void malformedFullFileDigestCannotSilentlyDisableVerification() {
        assertThatThrownBy(() -> service.init(owner,"sample.bin","application/octet-stream",8L,"not-a-hash",null))
                .hasMessageContaining("SHA-256");
    }
    @Test void batchReturnsSuccessAndErrorForIndividualFiles() {
        when(properties.uploadMaxMb()).thenReturn(1); when(properties.userStorageQuotaMb()).thenReturn(20);
        when(db.required(anyString(),any(Object[].class))).thenReturn(Map.of("used",0L));
        when(storage.saveStream(any(),anyString(),anyString())).thenThrow(new com.activityassistant.support.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"测试单个失败"));
        var results=service.batch(owner,List.of(new MockMultipartFile("files","a.txt","text/plain",new byte[]{1}),new MockMultipartFile("files","empty.txt","text/plain",new byte[0])));
        assertThat(results).hasSize(2);
        assertThat(results.get(0)).containsEntry("ok",false).containsEntry("error","测试单个失败");
        assertThat(results.get(1)).containsEntry("ok",false).containsEntry("error","空文件");
    }
}
