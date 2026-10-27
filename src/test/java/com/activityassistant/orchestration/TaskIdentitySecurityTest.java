package com.activityassistant.orchestration;

import com.activityassistant.persistence.Db;
import com.activityassistant.service.QqService;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskIdentitySecurityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Db db = mock(Db.class);
    private final List<Map<String, Object>> sameNames = List.of(
            Map.of("account_id", "a1", "display_name", "同名"),
            Map.of("account_id", "a2", "display_name", "同名"));
    private final ActivityOrchestrator orchestrator =
            new ActivityOrchestrator(db, null, null, null, mapper, null, null, null);

    @Test
    void duplicateDisplayNamesKeepTheSelectedAccountId() throws Exception {
        JsonNode plan = plan("a2", "同名");
        JsonNode result = orchestrator.normalizeTaskIdentities("event", plan, sameNames, false);
        assertThat(result.path("tasks").get(0).path("account_id").asText()).isEqualTo("a2");
        verifyNoInteractions(db);
    }

    @Test
    void renamingAnAccountOnlyChangesItsDisplayName() throws Exception {
        JsonNode result = orchestrator.normalizeTaskIdentities("event", plan("a1", "旧姓名"),
                List.of(Map.of("account_id", "a1", "display_name", "新姓名")), false);
        assertThat(result.path("tasks").get(0).path("account_id").asText()).isEqualTo("a1");
        assertThat(result.path("tasks").get(0).path("assignee").asText()).isEqualTo("新姓名");
    }

    @Test
    void aDisplayNameCannotSubstituteForAnAccountId() throws Exception {
        JsonNode plan = plan("", "同名");
        JsonNode result=orchestrator.normalizeTaskIdentities("event",plan,sameNames,false);
        assertThat(result.path("tasks").get(0).path("account_id").asText()).isEmpty();
        assertThat(result.path("tasks").get(0).path("assignee").asText()).isEqualTo("待分配");
        verifyNoInteractions(db);
    }

    @Test
    void anotherClassOrDeletedAccountCannotBeSelectedByName() throws Exception {
        JsonNode plan = plan("outside", "同名");
        JsonNode result=orchestrator.normalizeTaskIdentities("event",plan,sameNames,false);
        assertThat(result.path("tasks").get(0).path("account_id").asText()).isEmpty();
    }

    @Test
    void legacyPlansReusePersistedIdentityWithoutInferringANewOwner() throws Exception {
        when(db.list(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("account_id", "a2")));
        JsonNode result = orchestrator.normalizeTaskIdentities("event", plan("", "旧姓名"), sameNames, true);
        assertThat(result.path("tasks").get(0).path("account_id").asText()).isEqualTo("a2");
        assertThat(result.path("tasks").get(0).path("assignee").asText()).isEqualTo("同名");
    }

    @Test
    void legacyPlansWithoutAnAuthoritativeMappingRequireReconfirmation() throws Exception {
        when(db.list(anyString(), any(Object[].class))).thenReturn(List.of());
        JsonNode result=orchestrator.normalizeTaskIdentities("event",plan("", "同名"),sameNames,true);
        assertThat(result.path("tasks").get(0).path("account_id").asText()).isEmpty();
        assertThat(result.path("tasks").get(0).path("title").asText()).isEqualTo("准备场地");
    }

    @Test
    void ambiguousLegacyMappingsAreNeverResolvedByTakingTheFirstRow() throws Exception {
        when(db.list(anyString(), any(Object[].class))).thenReturn(
                List.of(Map.of("account_id", "a1"), Map.of("account_id", "a2")));
        JsonNode result=orchestrator.normalizeTaskIdentities("event",plan("", "同名"),sameNames,true);
        assertThat(result.path("tasks").get(0).path("account_id").asText()).isEmpty();
        assertThat(result.path("tasks").get(0).path("title").asText()).isEqualTo("准备场地");
    }

    @Test
    void normalizationDoesNotMutateTheOriginalPlan() throws Exception {
        JsonNode original = plan("a1", "模型姓名");
        orchestrator.normalizeTaskIdentities("event", original, sameNames, false);
        assertThat(original.path("tasks").get(0).path("assignee").asText()).isEqualTo("模型姓名");
    }

    @Test
    void revokedOrCrossOrganizationChannelsCannotSend() {
        QqService qq = mock(QqService.class);
        ExecutionJournal journal = mock(ExecutionJournal.class);
        when(journal.find("key", "send_qq_group_message")).thenReturn(null);
        when(db.one(anyString(), any(Object[].class))).thenReturn(
                Optional.of(Map.of("organization_id", "org")));
        when(qq.isGroupAuthorized("org", "foreign-group")).thenReturn(false);
        ActivityOrchestrator guarded = new ActivityOrchestrator(db, null, null, qq, mapper, null, journal, null);
        assertThatThrownBy(() -> guarded.sendGroupMessage("event", "foreign-group", "private", "manual", "key"))
                .isInstanceOf(ApiException.class);
        verify(qq, never()).sendGroup(anyString(), anyString(), anyString());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    private JsonNode plan(String id, String name) throws Exception {
        return mapper.valueToTree(Map.of("tasks",
                List.of(Map.of("title", "准备场地", "account_id", id, "assignee", name))));
    }
}
