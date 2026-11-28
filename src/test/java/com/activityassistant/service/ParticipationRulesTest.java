package com.activityassistant.service;

import com.activityassistant.persistence.mapper.ActivityGateMapper;
import com.activityassistant.persistence.mapper.ParticipationMapper;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParticipationRulesTest {
    private static final String ACTIVITY = "activity";
    private static final AccountPrincipal STUDENT = account("student-id", "student", "participant");
    private static final AccountPrincipal MEMBER_MANAGER = account("member-manager-id", "member-manager", "manager");
    private static final AccountPrincipal OWNER = account("owner-id", "owner", "manager");
    private final ObjectMapper mapper = new ObjectMapper();
    private final ActivityGateMapper gateMapper = mock(ActivityGateMapper.class);
    private final ClassroomService classroom = mock(ClassroomService.class);
    private final ActivityGate gate = new ActivityGate(gateMapper, mapper, null, mock(AuditService.class), null);
    private final ParticipationMapper rows = mock(ParticipationMapper.class);
    private final ParticipationService service = new ParticipationService(rows, gate, classroom);
    private String status = "ready";
    private boolean currentMember = true;
    private boolean checkedIn = true;
    private Integer feedbackVersion;

    private static AccountPrincipal account(String id, String username, String role) {
        return new AccountPrincipal(id, username, username, "", role, "active", "now", 0);
    }

    @BeforeEach void eligibleActivity() {
        when(gateMapper.lockActivity(ACTIVITY))
                .thenAnswer(call -> Optional.of(Map.of("id", ACTIVITY, "user_id", "owner", "status", status)));
        when(rows.findMember(eq(ACTIVITY), anyString()))
                .thenAnswer(call -> currentMember ? Optional.of(Map.of("present", 1)) : Optional.empty());
        when(rows.findRosterMember(eq(ACTIVITY), anyString())).thenReturn(Optional.of(Map.of("present", 1)));
        when(rows.hasCheckIn(eq(ACTIVITY), anyString()))
                .thenAnswer(call -> checkedIn ? Optional.of(Map.of("present", 1)) : Optional.empty());
        when(rows.findSettings(ACTIVITY)).thenReturn(Optional.of(
                Map.of("attendance_open", true, "feedback_open", true, "version", 1, "roster_confirmed", true)));
        when(rows.findFeedbackVersion(eq(ACTIVITY), anyString())).thenAnswer(
                call -> feedbackVersion == null ? Optional.empty() : Optional.of(Map.of("version", feedbackVersion)));
    }

    private ObjectNode checkIn() {
        return mapper.createObjectNode().put("request_id", "request-check-001");
    }
    private ObjectNode feedback() {
        return mapper.createObjectNode().put("request_id", "request-feedback-001")
                .put("rating", 5).put("comment", "活动安排清晰").put("expected_version", 0);
    }
    private ObjectNode settings() {
        return mapper.createObjectNode().put("request_id", "request-settings-001")
                .put("attendance_open", true).put("feedback_open", true).put("expected_version", 1);
    }
    private void rejected(Runnable action, HttpStatus expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(expected));
        verify(rows, never()).insertCheckIn(anyString(), anyString(), anyString(), anyString());
        verify(rows, never()).saveSettings(anyString(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(rows, never()).insertFeedback(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
        verify(rows, never()).updateFeedback(anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void studentCannotSupplyAnotherMembersIdentity() {
        rejected(() -> service.checkIn(ACTIVITY, STUDENT, checkIn().put("account_id", "another-student")), HttpStatus.FORBIDDEN);
    }

    @Test void actualMemberManagerCanCheckInWithoutOwningTheActivity() {
        when(rows.insertCheckIn(eq(ACTIVITY), eq(MEMBER_MANAGER.id()), eq(MEMBER_MANAGER.id()), anyString())).thenReturn(1);
        when(rows.findCheckIn(ACTIVITY, MEMBER_MANAGER.id()))
                .thenReturn(Optional.of(Map.of("checked_in_at", "2026-10-27T09:00:00")));

        var result = service.checkIn(ACTIVITY, MEMBER_MANAGER, checkIn());

        assertThat(result).containsEntry("account_id", MEMBER_MANAGER.id()).containsEntry("checked_in", true);
        verify(rows).insertCheckIn( eq(ACTIVITY), eq(MEMBER_MANAGER.id()), eq(MEMBER_MANAGER.id()), anyString());
        verifyNoInteractions(classroom);
    }

    @Test void managerRoleAloneDoesNotGrantMemberCheckIn() {
        currentMember = false;
        rejected(() -> service.checkIn(ACTIVITY, MEMBER_MANAGER, checkIn()), HttpStatus.FORBIDDEN);
    }

    @Test void feedbackRequiresAnExistingCheckIn() {
        checkedIn = false;
        rejected(() -> service.feedback(ACTIVITY, STUDENT, feedback()), HttpStatus.FORBIDDEN);
    }

    @Test void unknownFieldsAreRejectedByEveryParticipationMutation() {
        rejected(() -> service.checkIn(ACTIVITY, STUDENT, checkIn().put("checked_by", OWNER.id())), HttpStatus.BAD_REQUEST);
        rejected(() -> service.feedback(ACTIVITY, STUDENT, feedback().put("account_id", OWNER.id())), HttpStatus.BAD_REQUEST);
        rejected(() -> service.configure(ACTIVITY, OWNER, settings().put("roster_confirmed", true)), HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "6", "1.5", "\"5\"", "2147483648", "null"})
    void feedbackRejectsNonIntegerOrOutOfRangeRatings(String raw) throws Exception {
        JsonNode rating = mapper.readTree(raw);
        rejected(() -> service.feedback(ACTIVITY, STUDENT, feedback().set("rating", rating)), HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0.5", "\"0\"", "2147483648", "null"})
    void feedbackRejectsInvalidVersions(String raw) throws Exception {
        JsonNode version = mapper.readTree(raw);
        rejected(() -> service.feedback(ACTIVITY, STUDENT, feedback().set("expected_version", version)), HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "\"1\"", "2147483648", "null"})
    void configurationRejectsInvalidVersions(String raw) throws Exception {
        JsonNode version = mapper.readTree(raw);
        rejected(() -> service.configure(ACTIVITY, OWNER, settings().set("expected_version", version)), HttpStatus.BAD_REQUEST);
    }

    @Test void feedbackVersionConflictDoesNotOverwriteAnExistingResponse() {
        feedbackVersion = 2;
        rejected(() -> service.feedback(ACTIVITY, STUDENT, feedback().put("expected_version", 1)), HttpStatus.CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "finished"})
    void terminalActivitiesRejectNewCheckInsEvenWithAnOpenSetting(String activityStatus) {
        status = activityStatus;
        rejected(() -> service.checkIn(ACTIVITY, STUDENT, checkIn()), HttpStatus.BAD_REQUEST);
    }
}
