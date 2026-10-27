package com.activityassistant.api;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.ExportService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ExportControllerTest {
 @Test void csvResponseUsesFixedFilenameAndNoStoreHeaders() {
  ExportService service=mock(ExportService.class);var actor=new AccountPrincipal("id","manager","M","","manager","active","now",0);var auth=new UsernamePasswordAuthenticationToken(actor,null);byte[] data="\uFEFFcsv".getBytes(StandardCharsets.UTF_8);when(service.tasks("activity",actor)).thenReturn(data);
  var response=new ExportController(service).tasks(auth,"activity");assertArrayEquals(data,response.getBody());assertEquals("no-store",response.getHeaders().getCacheControl());assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("activity-tasks.csv"));assertEquals("UTF-8",response.getHeaders().getContentType().getCharset().name());assertEquals(data.length,response.getHeaders().getContentLength());
 }
}
