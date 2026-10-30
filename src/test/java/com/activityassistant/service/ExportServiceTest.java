package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExportServiceTest {
    final Db db=mock(Db.class);
    final ClassroomService classroom=mock(ClassroomService.class);
    final AuditService audits=mock(AuditService.class);
    final ExportService service=new ExportService(db,classroom,audits,new ObjectMapper());
    final AccountPrincipal owner=new AccountPrincipal("owner-id","owner","管理者","","manager","active","now",0);
    final AccountPrincipal other=new AccountPrincipal("other-id","other","其他管理者","","manager","active","now",0);
    final AccountPrincipal student=new AccountPrincipal("student-id","student","同名","","participant","active","now",0);
    void owned() {
        when(db.one(startsWith("SELECT a.user_id,ac.class_id"),eq("activity"))).thenReturn(Optional.of(Map.of("user_id","owner","class_id","class","organization_id","org")));
        when(classroom.requireClassManager("class",owner)).thenReturn(Map.of("organization_id","org"));
    }
    @Test void registrationSnapshotIncludesAllAnswersAndCancelledHistoryButNoInternalCredentialColumns() {
        owned();
        when(db.list(startsWith("SELECT id,title,fields_json"),eq("activity"))).thenReturn(List.of(Map.of("id","form","title","报名表","fields_json","[{\"name\":\"name\",\"label\":\"姓名\"},{\"name\":\"transport\",\"label\":\"交通方式\"}]")));
        Map<String,Object> row=new LinkedHashMap<>(Map.of("id",7,"form_id","form","form_title","报名表","name","＝FORMULA()","contact","contact@example.invalid","status","cancelled","created_at","now","extra_json","{\"transport\":\"公交\",\"legacy\":{\"nested\":[false,0]},\"number\":0}"));
        row.put("cancel_token_hash","must-not-export");row.put("request_id","also-not-export");row.put("metadata_json","not-business-data");
        when(db.list(startsWith("SELECT r.id,r.form_id"),eq("activity"))).thenReturn(List.of(row));
        String csv=new String(service.registrations("activity",owner),StandardCharsets.UTF_8);
        assertTrue(csv.contains("交通方式 [transport]"));assertTrue(csv.contains("历史字段 [legacy]"));assertTrue(csv.contains("公交"));assertTrue(csv.contains("已取消"));assertTrue(csv.contains("false,0"));assertTrue(csv.contains("'＝FORMULA()"));
        assertFalse(csv.contains("must-not-export"));assertFalse(csv.contains("also-not-export"));assertFalse(csv.contains("not-business-data"));assertFalse(csv.contains("cancel_token_hash"));
        verify(audits).write(eq("export.registrations"),eq(owner.id()),eq("org"),eq("activity"),eq("activity"),eq(Map.of("row_count",1)));
    }
    @Test void ownerAndOrganizationAuthorizationPrecedePrivateRows() {
        owned();assertThrows(ApiException.class,()->service.registrations("activity",other));
        verify(db,never()).list(anyString(),any());verifyNoInteractions(audits);
    }
    @Test void formerOrganizationManagerCannotExportEvenWhenStillRecordedAsCreator() {
        owned();when(classroom.requireClassManager("class",owner)).thenThrow(ApiException.forbidden("组织权限失效"));
        assertThrows(ApiException.class,()->service.tasks("activity",owner));verify(db,never()).list(anyString(),any());verifyNoInteractions(audits);
    }
    @Test void mismatchedActivityAndClassOrganizationsCannotExportPrivateRows() {
        owned();when(classroom.requireClassManager("class",owner)).thenReturn(Map.of("organization_id","another-org"));
        assertThrows(ApiException.class,()->service.registrations("activity",owner));verify(db,never()).list(anyString(),any());
    }
    @Test void participantCannotExportMissingRoster() {
        assertThrows(ApiException.class,()->service.missing("todo",student));verifyNoInteractions(db,classroom,audits);
    }
    @Test void taskExportUsesAccountIdentityAndOnlyOwnActivityDependencies() {
        owned();when(db.list(startsWith("SELECT t.id,t.title,t.description"),eq("activity"))).thenReturn(List.of(Map.of("id","task","title","任务","description","说明","deadline","tomorrow","priority","high","status","done","account_id","account","username","selected-user","student_no","S001","display_name","同名")));
        when(db.list(startsWith("SELECT d.task_id,d.depends_on_id"),eq("activity"))).thenReturn(List.of(Map.of("task_id","task","depends_on_id","prep","title","前置任务")));
        String csv=new String(service.tasks("activity",owner),StandardCharsets.UTF_8);assertTrue(csv.contains("selected-user"));assertTrue(csv.contains("S001"));assertTrue(csv.contains("prep：前置任务"));assertTrue(csv.contains("说明"));
        verify(db).list(contains("dep.activity_id=own.activity_id"),eq("activity"));
    }
    @Test void emptyAuthorizedExportsStillContainAUsableChineseHeader() {
        owned();String csv=new String(service.registrations("activity",owner),StandardCharsets.UTF_8);assertTrue(csv.startsWith("\uFEFF\"报名编号\""));assertEquals(1,csv.split("\r\n").length);
    }
}
