package com.activityassistant.service;

import com.activityassistant.api.ApiModels;
import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.mapper.ClassroomMapper;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class ClassroomSecurityTest {
    private final ClassroomMapper db = mock(ClassroomMapper.class);
    private final AuthService auth = mock(AuthService.class);
    private final AuditService audits = mock(AuditService.class);
    private final QqService qq = mock(QqService.class);
    private final ClassroomService service = new ClassroomService(
            db, auth, audits, mock(StorageService.class), qq, mock(AppProperties.class));
    private final AccountPrincipal manager = new AccountPrincipal(
            "manager-id", "manager", "Manager", "", "manager", "active", "2026-10-25", 0);
    private Map<String, Object> classroom;

    @BeforeEach
    void ownClass() {
        classroom = new LinkedHashMap<>();
        classroom.put("id", "class-id");
        classroom.put("name", "Class");
        classroom.put("manager_id", manager.id());
        when(db.findClass("class-id")).thenReturn(Optional.of(classroom));
        when(db.findMembership("class-id", manager.id()))
                .thenReturn(Optional.of(Map.of("organization_id", "org-id", "role", "owner")));
    }

    @Test
    void newClassDoesNotInheritAnyQqGroup() {
        when(auth.organizationOf(manager.id())).thenReturn("org-id");

        Map<String, Object> result = service.createClass(manager, "New class");

        assertThat(result).containsEntry("qq_group_bound", false);
        verifyNoInteractions(qq);
    }

    @Test
    void existingActiveParticipantCanOnlyBeReusedWithinItsOwnOrganization() {
        existing("student", "participant", "active");
        when(db.listOrganizations("participant-id"))
                .thenReturn(List.of(Map.of("organization_id", "org-id")));

        Map<String, Object> result = service.importRoster(manager, "class-id", List.of(member(" student ")));

        assertThat(result.get("count")).isEqualTo(1);
        assertThat(((List<?>) result.get("members")).get(0)).isEqualTo(Map.of(
                "id", "participant-id", "username", "student", "display_name", "Existing name", "student_no", "001"));
        verifyNoInteractions(auth);
        verify(db).addClassMember(eq("class-id"), eq("participant-id"), anyString());
        verify(db, never()).addParticipant(anyString(), anyString(), anyString());
    }

    @Test
    void importRejectsParticipantFromAnotherOrganizationWithoutWritingAnything() {
        existing("student", "participant", "active");
        when(db.listOrganizations("participant-id"))
                .thenReturn(List.of(Map.of("organization_id", "other-org")));

        assertImportForbidden(List.of(member("student")));
    }

    @Test
    void importRejectsExistingUnclaimedParticipant() {
        existing("student", "participant", "active");

        assertImportForbidden(List.of(member("student")));
    }

    @Test
    void importRejectsHistoricalParticipantLinkedToSeveralOrganizations() {
        existing("student", "participant", "active");
        when(db.listOrganizations("participant-id"))
                .thenReturn(List.of(Map.of("organization_id", "org-id"), Map.of("organization_id", "other-org")));

        assertImportForbidden(List.of(member("student")));
    }

    @Test
    void importRejectsDeletedParticipant() {
        existing("student", "participant", "deleted");

        assertImportForbidden(List.of(member("student")));
    }

    @Test
    void importCannotReuseManagerAccount() {
        existing("student", "manager", "active");

        assertImportForbidden(List.of(member("student")));
    }

    @Test
    void invalidLaterMemberPreventsCreatingEarlierNewAccount() {
        existing("z-other", "participant", "active");
        when(db.listOrganizations("participant-id"))
                .thenReturn(List.of(Map.of("organization_id", "other-org")));

        assertImportForbidden(List.of(member("a-new"), member("z-other")));
    }

    @Test
    void duplicateNormalizedUsernameRejectsTheWholeImport() {
        assertThatThrownBy(() -> service.importRoster(manager, "class-id",
                List.of(member("student"), member(" student "))))
                .isInstanceOf(ApiException.class);
        assertNoMapperWrites();
        verifyNoInteractions(auth, audits);
    }

    @Test
    void bindingListMasksHistoricalUnauthorizedGroup() {
        classroom.put("qq_group_openid", "foreign-group");
        when(qq.authorizedGroups("org-id")).thenReturn(List.of(Map.of(
                "group_openid", "own-group", "group_label", "Own group")));

        Map<String, Object> result = service.classQqBindings(manager, "class-id");

        assertThat(result).containsEntry("group_openid", null)
                .containsEntry("qq_group_bound", false).containsEntry("binding_needs_update", true);
        assertThat((List<?>) result.get("groups")).hasSize(1);
        assertNoMapperWrites();
    }

    @Test
    void classCanBeExplicitlyBoundAfterTheGroupWasConnected() {
        when(qq.isGroupAuthorized("org-id", "own-group")).thenReturn(true);

        Map<String, Object> result = service.bindClassGroup(manager, "class-id", " own-group ");

        assertThat(result).containsEntry("group_openid", "own-group").containsEntry("qq_group_bound", true);
        verify(db).updateGroup("own-group", "class-id");
        verify(audits).write(eq("class.qq_binding_update"), eq(manager.id()), eq("org-id"),
                eq("class"), eq("class-id"), any());
    }

    @Test
    void unauthorizedGroupCannotBeWrittenToAClass() {
        assertThatThrownBy(() -> service.bindClassGroup(manager, "class-id", "foreign-group"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("本组织");
        assertNoMapperWrites();
        verifyNoInteractions(audits);
    }

    @Test
    void classCanBeUnboundWithoutSendingAnyMessage() {
        classroom.put("qq_group_openid", "old-group");

        Map<String, Object> result = service.bindClassGroup(manager, "class-id", null);

        assertThat(result).containsEntry("group_openid", null).containsEntry("qq_group_bound", false);
        verify(db).updateGroup(null, "class-id");
        verifyNoInteractions(qq);
    }

    @Test
    void managerCannotReadOrUpdateOtherOrganizationsClassBindings() {
        when(db.findMembership("class-id", manager.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.classQqBindings(manager, "class-id")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.bindClassGroup(manager, "class-id", "group")).isInstanceOf(ApiException.class);
        assertNoMapperWrites();
        verifyNoInteractions(qq, audits);
    }

    @Test
    void historicalForeignGroupStopsTodoPublicationBeforeAnyInsert() {
        classroom.put("qq_group_openid", "foreign-group");

        assertThatThrownBy(() -> service.createTodo(manager, "class-id",
                new ApiModels.TodoCreateRequest("Homework", "", "2026-10-26T12:00:00", "homework")))
                .isInstanceOf(ApiException.class).hasMessageContaining("重新选择");
        assertNoMapperWrites();
        verify(qq, never()).sendGroup(anyString(), anyString(), anyString());
    }

    @Test
    void siteOnlyTodoDoesNotReadOrSendToTheBoundGroup() {
        classroom.put("qq_group_openid", "bound-group");
        when(db.listClassAccounts("class-id"))
                .thenReturn(List.of(Map.of("account_id", "student-id")));

        var result = service.createTodo(manager, "class-id",
                new ApiModels.TodoCreateRequest("Homework", "", "2026-11-13T12:00:00", "homework"), false);

        assertThat(result).containsEntry("assigned_count", 1);
        verify(db).assignTodo(eq(result.get("id")), eq("student-id"), anyString());
        verifyNoInteractions(qq);
    }

    private void existing(String username, String role, String status) {
        when(db.lockAccount(username))
                .thenReturn(Optional.of(Map.of("id", "participant-id", "username", username,
                        "role", role, "status", status, "display_name", "Existing name", "student_no", "001")));
    }

    private ApiModels.RosterMember member(String username) {
        return new ApiModels.RosterMember(username, "Input name", "999", "initial-password");
    }

    private void assertImportForbidden(List<ApiModels.RosterMember> members) {
        assertThatThrownBy(() -> service.importRoster(manager, "class-id", members))
                .isInstanceOf(ApiException.class);
        assertNoMapperWrites();
        verifyNoInteractions(auth, audits);
    }
    private void assertNoMapperWrites() {
        assertThat(mockingDetails(db).getInvocations()).noneMatch(invocation ->
                invocation.getMethod().isAnnotationPresent(org.apache.ibatis.annotations.Insert.class)
                || invocation.getMethod().isAnnotationPresent(org.apache.ibatis.annotations.Update.class));
    }
}
