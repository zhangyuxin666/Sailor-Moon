package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.mapper.TemplateMapper;
import com.activityassistant.persistence.mapper.ActivityGateMapper;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemplateServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final AccountPrincipal manager=new AccountPrincipal("manager-id","manager","Manager","","manager","active","now",0);
    final AccountPrincipal participant=new AccountPrincipal("student-id","student","Student","","participant","active","now",0);
    JsonNode tree(String json)throws Exception{return mapper.readTree(json);}
    String structuralSource(){return """
        {"id":"PRIVATE-ACTIVITY","intent_type":"activity","title":"PRIVATE-NAME","description":"PRIVATE-ANSWER","event_time":"2000-01-01T00:00:00Z","deadline":"2000-01-01T00:00:00Z",
         "raw_input":"PRIVATE-RAW","input":{"answers":{"q":"PRIVATE-ANSWER"}},"generation":{"sources":[{"id":"PRIVATE-SOURCE"}]},"group_openid":"PRIVATE-GROUP",
         "workflow":{"create_form":true,"assign_tasks":true,"create_calendar":true,"publish_message":true},
         "form_fields":[{"name":"private_field","label":"PRIVATE-NAME","type":"select","options":["PRIVATE-CONTACT"]}],
         "execution_plan":{"materials":["PRIVATE-NAME"],"tasks":[{"key":"PRIVATE-TASK-1","title":"PRIVATE-NAME","description":"PRIVATE-CONTACT","account_id":"PRIVATE-OWNER","assignee":"PRIVATE-NAME","deadline":"2000-01-01T00:00:00Z"},{"key":"PRIVATE-TASK-2","title":"PRIVATE-ANSWER","depends_on":["PRIVATE-TASK-1"]}],"generation":{"sources":[{"id":"PRIVATE-SOURCE"}]}}}
        """;}
    @Test void recordImportRetainsTopologyWithoutPrivateFreeTextOrOldReferences()throws Exception{
        JsonNode result=TemplateService.portable(tree(structuralSource()),mapper,true,"Reusable meeting");
        assertFalse(result.toString().contains("PRIVATE"));assertFalse(result.toString().contains("2000-01-01"));
        assertEquals("Reusable meeting",result.path("title").asText());assertEquals("",result.path("event_time").asText());assertFalse(result.path("workflow").path("publish_message").asBoolean());
        JsonNode tasks=result.path("execution_plan").path("tasks");assertEquals(2,tasks.size());assertEquals("task_1",tasks.get(1).path("depends_on").get(0).asText());
        assertEquals("",tasks.get(0).path("account_id").asText());assertEquals("待分配",tasks.get(0).path("assignee").asText());assertEquals("任务1",tasks.get(0).path("title").asText());
        assertEquals(0,result.path("execution_plan").path("materials").size());assertEquals("问题1",result.path("form_fields").get(2).path("label").asText());
    }
    @Test void explicitTemplateContentKeepsUsefulInstructionsButNotIdentityOrTime()throws Exception{
        JsonNode input=tree("""
            {"intent_type":"activity","title":"Meeting","description":"Confirm venue","workflow":{"assign_tasks":true,"publish_message":true},"execution_plan":{"materials":["Water"],"tasks":[{"key":"old-a","id":"old-id","title":"Prepare","account_id":"old-person","assignee":"Old Name","deadline":"2000-01-01T00:00:00Z","priority":"high"},{"key":"old-b","title":"Host","depends_on":["old-a"]}]}}
            """);
        JsonNode clean=TemplateService.portable(input,mapper,false,null);
        assertEquals("Confirm venue",clean.path("description").asText());assertEquals("Prepare",clean.path("execution_plan").path("tasks").get(0).path("title").asText());
        assertEquals("high",clean.path("execution_plan").path("tasks").get(0).path("priority").asText());assertEquals("Water",clean.path("execution_plan").path("materials").get(0).asText());
        assertFalse(clean.toString().contains("old-person"));assertFalse(clean.toString().contains("old-id"));assertFalse(clean.toString().contains("2000-01-01"));
    }
    @Test void cyclicDependenciesAreRejected()throws Exception{
        JsonNode input=tree("""
            {"intent_type":"activity","title":"Cycle","workflow":{},"execution_plan":{"tasks":[{"key":"a","title":"a","depends_on":["b"]},{"key":"b","title":"b","depends_on":["a"]}]}}
            """);assertThrows(ApiException.class,()->TemplateService.portable(input,mapper,false,null));
    }
    @Test void missingDependencyCannotBeSilentlyDropped()throws Exception{
        JsonNode input=tree("""
            {"intent_type":"activity","title":"Missing","workflow":{},"execution_plan":{"tasks":[{"key":"a","title":"a","depends_on":["foreign-task"]}]}}
            """);assertThrows(ApiException.class,()->TemplateService.portable(input,mapper,false,null));
    }
    @Test void malformedWorkflowAndExcessiveReminderAreRejected()throws Exception{
        JsonNode invalid=tree("{\"intent_type\":\"activity\",\"title\":\"Invalid\",\"workflow\":{\"assign_tasks\":\"true\"}}");
        assertThrows(ApiException.class,()->TemplateService.portable(invalid,mapper,false,null));
        JsonNode tooLong=tree("{\"intent_type\":\"activity\",\"title\":\"Invalid\",\"execution_plan\":{\"remind_minutes_before\":10081}}");assertThrows(ApiException.class,()->TemplateService.portable(tooLong,mapper,false,null));
    }
    Fixture fixture(){return new Fixture();}
    class Fixture {
        final MemoryDb db=new MemoryDb();final AuthService auth=mock(AuthService.class);final ClassroomService classroom=mock(ClassroomService.class);final AuditService audit=mock(AuditService.class);
        final ActivityGate gate=new ActivityGate(mock(ActivityGateMapper.class),mapper,mock(AppProperties.class),audit,mock(BackgroundJobService.class));
        final TemplateService service=new TemplateService(db,auth,classroom,gate,audit,mapper);
        Fixture(){when(auth.organizationOf(manager.id())).thenReturn("org-one");when(classroom.requireClassManager("class-one",manager)).thenReturn(Map.of("organization_id","org-one"));}
        JsonNode apply(String request)throws Exception{return tree("{\"class_id\":\"class-one\",\"request_id\":\""+request+"\"}");}
    }
    @Test void builtinScenariosHaveBlankDatesAndNoPriorAssignees(){
        var f=fixture();var values=f.service.list(manager);assertEquals(3,values.size());
        assertTrue(values.stream().anyMatch(row->row.get("name").equals("秋游")));assertTrue(values.stream().anyMatch(row->row.get("name").equals("作业收集")));
        for(var row:values){JsonNode plan=(JsonNode)row.get("blueprint");assertEquals("",plan.path("event_time").asText());assertEquals("",plan.path("deadline").asText());assertEquals(false,row.get("editable"));for(var task:plan.path("execution_plan").path("tasks"))assertEquals("",task.path("account_id").asText());}
    }
    @Test void everyNewApplicationCreatesFreshDraftButSameRequestReplays()throws Exception{
        var f=fixture();var first=f.service.apply(manager,"builtin-class-meeting",f.apply("request-one"));var retry=f.service.apply(manager,"builtin-class-meeting",f.apply("request-one"));var next=f.service.apply(manager,"builtin-class-meeting",f.apply("request-two"));
        assertEquals(first.get("draft_id"),retry.get("draft_id"));assertEquals(true,retry.get("idempotent"));assertNotEquals(first.get("draft_id"),next.get("draft_id"));assertEquals(2,f.db.drafts.size());
        for(var draft:f.db.drafts.values()){assertEquals("draft",draft.get("status"));JsonNode body=mapper.readTree(String.valueOf(draft.get("draft_json")));assertEquals("",body.path("event_time").asText());assertFalse(body.path("workflow").path("publish_message").asBoolean());assertEquals("template",body.path("generation").path("fallback_reason").asText());}
    }
    @Test void requestCannotBeReusedForDifferentTemplate()throws Exception{
        var f=fixture();f.service.apply(manager,"builtin-class-meeting",f.apply("request-one"));ApiException error=assertThrows(ApiException.class,()->f.service.apply(manager,"builtin-autumn-trip",f.apply("request-one")));assertEquals(409,error.status().value());assertEquals(1,f.db.drafts.size());
    }
    @Test void applyingToAnotherOrganizationIsRejectedBeforeNewRows()throws Exception{
        var f=fixture();when(f.classroom.requireClassManager("class-one",manager)).thenReturn(Map.of("organization_id","org-two"));assertThrows(ApiException.class,()->f.service.apply(manager,"builtin-class-meeting",f.apply("request-one")));assertTrue(f.db.drafts.isEmpty());assertTrue(f.db.applications.isEmpty());
    }
    @Test void participantCannotUseTemplatesAndBuiltinsCannotBeMutated()throws Exception{
        var f=fixture();assertThrows(ApiException.class,()->f.service.list(participant));assertThrows(ApiException.class,()->f.service.update(manager,"builtin-class-meeting",tree("{}")));assertThrows(ApiException.class,()->f.service.delete(manager,"builtin-class-meeting",1));assertTrue(f.db.drafts.isEmpty());
    }
    @Test void currentOrganizationDemotionRejectsStaleGlobalManagerOnEveryTemplateEndpoint()throws Exception{
        var f=fixture();f.db.membershipRole="owner";assertEquals(3,f.service.list(manager).size());
        JsonNode content=tree("{}");
        for(String role:Arrays.asList("student",null)){
            f.db.membershipRole=role;
            List<org.junit.jupiter.api.function.Executable> operations=List.of(
                ()->f.service.list(manager),()->f.service.get(manager,"builtin-class-meeting"),()->f.service.sources(manager),
                ()->f.service.create(manager,content),()->f.service.update(manager,"own-template",content),
                ()->f.service.delete(manager,"own-template",1),()->f.service.apply(manager,"builtin-class-meeting",f.apply("request-one"))
            );
            for(var operation:operations)assertEquals(403,assertThrows(ApiException.class,operation).status().value());
        }
        assertTrue(f.db.drafts.isEmpty());assertTrue(f.db.applications.isEmpty());assertEquals(0,f.db.sourceQueries);
        verifyNoInteractions(f.classroom,f.audit);
    }
    @Test void foreignTemplateAndAnotherCreatorsSourceAreNotExposed()throws Exception{
        var f=fixture();assertThrows(ApiException.class,()->f.service.get(manager,"foreign-template"));JsonNode input=tree("{\"name\":\"No copy\",\"source_draft_id\":\"foreign-draft\"}");assertThrows(ApiException.class,()->f.service.create(manager,input));assertTrue(f.db.drafts.isEmpty());
    }
    @Test void importChoicesAreCreatorScopedAndDoNotExposeStoredDraftBodies()throws Exception{
        var f=fixture();
        f.db.sourceDrafts=List.of(Map.of("id","own-draft","status","draft","draft_json",structuralSource()));
        f.db.sourceActivities=List.of(Map.of("id","own-activity","title","My meeting","status","ready"));
        var choices=f.service.sources(manager);
        JsonNode response=mapper.valueToTree(choices);
        assertEquals("own-draft",response.path("drafts").get(0).path("id").asText());
        assertEquals(Set.of("id","title","status"),((Map<?,?>)((List<?>)choices.get("drafts")).get(0)).keySet());
        assertFalse(response.toString().contains("PRIVATE-ANSWER"));assertFalse(response.toString().contains("PRIVATE-OWNER"));
        assertEquals("own-activity",response.path("activities").get(0).path("id").asText());
        assertEquals(2,f.db.sourceQueries);
    }
    @Test void participantCannotEnumerateImportChoices(){
        var f=fixture();assertThrows(ApiException.class,()->f.service.sources(participant));assertEquals(0,f.db.sourceQueries);
    }
    @Test void importedPlaceholdersCanBeReplacedWithReusableTaskAndQuestionText()throws Exception{
        var draft=TemplateService.portable(tree(structuralSource()),mapper,true,"Meeting");
        ((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("execution_plan").path("tasks").get(0)).put("title","Prepare agenda");
        var question=(com.fasterxml.jackson.databind.node.ObjectNode)draft.path("form_fields").get(2);
        question.put("label","Transport preference");question.putArray("options").add("Shared coach").add("Own transport");
        JsonNode saved=TemplateService.portable(draft,mapper,false,null);
        assertEquals("Prepare agenda",saved.path("execution_plan").path("tasks").get(0).path("title").asText());
        assertEquals("Transport preference",saved.path("form_fields").get(2).path("label").asText());
        assertEquals("Shared coach",saved.path("form_fields").get(2).path("options").get(0).asText());assertFalse(saved.toString().contains("PRIVATE"));
    }
    static class MemoryDb implements TemplateMapper {
        final Map<String,Map<String,Object>> applications=new LinkedHashMap<>(),drafts=new LinkedHashMap<>();
        List<Map<String,Object>> sourceDrafts=List.of(),sourceActivities=List.of();int sourceQueries;String membershipRole="manager";
        @Override public Map<String, Object> findMembershipRow(String organizationId, String accountId) {
            assertEquals("org-one", organizationId); assertEquals("manager-id", accountId);
            return membershipRole == null ? null : Map.of("role", membershipRole);
        }

        @Override public List<Map<String, Object>> listTemplates(String organizationId) {
            return List.of();
        }

        @Override public Map<String, Object> findTemplateRow(String id, String organizationId) {
            return null;
        }

        @Override public List<Map<String, Object>> listSourceDrafts(String organizationId, String creatorId) {
            sourceQueries++; assertEquals("org-one", organizationId); assertEquals("manager-id", creatorId);
            return sourceDrafts;
        }

        @Override public List<Map<String, Object>> listSourceActivities(String organizationId, String username) {
            sourceQueries++; assertEquals("org-one", organizationId); assertEquals("manager", username);
            return sourceActivities;
        }

        @Override public Map<String, Object> findSourceDraftRow(String id, String organizationId, String creatorId) {
            return null;
        }

        @Override public Map<String, Object> findActivityScopeRow(String activityId) {
            throw new AssertionError("Unexpected call: findActivityScope");
        }

        @Override public Map<String, Object> findActivityPlan(String activityId) {
            throw new AssertionError("Unexpected call: findActivityPlan");
        }

        @Override public Map<String, Object> findWorkflowRow(String activityId) {
            throw new AssertionError("Unexpected call: findWorkflow");
        }

        @Override public List<Map<String, Object>> listSourceTasks(String activityId) {
            throw new AssertionError("Unexpected call: listSourceTasks");
        }

        @Override public List<Map<String, Object>> listDependencies(Object taskId) {
            throw new AssertionError("Unexpected call: listDependencies");
        }

        @Override public void insertTemplate(String id, String organizationId, String creatorId, String name, String description, String blueprint, String now, String updatedAt) {
            throw new AssertionError("Unexpected call: insertTemplate");
        }

        @Override public Map<String, Object> lockTemplateRow(String id, String organizationId) {
            throw new AssertionError("Unexpected call: lockTemplate");
        }

        @Override public void updateTemplate(String name, String description, String blueprint, String updatedAt, String id) {
            throw new AssertionError("Unexpected call: updateTemplate");
        }

        @Override public void deleteTemplate(String id) {
            throw new AssertionError("Unexpected call: deleteTemplate");
        }

        @Override public Map<String, Object> findApplicationRow(String accountId, String requestId) {
            return applications.get(accountId + ":" + requestId);
        }

        @Override public int insertApplication(String accountId, String requestId, String templateId, String classId, String hash, String draftId, String now) {
            String key = accountId + ":" + requestId;
            if (applications.containsKey(key)) return 0;
            applications.put(key, Map.of("account_id", accountId, "request_id", requestId,
                    "template_id", templateId, "class_id", classId, "payload_hash", hash, "draft_id", draftId));
            return 1;
        }

        @Override public void insertDraft(String id, String organizationId, String classId, String creatorId, String rawInput, String intent, String complexity, String draft, String now, String updatedAt) {
            drafts.put(id, Map.of("id", id, "draft_json", draft, "status", "draft"));
        }
    }
}
