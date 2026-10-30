package com.activityassistant.service;
import com.activityassistant.persistence.Db;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RegistrationReplayTest {
 private final Db db=mock(Db.class);
 private final String token="known-client-cancellation-token-12345678";
 private RegistrationService service;
 private final Map<String,Object> registration=new LinkedHashMap<>();
 @BeforeEach void setup(){
  ActivityGate target=new ActivityGate(db,new ObjectMapper(),null,mock(AuditService.class),mock(BackgroundJobService.class));
  ProxyFactory factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice((MethodInterceptor)invocation->invocation.proceed());
  ActivityGate proxy=(ActivityGate)factory.getProxy();service=new RegistrationService(proxy,db);
  when(db.one("SELECT activity_id FROM forms WHERE id=?","form")).thenReturn(Optional.of(Map.of("activity_id","activity")));
  when(db.one("SELECT * FROM activities WHERE id=? FOR UPDATE","activity")).thenReturn(Optional.of(Map.of("status","cancelled")));
  when(db.required("SELECT * FROM forms WHERE id=? FOR UPDATE","form")).thenReturn(Map.of("activity_id","activity","registration_status","closed","fields_json","[{\"name\":\"choice\",\"label\":\"选择\",\"type\":\"select\",\"options\":[\"A\",\"B\"],\"required\":true}]"));
  registration.putAll(Map.of("id",1L,"name","Name","contact","contact","extra_json","{\"choice\":\"A\"}","status","active","cancel_token_hash",ActivityGate.hash(token)));
  when(db.one("SELECT * FROM registrations WHERE form_id=? AND request_id=?","form","request_123")).thenReturn(Optional.of(registration));
 }
 @Test void proxiedGateSupportsReplayAfterFormClosesWithoutCreatingAnything(){
  var response=service.register("form","Name","contact",Map.of("choice","A"),"request_123",token);
  assertThat(response).containsEntry("idempotent",true).containsEntry("registration_id",1L).containsEntry("cancellation_token",token);
  verify(db,never()).insertReturningId(anyString(),anyMap());verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void sameRequestCannotBeUsedForDifferentAnswersOrReceipt(){
  assertThatThrownBy(()->service.register("form","Name","contact",Map.of("choice","B"),"request_123",token)).isInstanceOf(ApiException.class).hasMessageContaining("其他内容");
  assertThatThrownBy(()->service.register("form","Name","contact",Map.of("choice","A"),"request_123","different-client-cancellation-token-12345678")).isInstanceOf(ApiException.class);
  verify(db,never()).insertReturningId(anyString(),anyMap());
 }
 @Test void cancelledReceiptReplayDoesNotReactivateTheRegistration(){
  registration.put("status","cancelled");var result=service.register("form","Name","contact",Map.of("choice","A"),"request_123",token);
  assertThat(result).containsEntry("status","cancelled").containsEntry("idempotent",true);verify(db,never()).update(anyString(),any(Object[].class));
 }
}
