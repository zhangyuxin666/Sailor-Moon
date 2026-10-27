package com.activityassistant.service;

import com.activityassistant.api.ApiModels;
import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.Db;
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
    private static final String CLASS_QUERY = "SELECT * FROM classes WHERE id=?";
    private static final String MEMBERSHIP_QUERY = "SELECT co.organization_id, om.role FROM class_organizations co " +
            "JOIN organization_members om ON om.organization_id=co.organization_id WHERE co.class_id=? AND om.account_id=?";
    private final Db db = mock(Db.class);
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
        when(db.one(CLASS_QUERY, "class-id")).thenReturn(Optional.of(classroom));
        when(db.one(MEMBERSHIP_QUERY, "class-id", manager.id()))
                .thenReturn(Optional.of(Map.of("organization_id", "org-id", "role", "owner")));
    }

    @Test
    void newClassDoesNotInheritAnyQqGroup() {
        when(auth.organizationOf(manager.id())).thenReturn("org-id");

        Map<String, Object> result = service.createClass(manager, "New class");

        assertThat(result).containsEntry("qq_group_bound", false);
        verifyNoInteractions(qq);
        verify(db, never()).list(startsWith("SELECT group_openid FROM qq_bindings"), any(Object[].class));
    }

    @Test
    void existingActiveParticipantCanOnlyBeReusedWithinItsOwnOrganization() {
        existing("student", "participant", "active");
        when(db.list("SELECT organization_id FROM organization_members WHERE account_id=?", "participant-id"))
                .thenReturn(List.of(Map.of("organization_id", "org-id")));

        Map<String, Object> result = service.importRoster(manager, "class-id", List.of(member(" student ")));

        assertThat(result.get("count")).isEqualTo(1);
        assertThat(((List<?>) result.get("members")).get(0)).isEqualTo(Map.of(
                "id", "participant-id", "username", "student", "display_name", "Existing name", "student_no", "001"));
        verifyNoInteractions(auth);
        verify(db).update(eq("INSERT INTO class_members (class_id, account_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"),
                eq("class-id"), eq("participant-id"), anyString());
        verify(db, never()).update(startsWith("INSERT INTO organization_members"), any(Object[].class));
    }

    @Test
    void importRejectsParticipantFromAnotherOrganizationWithoutWritingAnything() {
        existing("student", "participant", "active");
        when(db.list("SELECT organization_id FROM organization_members WHERE account_id=?", "participant-id"))
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
        when(db.list("SELECT organization_id FROM organization_members WHERE account_id=?", "participant-id"))
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
        when(db.list("SELECT organization_id FROM organization_members WHERE account_id=?", "participant-id"))
                .thenReturn(List.of(Map.of("organization_id", "other-org")));

        assertImportForbidden(List.of(member("a-new"), member("z-other")));
    }

    @Test
    void duplicateNormalizedUsernameRejectsTheWholeImport() {
        assertThatThrownBy(() -> service.importRoster(manager, "class-id",
                List.of(member("student"), member(" student "))))
                .isInstanceOf(ApiException.class);
        verify(db, never()).update(anyString(), any(Object[].class));
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
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void classCanBeExplicitlyBoundAfterTheGroupWasConnected() {
        when(qq.isGroupAuthorized("org-id", "own-group")).thenReturn(true);

        Map<String, Object> result = service.bindClassGroup(manager, "class-id", " own-group ");

        assertThat(result).containsEntry("group_openid", "own-group").containsEntry("qq_group_bound", true);
        verify(db).update("UPDATE classes SET qq_group_openid=? WHERE id=?", "own-group", "class-id");
        verify(audits).write(eq("class.qq_binding_update"), eq(manager.id()), eq("org-id"),
                eq("class"), eq("class-id"), any());
    }

    @Test
    void unauthorizedGroupCannotBeWrittenToAClass() {
        assertThatThrownBy(() -> service.bindClassGroup(manager, "class-id", "foreign-group"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("本组织");
        verify(db, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(audits);
    }

    @Test
    void classCanBeUnboundWithoutSendingAnyMessage() {
        classroom.put("qq_group_openid", "old-group");

        Map<String, Object> result = service.bindClassGroup(manager, "class-id", null);

        assertThat(result).containsEntry("group_openid", null).containsEntry("qq_group_bound", false);
        verify(db).update("UPDATE classes SET qq_group_openid=? WHERE id=?", null, "class-id");
        verifyNoInteractions(qq);
    }

    @Test
    void managerCannotReadOrUpdateOtherOrganizationsClassBindings() {
        when(db.one(MEMBERSHIP_QUERY, "class-id", manager.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.classQqBindings(manager, "class-id")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.bindClassGroup(manager, "class-id", "group")).isInstanceOf(ApiException.class);
        verify(db, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(qq, audits);
    }

    @Test
    void historicalForeignGroupStopsTodoPublicationBeforeAnyInsert() {
        classroom.put("qq_group_openid", "foreign-group");

        assertThatThrownBy(() -> service.createTodo(manager, "class-id",
                new ApiModels.TodoCreateRequest("Homework", "", "2026-10-26T12:00:00", "homework")))
                .isInstanceOf(ApiException.class).hasMessageContaining("重新选择");
        verify(db, never()).update(anyString(), any(Object[].class));
        verify(qq, never()).sendGroup(anyString(), anyString(), anyString());
    }

    private void existing(String username, String role, String status) {
        when(db.one("SELECT * FROM accounts WHERE username=? FOR UPDATE", username))
                .thenReturn(Optional.of(Map.of("id", "participant-id", "username", username,
                        "role", role, "status", status, "display_name", "Existing name", "student_no", "001")));
    }

    private ApiModels.RosterMember member(String username) {
        return new ApiModels.RosterMember(username, "Input name", "999", "initial-password");
    }

    private void assertImportForbidden(List<ApiModels.RosterMember> members) {
        assertThatThrownBy(() -> service.importRoster(manager, "class-id", members))
                .isInstanceOf(ApiException.class);
        verify(db, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(auth, audits);
    }
}
