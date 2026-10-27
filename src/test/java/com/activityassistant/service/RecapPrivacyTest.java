package com.activityassistant.service;
import com.activityassistant.integration.AiClient;
import com.activityassistant.security.AccountPrincipal;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RecapPrivacyTest {
 final ObjectMapper mapper=new ObjectMapper();final RecapStore store=mock(RecapStore.class);final AiClient ai=mock(AiClient.class);
 final AccountPrincipal actor=new AccountPrincipal("u","owner","User","","manager","active","now",0);
 @Test void sendsOnlyAggregatesAndPersistsLocalFallback() throws Exception {
  var snapshot=mapper.readTree("{\"title\":\"private-name\",\"stats\":{\"registration_active\":3,\"tasks_total\":4,\"tasks_done\":2,\"registrations\":[{\"contact\":\"private-phone\"}],\"steps\":{},\"deliveries\":{}}}");
  when(store.begin("a",actor,"request-123")).thenReturn(new LinkedHashMap<>(Map.of("fresh",true,"id","r","snapshot",snapshot)));
  when(ai.recapAggregates(anyMap())).thenThrow(new RuntimeException("private URL and secret"));when(store.get("a","r",actor)).thenReturn(Map.of("status","succeeded"));
  new RecapService(store,ai,mapper).generate("a",actor,"request-123");
  var payload=org.mockito.ArgumentCaptor.forClass(Map.class);verify(ai).recapAggregates(payload.capture());assertFalse(payload.getValue().toString().contains("private"));assertFalse(payload.getValue().containsKey("registrations"));
  var body=org.mockito.ArgumentCaptor.forClass(String.class);var generation=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(store).complete(eq("r"),body.capture(),generation.capture());assertTrue(body.getValue().contains("3"));assertEquals("offline",generation.getValue().path("mode").asText());assertFalse(generation.getValue().toString().contains("secret"));
 }
 @Test void replayDoesNotCallModelAgain(){when(store.begin("a",actor,"request-123")).thenReturn(new LinkedHashMap<>(Map.of("fresh",false,"status","generating")));new RecapService(store,ai,mapper).generate("a",actor,"request-123");verifyNoInteractions(ai);}
}
