package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagementRulesTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private com.fasterxml.jackson.databind.JsonNode fields() throws Exception {return mapper.readTree("""
        [{"name":"name","label":"姓名","required":true},{"name":"contact","label":"联系方式","required":true},
         {"name":"choice","label":"出行方式","type":"select","required":true,"options":["公交","步行"]},
         {"name":"note","label":"备注","type":"text","required":false}]
        """);}
    @Test void publishedAnswersAreValidatedAndPreservedWithoutInventingFields() throws Exception {
        assertThat(RegistrationService.validate(fields(),"测试","Test@example.invalid",Map.of("choice","公交","note","  原回答  ")))
            .containsEntry("choice","公交").containsEntry("note","原回答");
    }
    @Test void requiredQuestionAndInvalidOptionErrorsIdentifyTheLabel() throws Exception {
        assertThatThrownBy(()->RegistrationService.validate(fields(),"测试","contact",Map.of())).hasMessageContaining("出行方式");
        assertThatThrownBy(()->RegistrationService.validate(fields(),"测试","contact",Map.of("choice","不在选项内"))).hasMessageContaining("出行方式");
    }
    @Test void unexpectedAndStructuredAnswersAreRejectedForNewRegistrations() throws Exception {
        assertThatThrownBy(()->RegistrationService.validate(fields(),"测试","contact",Map.of("choice","公交","unknown","x"))).isInstanceOf(ApiException.class).hasMessageContaining("unknown");
        assertThatThrownBy(()->RegistrationService.validate(fields(),"测试","contact",Map.of("choice",List.of("公交")))).hasMessageContaining("出行方式");
    }
    @ParameterizedTest @ValueSource(ints={2001,4000}) void longAnswerIsRejected(int length) throws Exception {
        assertThatThrownBy(()->RegistrationService.validate(fields(),"测试","contact",Map.of("choice","公交","note","x".repeat(length)))).hasMessageContaining("备注");
    }
    @Test void identityNormalizationDoesNotUseDisplayNameAlone() {
        assertThat(ActivityGate.identity(" Ａlice "," TEST @Example.INVALID ")).isEqualTo(ActivityGate.identity("alice","test@example.invalid"));
        assertThat(ActivityGate.identity("同名","a")).isNotEqualTo(ActivityGate.identity("同名","b"));
    }
    @Test void graphAllowsDagAndRejectsCycleSelfAndForeignDependency() {
        TaskManagementService.validateGraph(Map.of("a",List.of(),"b",List.of("a"),"c",List.of("a","b")));
        for(var graph:List.of(Map.of("a",List.of("a")),Map.of("a",List.of("b"),"b",List.of("a")),Map.of("a",List.of("foreign"))))
            assertThatThrownBy(()->TaskManagementService.validateGraph(graph)).isInstanceOf(ApiException.class);
    }
    @Test void taskMetadataRejectsInvalidPriorityOrMissingTitle() throws Exception {
        for(String raw:List.of("{\"title\":\"\"}","{\"title\":\"任务\",\"priority\":\"wrong\"}","{\"title\":\"任务\",\"depends_on\":true}"))
            assertThatThrownBy(()->TaskManagementService.validateTask(mapper.readTree(raw))).isInstanceOf(ApiException.class);
    }
    @Test void calendarsRetainExistingUidAndCarryRevisionAndCancellation() {
        String old="BEGIN:VCALENDAR\r\nUID:old-calendar@sailor-moon\r\nEND:VCALENDAR";
        String value=ActivityManagementService.calendarWithUid("activity","活动","2030-11-01T02:00:00Z","说明",4,true,old,"Asia/Shanghai");
        assertThat(value).contains("UID:old-calendar@sailor-moon","SEQUENCE:4","STATUS:CANCELLED","DTSTART:20301101T020000Z");
        assertThat(value).doesNotContain("UID:activity@sailor-moon");
    }
    @Test void cancelledGatePreventsTheNextStepButPermitsExplicitCancellationNotice() {
        Db db=mock(Db.class);when(db.one("SELECT * FROM activities WHERE id=? FOR UPDATE","a")).thenReturn(Optional.of(Map.of("status","cancelled")));
        ActivityGate gate=new ActivityGate(db,mapper,null,null,null);AtomicBoolean called=new AtomicBoolean();
        assertThatThrownBy(()->gate.during("a",false,()->{called.set(true);return null;})).isInstanceOf(ActivityGate.CancelledFlow.class);
        assertThat(called).isFalse();gate.during("a",true,()->{called.set(true);return null;});assertThat(called).isTrue();
    }
}
