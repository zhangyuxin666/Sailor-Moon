package com.activityassistant.service;
import com.activityassistant.integration.QqGatewayClient;
import com.activityassistant.support.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
class OutboundDeliveryServiceTest {
    final OutboundStore store=mock(OutboundStore.class);final QqGatewayClient gateway=mock(QqGatewayClient.class);
    final OutboundDeliveryService service=new OutboundDeliveryService(store,gateway);
    @Test void successfulIntentDoesNotCallGateway(){when(store.begin("key","g","body")).thenReturn(Map.of("status","sent","external_id","qq1","send",false));assertEquals("qq1",service.send("g","body",null,"key").get("id"));verifyNoInteractions(gateway);}
    @Test void interruptedIntentQueriesEvidenceWithoutRepeating(){when(store.begin("key","g","body")).thenReturn(Map.of("status","sending","payload_hash","hash","send",false));when(gateway.delivery("key")).thenReturn(Map.of("status","sent","payload_hash","hash","external_id","qq1"));assertEquals("qq1",service.send("g","body",null,"key").get("id"));verify(gateway,never()).sendGroup(any(),any(),any(),any());verify(store).complete("key","sent","qq1",null);}
    @Test void missingEvidenceStopsAutomaticSending(){when(store.begin("key","g","body")).thenReturn(Map.of("status","unknown","payload_hash","hash","send",false));when(gateway.delivery("key")).thenReturn(Map.of("status","missing"));assertEquals(409,assertThrows(ApiException.class,()->service.send("g","body",null,"key")).status().value());verify(gateway,never()).sendGroup(any(),any(),any(),any());}
    @Test void mismatchedEvidenceCannotClaimSuccess(){when(store.begin("key","g","body")).thenReturn(Map.of("status","sending","payload_hash","hash","send",false));when(gateway.delivery("key")).thenReturn(Map.of("status","sent","payload_hash","wrong"));assertEquals(409,assertThrows(ApiException.class,()->service.send("g","body",null,"key")).status().value());verify(gateway,never()).sendGroup(any(),any(),any(),any());}
    @Test void transportLossIsUnknownRatherThanRetryable(){when(store.begin("key","g","body")).thenReturn(Map.of("status","sending","send",true));when(gateway.sendGroup("g","body",null,"key")).thenThrow(new RuntimeException("socket closed"));assertEquals(409,assertThrows(ApiException.class,()->service.send("g","body",null,"key")).status().value());verify(store).complete("key","unknown",null,"投递结果未知，请核对后处理");}
    @Test void explicitlyNotSentCanRetry(){when(store.begin("key","g","body")).thenReturn(Map.of("status","failed","send",true));when(gateway.sendGroup("g","body",null,"key")).thenThrow(new QqGatewayClient.NotSent());assertEquals(502,assertThrows(ApiException.class,()->service.send("g","body",null,"key")).status().value());verify(store).complete("key","failed",null,"网关确认未发送，可重试");}
}
