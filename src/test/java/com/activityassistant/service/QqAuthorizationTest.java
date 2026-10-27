package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.integration.AiClient;
import com.activityassistant.integration.QqGatewayClient;
import com.activityassistant.persistence.Db;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QqAuthorizationTest {
    private final Db db = mock(Db.class);
    private final AiClient ai = mock(AiClient.class);
    private final QqGatewayClient gateway = mock(QqGatewayClient.class);
    private final AppProperties properties = mock(AppProperties.class);
    private final QqService service = new QqService(db, properties, gateway, ai);

    @Test
    void groupAuthorizationRequiresOrganizationAndActiveManagerBinding() {
        when(db.one(startsWith("SELECT 1 FROM qq_bindings qb"), eq("org-id"), eq("own-group")))
                .thenReturn(Optional.of(Map.of("ok", 1)));

        assertThat(service.isGroupAuthorized("org-id", "own-group")).isTrue();
        assertThat(service.isGroupAuthorized("other-org", "own-group")).isFalse();
        assertThat(service.isGroupAuthorized("", "own-group")).isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).one(sql.capture(), eq("org-id"), eq("own-group"));
        assertThat(sql.getValue()).contains("om.organization_id=?", "om.role IN ('owner','manager')",
                "owner.role='manager'", "owner.status='active'", "qb.status='active'", "qb.group_openid=?");
        verifyNoInteractions(ai, gateway);
    }

    @Test
    void authorizedGroupListIsScopedToTheSelectedOrganization() {
        when(db.list(startsWith("SELECT qb.group_openid"), eq("org-id")))
                .thenReturn(List.of(Map.of("group_openid", "own-group", "group_label", "Own group")));

        assertThat(service.authorizedGroups("org-id")).hasSize(1);
        assertThat(service.authorizedGroups("")).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), eq("org-id"));
        assertThat(sql.getValue()).contains("om.organization_id=?", "owner.status='active'", "qb.status='active'");
    }

    @Test
    void crossOrganizationConflictDoesNotConsumeBindingCode() {
        bindingCodeForActiveManager();
        when(db.update(startsWith("INSERT INTO qq_bindings"), any(Object[].class))).thenReturn(0);

        String reply = service.processInbound("already-owned-group", "sender", "Sender", "绑定123456", "m1", null);

        assertThat(reply).contains("无效");
        verify(db, never()).update("DELETE FROM qq_binding_codes WHERE code=?", "123456");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).update(sql.capture(), eq("already-owned-group"), eq("manager"), anyString(), anyString());
        assertThat(sql.getValue()).contains("ON CONFLICT (group_openid)", "WHERE qq_bindings.user_id=EXCLUDED.user_id",
                "new_om.organization_id=old_om.organization_id");
        verifyNoInteractions(ai, gateway);
    }

    @Test
    void successfulAuthorizedBindingConsumesCodeOnce() {
        bindingCodeForActiveManager();
        when(db.update(startsWith("INSERT INTO qq_bindings"), any(Object[].class))).thenReturn(1);

        String reply = service.processInbound("own-group", "sender", "Sender", "绑定123456", "m1", null);

        assertThat(reply).contains("绑定成功");
        verify(db).update("DELETE FROM qq_binding_codes WHERE code=?", "123456");
        verifyNoInteractions(ai, gateway);
    }

    @Test
    void participantCannotConsumeAGroupBindingCode() {
        when(db.one("SELECT user_id, expires_at FROM qq_binding_codes WHERE code=? FOR UPDATE", "123456"))
                .thenReturn(Optional.of(Map.of("user_id", "participant", "expires_at",
                        LocalDateTime.now().plusMinutes(5).toString())));

        String reply = service.processInbound("group", "sender", "Sender", "绑定123456", "m1", null);

        assertThat(reply).contains("无效");
        verify(db, never()).update(startsWith("INSERT INTO qq_bindings"), any(Object[].class));
        verifyNoInteractions(ai, gateway);
    }

    @Test
    void historicalUnauthorizedChannelDoesNotRevealActivityStatusOrRegistrations() {
        when(db.one(startsWith("SELECT a.id, a.title, a.status, ac.organization_id"), eq("foreign-group")))
                .thenReturn(Optional.of(Map.of("id", "private-activity", "title", "Private event",
                        "status", "ready", "organization_id", "other-org")));

        String status = service.processInbound("foreign-group", "sender", "Sender", "活动状态", "m1", null);
        String form = service.processInbound("foreign-group", "sender", "Sender", "报名链接", "m2", null);

        assertThat(status).doesNotContain("Private event").contains("还没有活动");
        assertThat(form).contains("没有可用");
        verify(db, never()).list(startsWith("SELECT status, COUNT(*)"), any(Object[].class));
        verify(db, never()).one(startsWith("SELECT f.id"), any(Object[].class));
        verifyNoInteractions(ai, gateway);
    }

    @Test
    void unboundGroupGetsGeneralHelpWithoutAiOrPrivateRag() {
        String reply = service.processInbound("unbound-group", "sender", "Sender", "活动有什么注意事项", "m1", null);

        assertThat(reply).contains("暂无可访问").contains("帮助");
        verifyNoInteractions(ai, gateway);
    }

    @Test
    void replyUsesOneAuthorizedActivityForBothOrganizationAndContext() {
        when(db.one(startsWith("SELECT a.id, a.title, a.status, ac.organization_id"), eq("own-group")))
                .thenReturn(Optional.of(Map.of("id", "own-activity", "title", "Own event",
                        "status", "ready", "organization_id", "org-id")));
        when(db.one(startsWith("SELECT 1 FROM qq_bindings qb"), eq("org-id"), eq("own-group")))
                .thenReturn(Optional.of(Map.of("ok", 1)));
        when(ai.reply(eq("活动有什么注意事项"), anyString(), eq("org-id"))).thenReturn("Safe reply");

        assertThat(service.processInbound("own-group", "sender", "Sender", "活动有什么注意事项", "m1", null))
                .isEqualTo("Safe reply");

        verify(ai).reply(eq("活动有什么注意事项"), contains("Own event"), eq("org-id"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, times(1)).one(sql.capture(), eq("own-group"));
        assertThat(sql.getValue()).contains("om.organization_id=ac.organization_id",
                "binding_owner.status='active'", "qb.status='active'");
        verifyNoInteractions(gateway);
    }

    @Test
    void managerWithAnExistingGroupStillReceivesCodeForAnotherGroup() {
        when(properties.qqConfigured()).thenReturn(true);
        when(properties.publicBaseUrl()).thenReturn("http://localhost/");
        when(gateway.healthy()).thenReturn(true);
        List<Map<String, Object>> groups = List.of(Map.of("group_openid", "first-group", "group_label", "First group"));
        when(db.list(startsWith("SELECT group_openid, group_label, status, last_seen_at"), eq("manager")))
                .thenReturn(groups);
        when(db.one(startsWith("SELECT 1 FROM accounts a JOIN organization_members om"), eq("manager")))
                .thenReturn(Optional.of(Map.of("ok", 1)));
        when(db.one("SELECT code, expires_at FROM qq_binding_codes WHERE user_id=? ORDER BY created_at DESC LIMIT 1", "manager"))
                .thenReturn(Optional.of(Map.of("code", "123456", "expires_at", LocalDateTime.now().plusMinutes(5).toString())));

        Map<String, Object> status = service.status("manager");

        assertThat(status).containsEntry("binding_code", "123456").containsEntry("groups", groups);
        verify(gateway).healthy();
        verify(db, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(ai);
    }

    @Test
    void bindingCodeIsNotOfferedWithoutActiveOrganizationManagerPermission() {
        when(properties.qqConfigured()).thenReturn(true);
        when(properties.publicBaseUrl()).thenReturn("http://localhost");
        when(gateway.healthy()).thenReturn(false);

        Map<String, Object> status = service.status("participant");

        assertThat(status).containsEntry("binding_code", null);
        verify(db, never()).one(startsWith("SELECT code, expires_at FROM qq_binding_codes"), any(Object[].class));
        verify(db, never()).update(anyString(), any(Object[].class));
        verify(gateway).healthy();
        verifyNoInteractions(ai);
    }

    private void bindingCodeForActiveManager() {
        when(db.one("SELECT user_id, expires_at FROM qq_binding_codes WHERE code=? FOR UPDATE", "123456"))
                .thenReturn(Optional.of(Map.of("user_id", "manager", "expires_at",
                        LocalDateTime.now().plusMinutes(5).toString())));
        when(db.one(startsWith("SELECT 1 FROM accounts a JOIN organization_members om"), eq("manager")))
                .thenReturn(Optional.of(Map.of("ok", 1)));
    }
}
