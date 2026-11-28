package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.integration.AiClient;
import com.activityassistant.integration.QqGatewayClient;
import com.activityassistant.persistence.mapper.QqMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QqAuthorizationTest {
    private final QqMapper db = mock(QqMapper.class);
    private final AiClient ai = mock(AiClient.class);
    private final QqGatewayClient gateway = mock(QqGatewayClient.class);
    private final AppProperties properties = mock(AppProperties.class);
    private final QqService service = new QqService(db, properties, gateway, ai);

    @Test
    void groupAuthorizationRequiresOrganizationAndActiveManagerBinding() {
        when(db.findAuthorization("org-id", "own-group"))
                .thenReturn(Optional.of(Map.of("ok", 1)));

        assertThat(service.isGroupAuthorized("org-id", "own-group")).isTrue();
        assertThat(service.isGroupAuthorized("other-org", "own-group")).isFalse();
        assertThat(service.isGroupAuthorized("", "own-group")).isFalse();

        verify(db).findAuthorization("org-id", "own-group");
        assertThat(sql("findAuthorizationRow", 2)).contains("om.organization_id=#{organizationId}", "om.role IN ('owner','manager')",
                "owner.role='manager'", "owner.status='active'", "qb.status='active'", "qb.group_openid=#{groupId}");
        verifyNoInteractions(ai, gateway);
    }

    @Test
    void authorizedGroupListIsScopedToTheSelectedOrganization() {
        when(db.listAuthorizedGroups("org-id"))
                .thenReturn(List.of(Map.of("group_openid", "own-group", "group_label", "Own group")));

        assertThat(service.authorizedGroups("org-id")).hasSize(1);
        assertThat(service.authorizedGroups("")).isEmpty();
        verify(db).listAuthorizedGroups("org-id");
        assertThat(sql("listAuthorizedGroups", 1)).contains("om.organization_id=#{organizationId}", "owner.status='active'", "qb.status='active'");
    }

    @Test
    void crossOrganizationConflictDoesNotConsumeBindingCode() {
        bindingCodeForActiveManager();
        when(db.bindGroup(anyString(), anyString(), anyString(), anyString())).thenReturn(0);

        String reply = service.processInbound("already-owned-group", "sender", "Sender", "绑定123456", "m1", null);

        assertThat(reply).contains("无效");
        verify(db, never()).consumeCode("123456");
        verify(db).bindGroup(eq("already-owned-group"), eq("manager"), anyString(), anyString());
        assertThat(sql("bindGroup", 4)).contains("ON CONFLICT (group_openid)", "WHERE qq_bindings.user_id=EXCLUDED.user_id",
                "new_om.organization_id=old_om.organization_id");
        verifyNoInteractions(ai, gateway);
    }

    @Test
    void successfulAuthorizedBindingConsumesCodeOnce() {
        bindingCodeForActiveManager();
        when(db.bindGroup(anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        String reply = service.processInbound("own-group", "sender", "Sender", "绑定123456", "m1", null);

        assertThat(reply).contains("绑定成功");
        verify(db).consumeCode("123456");
        verifyNoInteractions(ai, gateway);
    }

    @Test
    void participantCannotConsumeAGroupBindingCode() {
        when(db.lockCode("123456"))
                .thenReturn(Optional.of(Map.of("user_id", "participant", "expires_at",
                        LocalDateTime.now().plusMinutes(5).toString())));

        String reply = service.processInbound("group", "sender", "Sender", "绑定123456", "m1", null);

        assertThat(reply).contains("无效");
        verify(db, never()).bindGroup(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(ai, gateway);
    }

    @Test
    void historicalUnauthorizedChannelDoesNotRevealActivityStatusOrRegistrations() {
        when(db.findLatestActivity("foreign-group"))
                .thenReturn(Optional.of(Map.of("id", "private-activity", "title", "Private event",
                        "status", "ready", "organization_id", "other-org")));

        String status = service.processInbound("foreign-group", "sender", "Sender", "活动状态", "m1", null);
        String form = service.processInbound("foreign-group", "sender", "Sender", "报名链接", "m2", null);

        assertThat(status).doesNotContain("Private event").contains("还没有活动");
        assertThat(form).contains("没有可用");
        verify(db, never()).listTaskCounts(any());
        verify(db, never()).findForm(any());
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
        when(db.findLatestActivity("own-group"))
                .thenReturn(Optional.of(Map.of("id", "own-activity", "title", "Own event",
                        "status", "ready", "organization_id", "org-id")));
        when(db.findAuthorization("org-id", "own-group"))
                .thenReturn(Optional.of(Map.of("ok", 1)));
        when(ai.reply(eq("活动有什么注意事项"), anyString(), eq("org-id"))).thenReturn("Safe reply");

        assertThat(service.processInbound("own-group", "sender", "Sender", "活动有什么注意事项", "m1", null))
                .isEqualTo("Safe reply");

        verify(ai).reply(eq("活动有什么注意事项"), contains("Own event"), eq("org-id"));
        verify(db).findLatestActivity("own-group");
        assertThat(sql("findLatestActivityRow", 1)).contains("om.organization_id=ac.organization_id",
                "binding_owner.status='active'", "qb.status='active'");
        verifyNoInteractions(gateway);
    }

    @Test
    void managerWithAnExistingGroupStillReceivesCodeForAnotherGroup() {
        when(properties.qqConfigured()).thenReturn(true);
        when(properties.publicBaseUrl()).thenReturn("http://localhost/");
        when(gateway.healthy()).thenReturn(true);
        List<Map<String, Object>> groups = List.of(Map.of("group_openid", "first-group", "group_label", "First group"));
        when(db.listUserGroups("manager"))
                .thenReturn(groups);
        when(db.findManager("manager"))
                .thenReturn(Optional.of(Map.of("ok", 1)));
        when(db.findRecentCode("manager"))
                .thenReturn(Optional.of(Map.of("code", "123456", "expires_at", LocalDateTime.now().plusMinutes(5).toString())));

        Map<String, Object> status = service.status("manager");

        assertThat(status).containsEntry("binding_code", "123456").containsEntry("groups", groups);
        verify(gateway).healthy();
        verify(db, never()).insertCode(anyString(), anyString(), anyString(), anyString());
        verify(db, never()).deleteUserCodes(anyString());
        verifyNoInteractions(ai);
    }

    @Test
    void bindingCodeIsNotOfferedWithoutActiveOrganizationManagerPermission() {
        when(properties.qqConfigured()).thenReturn(true);
        when(properties.publicBaseUrl()).thenReturn("http://localhost");
        when(gateway.healthy()).thenReturn(false);

        Map<String, Object> status = service.status("participant");

        assertThat(status).containsEntry("binding_code", null);
        verify(db, never()).findRecentCode(anyString());
        verify(db, never()).insertCode(anyString(), anyString(), anyString(), anyString());
        verify(db, never()).deleteUserCodes(anyString());
        verify(gateway).healthy();
        verifyNoInteractions(ai);
    }

    private String sql(String method, int parameterCount) {
        return java.util.Arrays.stream(QqMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(method) && candidate.getParameterCount() == parameterCount)
                .map(candidate -> {
                    var select = candidate.getAnnotation(org.apache.ibatis.annotations.Select.class);
                    return String.join(" ", select == null
                            ? candidate.getAnnotation(org.apache.ibatis.annotations.Insert.class).value() : select.value());
                }).findFirst().orElseThrow();
    }

    private void bindingCodeForActiveManager() {
        when(db.lockCode("123456"))
                .thenReturn(Optional.of(Map.of("user_id", "manager", "expires_at",
                        LocalDateTime.now().plusMinutes(5).toString())));
        when(db.findManager("manager"))
                .thenReturn(Optional.of(Map.of("ok", 1)));
    }
}
