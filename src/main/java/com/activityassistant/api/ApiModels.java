package com.activityassistant.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public final class ApiModels {
    private ApiModels() {}

    public record BootstrapRequest(
            @NotBlank @Size(min = 3, max = 40) String username,
            @NotBlank @Size(min = 1, max = 40) String displayName,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Size(min = 1, max = 80) String organizationName,
            boolean acceptPrivacy) {
        public BootstrapRequest {
            if (organizationName == null || organizationName.isBlank()) organizationName = "我的组织";
        }
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record PasswordChangeRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 128) String newPassword) {}
    public record PasswordResetRequest(@NotBlank @Size(min = 8, max = 128) String newPassword) {}
    public record PrivacyConsentRequest(boolean accept) {}
    public record ClassCreateRequest(@NotBlank @Size(max = 80) String name) {}

    public record RosterMember(
            @NotBlank @Size(min = 3, max = 40) String username,
            @NotBlank @Size(max = 40) String displayName,
            @Size(max = 40) String studentNo,
            @NotBlank @Size(min = 6, max = 128) String password) {
        public RosterMember {
            if (studentNo == null) studentNo = "";
        }
    }

    public record RosterImportRequest(@NotNull List<@Valid RosterMember> members) {}

    public record TodoCreateRequest(
            @NotBlank @Size(max = 120) String title,
            @Size(max = 4000) String description,
            @NotBlank String deadline,
            String kind) {
        public TodoCreateRequest {
            if (description == null) description = "";
            if (kind == null || kind.isBlank()) kind = "homework";
        }
    }

    public record TodoReminderRequest(@NotBlank String remindAt) {}
    public record AgentRequest(@NotBlank String classId, @Size(min = 2, max = 4000) String text) {}
    public record AgentDraftUpdateRequest(@NotNull JsonNode draft) {}

    public record ActivityCreateRequest(
            @NotBlank String text,
            Boolean publishToQq,
            String classId) {}

    public record RegistrationRequest(@NotBlank String name, @NotBlank String contact,
                                      Map<String, Object> extra) {}
    public record ActivityMessageRequest(@NotBlank @Size(max = 4000) String content,
                                         @NotBlank @Size(min = 8, max = 80) String requestId) {}
    public record TaskUpdateRequest(@NotBlank @Pattern(regexp = "pending|done") String status) {}

    public record QqInboundRequest(
            String eventType,
            @NotBlank String groupOpenid,
            String senderOpenid,
            String senderName,
            @NotNull String content,
            @NotBlank String messageId,
            String timestamp) {}

    public record KnowledgeDocumentRequest(@NotBlank @Size(max = 500) String source,
                                           @NotBlank @Size(max = 100_000) String content,
                                           Map<String, Object> metadata) {}
    public record KnowledgeSearchRequest(@NotBlank @Size(max = 4000) String query,
                                         @Min(1) @Max(20) Integer topK) {}
}
