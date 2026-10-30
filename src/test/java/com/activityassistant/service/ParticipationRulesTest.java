package com.activityassistant.service;

import com.activityassistant.persistence.Db;
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
    private final Db db = mock(Db.class);
    private final ClassroomService classroom = mock(ClassroomService.class);
    private final ActivityGate gate = new ActivityGate(db, mapper, null, mock(AuditService.class), null);
    private final ParticipationService service = new ParticipationService(db, gate, classroom);
    private String status = "ready";
    private boolean currentMember = true;
    private boolean checkedIn = true;
    private Integer feedbackVersion;

    private static AccountPrincipal account(String id, String username, String role) {
        return new AccountPrincipal(id, username, username, "", role, "active", "now", 0);
    }

    @BeforeEach void eligibleActivity() {
        when(db.one(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.equals("SELECT * FROM activities WHERE id=? FOR UPDATE"))
                return Optional.of(Map.of("id", ACTIVITY, "user_id", "owner", "status", status));
            if (sql.startsWith("SELECT 1 FROM activity_classes ac JOIN"))
                return currentMember ? Optional.of(Map.of("present", 1)) : Optional.empty();
            if (sql.startsWith("SELECT 1 FROM activity_participants"))
                return Optional.of(Map.of("present", 1));
            if (sql.startsWith("SELECT 1 FROM activity_check_ins"))
                return checkedIn ? Optional.of(Map.of("present", 1)) : Optional.empty();
            if (sql.startsWith("SELECT attendance_open,feedback_open"))
                return Optional.of(Map.of("attendance_open", true, "feedback_open", true, "version", 1, "roster_confirmed", true));
            if (sql.startsWith("SELECT version FROM activity_feedback") && feedbackVersion != null)
                return Optional.of(Map.of("version", feedbackVersion));
            return Optional.empty();
        });
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
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void studentCannotSupplyAnotherMembersIdentity() {
        rejected(() -> service.checkIn(ACTIVITY, STUDENT, checkIn().put("account_id", "another-student")), HttpStatus.FORBIDDEN);
    }

    @Test void actualMemberManagerCanCheckInWithoutOwningTheActivity() {
        when(db.update(startsWith("INSERT INTO activity_check_ins"), any(Object[].class))).thenReturn(1);
        when(db.required("SELECT checked_in_at FROM activity_check_ins WHERE activity_id=? AND account_id=?", ACTIVITY, MEMBER_MANAGER.id()))
                .thenReturn(Map.of("checked_in_at", "2026-10-27T09:00:00"));

        var result = service.checkIn(ACTIVITY, MEMBER_MANAGER, checkIn());

        assertThat(result).containsEntry("account_id", MEMBER_MANAGER.id()).containsEntry("checked_in", true);
        verify(db).update(startsWith("INSERT INTO activity_check_ins"), eq(ACTIVITY), eq(MEMBER_MANAGER.id()), eq(MEMBER_MANAGER.id()), anyString());
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
