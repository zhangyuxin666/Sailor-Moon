package com.activityassistant.service;

import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfirmedPlansTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reviewedFieldsAndWorkflowOverrideGeneratedPlanWithoutDroppingTasksAndMaterials() throws Exception {
        JsonNode draft = mapper.readTree("""
                {"intent_type":"activity","title":"Reviewed","description":"Edited description",
                 "event_time":"2026-10-25T19:00:00","workflow":{"create_form":true,"assign_tasks":true,"publish_message":false},
                 "form_fields":[{"name":"choice","label":"Where?","type":"select","options":["Park","Hall"]}],
                 "execution_plan":{"title":"Generated","event_time":"2026-01-01T00:00:00","materials":["water"],
                  "tasks":[{"title":"Prepare","account_id":"u1","assignee":"Manager"}],"remind_minutes_before":30}}
                """);

        JsonNode plan = ConfirmedPlans.fromDraft(draft, mapper);

        assertThat(plan.path("title").asText()).isEqualTo("Reviewed");
        assertThat(plan.path("description").asText()).isEqualTo("Edited description");
        assertThat(plan.path("event_time").asText()).isEqualTo("2026-10-25T19:00:00");
        assertThat(plan.path("materials").get(0).asText()).isEqualTo("water");
        assertThat(plan.path("tasks").get(0).path("account_id").asText()).isEqualTo("u1");
        assertThat(plan.path("remind_minutes_before").asInt()).isEqualTo(30);
        assertThat(plan.path("tool_intents").toString()).contains("create_form", "assign_tasks").doesNotContain("publish_message");
        assertThat(plan.path("form_fields").get(2)).isEqualTo(draft.path("form_fields").get(0));
    }

    @Test
    void identityFieldsKeepReviewedLabelsOrderAndCustomMetadata() throws Exception {
        JsonNode raw = mapper.readTree("""
                [{"name":"choice","label":"Select","type":"select","options":["A","B"],"required":false},
                 {"name":"contact","label":"School email","type":"text","placeholder":"Email"},
                 {"name":"name","label":"Your name","type":"text"}]
                """);

        JsonNode fields = ConfirmedPlans.fields(raw, mapper);

        assertThat(fields.get(0)).isEqualTo(raw.get(0));
        assertThat(fields.get(1).path("label").asText()).isEqualTo("School email");
        assertThat(fields.get(1).path("placeholder").asText()).isEqualTo("Email");
        assertThat(fields.get(1).path("required").asBoolean()).isTrue();
        assertThat(fields.get(2).path("label").asText()).isEqualTo("Your name");
    }

    @Test
    void duplicateFieldNamesAndInvalidTimesAreRejected() throws Exception {
        JsonNode duplicate = mapper.readTree("""
                [{"name":"same","label":"One"},{"name":"same","label":"Two"}]
                """);
        assertThatThrownBy(() -> ConfirmedPlans.fields(duplicate, mapper)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ConfirmedPlans.validateTime("tomorrow")).isInstanceOf(ApiException.class);
    }

    @Test
    void unknownWorkflowKeysAndNonBooleanValuesAreRejected() throws Exception {
        ObjectNode draft = (ObjectNode) mapper.readTree("""
                {"intent_type":"survey","title":"Survey","workflow":{"delete_all":true}}
                """);
        assertThatThrownBy(() -> ConfirmedPlans.validateDraft(draft)).isInstanceOf(ApiException.class);
        draft.set("workflow", mapper.readTree("{\"create_form\":\"true\"}"));
        assertThatThrownBy(() -> ConfirmedPlans.validateDraft(draft)).isInstanceOf(ApiException.class);
    }

    @Test
    void supplementalAnswersBecomePartOfConfirmedDescription() throws Exception {
        JsonNode draft = mapper.readTree("""
                {"intent_type":"survey","title":"Survey","description":"Base","workflow":{"create_form":true},
                 "input":{"answers":{"Which room?":"Room 204"}}}
                """);
        JsonNode plan = ConfirmedPlans.fromDraft(draft, mapper);
        assertThat(plan.path("description").asText()).contains("Base", "Which room?", "Room 204");
        assertThat(plan.path("supplemental_information").path("Which room?").asText()).isEqualTo("Room 204");
    }
}
