package com.activityassistant.api;

import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileControllerTest {
    @TempDir Path directory;
    final AccountPrincipal account = new AccountPrincipal("owner","student","Student","","participant","active","2026",0);
    final UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(account,null,java.util.List.of());
    FileController controller() throws Exception {
        Path file = directory.resolve("file.bin"); Files.write(file,new byte[]{0,1,2,3,4,5});
        FileService files=mock(FileService.class);
        when(files.downloadHeaders(account,"file")).thenReturn(Map.of("content_type","application/octet-stream","filename","中文.bin","sha256","abc"));
        when(files.path(account,"file")).thenReturn(file);
        return new FileController(files,8);
    }
    @Test void closedRangeWritesExactBytesAnd206() throws Exception {
        var response=new MockHttpServletResponse();
        controller().download(authentication,"file","bytes=2-4",null,null,new MockHttpServletRequest("GET","/"),response);
        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getHeader("Content-Range")).isEqualTo("bytes 2-4/6");
        assertThat(response.getContentAsByteArray()).containsExactly(2,3,4);
    }
    @Test void changedIfRangeMustReturnWholeNewFile() throws Exception {
        var response=new MockHttpServletResponse();
        controller().download(authentication,"file","bytes=3-","\"old\"",null,new MockHttpServletRequest("GET","/"),response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).containsExactly(0,1,2,3,4,5);
    }
    @Test void headReturnsHeadersWithoutReadingResponseBody() throws Exception {
        var response=new MockHttpServletResponse();
        controller().download(authentication,"file",null,null,null,new MockHttpServletRequest("HEAD","/"),response);
        assertThat(response.getContentLengthLong()).isEqualTo(6);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("ETag")).isEqualTo("\"abc\"");
    }
    @Test void invalidRangeReturns416AndTotalLength() throws Exception {
        var response=new MockHttpServletResponse();
        controller().download(authentication,"file","bytes=99-",null,null,new MockHttpServletRequest("GET","/"),response);
        assertThat(response.getStatus()).isEqualTo(416);
        assertThat(response.getHeader("Content-Range")).isEqualTo("bytes */6");
    }
    @Test void matchingIfNoneMatchDoesNotStreamFile() throws Exception {
        var response=new MockHttpServletResponse();
        controller().download(authentication,"file",null,null,"\"abc\"",new MockHttpServletRequest("GET","/"),response);
        assertThat(response.getStatus()).isEqualTo(304); assertThat(response.getContentAsByteArray()).isEmpty();
    }
    @Test void parsesOpenSuffixClampedAndInvalidRanges() {
        assertThat(FileController.parseRange("bytes=3-",6)).containsExactly(3,5);
        assertThat(FileController.parseRange("bytes=-2",6)).containsExactly(4,5);
        assertThat(FileController.parseRange("bytes=0-999",6)).containsExactly(0,5);
        for(String value:java.util.List.of("bytes=-0","bytes=4-2","bytes=x-y","bytes=0-1,3-4","none=0-1","bytes=999999999999999999999999-"))
            assertThat(FileController.parseRange(value,6)).as(value).isNull();
        assertThat(FileController.parseRange("bytes=-2",0)).isNull();
    }
}
