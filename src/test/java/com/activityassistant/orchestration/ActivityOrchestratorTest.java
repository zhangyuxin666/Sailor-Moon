package com.activityassistant.orchestration;

import com.activityassistant.config.AppProperties;
import com.activityassistant.integration.AiClient;
import com.activityassistant.persistence.Db;
import com.activityassistant.service.AgentRunService;
import com.activityassistant.service.QqService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityOrchestratorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void runsBusinessStepsInTheSpecifiedOrderAndRecordsIdempotencyKeys() throws Exception {
        // 学习点: Arrange：用固定活动和 Fake 依赖准备一个可重复的测试场景。
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("id", "a1");
        activity.put("raw_input", "plan a welcome event");
        activity.put("title", "Welcome event");
        activity.put("plan_json", null);
        JsonNode plan = samplePlan();
        FakeDb db = new FakeDb(activity);
        FakeAgentRuns agentRuns = new FakeAgentRuns();
        FakeAiClient ai = new FakeAiClient(properties(), plan);
        FakeQqService qq = new FakeQqService();
        FakeJournal journal = new FakeJournal();
        ActivityOrchestrator orchestrator = new ActivityOrchestrator(
                db, agentRuns, ai, qq, mapper, properties(), journal, null);

        // 学习点: Act：只执行一次完整的活动编排。
        Map<String, Object> result = orchestrator.run("a1");

        // 学习点: Assert：分别验证返回值、状态顺序、步骤顺序、幂等键和外部副作用次数。
        assertThat(result).containsEntry("activity_id", "a1");
        assertThat(result.get("form_id")).isNotNull();
        assertThat(result.get("reminder_id")).isNotNull();
        assertThat(result.get("plan")).isEqualTo(plan);
        assertThat(agentRuns.events).containsExactly(
                "start", "phase:planning", "planned", "phase:execution", "phase:delivery", "complete");
        assertThat(journal.steps()).containsSubsequence(
                "generate_plan", "create_form", "assign_tasks", "create_calendar_event",
                "schedule_reminder", "publish_qq", "complete_delivery");
        assertThat(journal.keys()).containsSubsequence(
                "plan:a1", "form:a1", "tasks:a1", "cal:a1", "reminder:a1",
                "qq:announcement:a1", "delivery:a1");
        assertThat(db.updates).contains("UPDATE activities SET status='running' WHERE id=? [a1]");
        assertThat(db.updates).contains("UPDATE activities SET status='ready' WHERE id=? [a1]");
        assertThat(qq.sentContents).hasSize(1);
    }

    @Test
    void rerunReusesCompletedStepsAndDoesNotDuplicateSideEffects() throws Exception {
        // 学习点: 这个测试故意对同一个活动连续执行两次，检查第二次是否复用第一次的结果。
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("id", "a1");
        activity.put("raw_input", "plan a welcome event");
        activity.put("title", "Welcome event");
        activity.put("plan_json", null);
        FakeDb db = new FakeDb(activity);
        FakeAgentRuns agentRuns = new FakeAgentRuns();
        FakeAiClient ai = new FakeAiClient(properties(), samplePlan());
        FakeQqService qq = new FakeQqService();
        FakeJournal journal = new FakeJournal();
        ActivityOrchestrator orchestrator = new ActivityOrchestrator(
                db, agentRuns, ai, qq, mapper, properties(), journal, null);

        Map<String, Object> first = orchestrator.run("a1");
        Map<String, Object> second = orchestrator.run("a1");

        // 学习点: 返回结果应相同，所有关键副作用计数都应保持为 1。
        assertThat(second.get("form_id")).isEqualTo(first.get("form_id"));
        assertThat(second.get("reminder_id")).isEqualTo(first.get("reminder_id"));
        assertThat(ai.planCalls).isEqualTo(1);
        assertThat(db.formInserts).isEqualTo(1);
        assertThat(db.taskInserts).isEqualTo(1);
        assertThat(db.reminderInserts).isEqualTo(1);
        assertThat(qq.sentContents).hasSize(1);
    }

    @Test
    void resumedRunPassesSupplementalInputToPlanner() throws Exception {
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("id", "a1");
        activity.put("raw_input", "plan a welcome event");
        activity.put("title", "Welcome event");
        activity.put("plan_json", null);
        FakeDb db = new FakeDb(activity);
        FakeAgentRuns agentRuns = new FakeAgentRuns();
        agentRuns.run = Map.of("input_json", "{\"event_time\":\"2026-10-20T19:00:00\"}");
        FakeAiClient ai = new FakeAiClient(properties(), samplePlan());
        FakeQqService qq = new FakeQqService();
        FakeJournal journal = new FakeJournal();
        ActivityOrchestrator orchestrator = new ActivityOrchestrator(
                db, agentRuns, ai, qq, mapper, properties(), journal, null);

        orchestrator.run("a1");

        assertThat(ai.inputs).hasSize(1);
        assertThat(ai.inputs.get(0))
                .contains("plan a welcome event")
                .contains("用户补充信息")
                .contains("2026-10-20T19:00:00");
    }


    @Test
    void confirmedSnapshotExecutesReviewedQuestionsWithoutCallingAi() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode plan = (com.fasterxml.jackson.databind.node.ObjectNode) samplePlan();
        plan.put("plan_source", "confirmed_draft").put("title", "Reviewed survey");
        plan.set("form_fields", mapper.readTree("""
                [{"name":"destination","label":"Destination","type":"select","options":["Park","Museum"],"required":true},
                 {"name":"contact","label":"School email","type":"text","required":true},
                 {"name":"name","label":"Your name","type":"text","required":true}]
                """));
        Map<String, Object> activity = activityWithPlan(plan);
        FakeDb db = new FakeDb(activity);
        FakeAiClient ai = new FakeAiClient(properties(), samplePlan());
        ActivityOrchestrator orchestrator = new ActivityOrchestrator(
                db, new FakeAgentRuns(), ai, new FakeQqService(), mapper, properties(), new FakeJournal(), null);

        Map<String, Object> result = orchestrator.run("a1");

        assertThat(ai.planCalls).isZero();
        assertThat(mapper.readTree(db.formFieldsJson)).isEqualTo(plan.path("form_fields"));
        assertThat(((JsonNode) result.get("plan")).path("title").asText()).isEqualTo("Reviewed survey");
    }

    @Test
    void confirmedEmptyWorkflowDoesNotFallBackToExecutingAllTools() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode plan = (com.fasterxml.jackson.databind.node.ObjectNode) samplePlan();
        plan.put("plan_source", "confirmed_draft");
        plan.set("tool_intents", mapper.createArrayNode());
        FakeDb db = new FakeDb(activityWithPlan(plan));
        FakeAiClient ai = new FakeAiClient(properties(), samplePlan());
        FakeQqService qq = new FakeQqService();
        FakeJournal journal = new FakeJournal();
        ActivityOrchestrator orchestrator = new ActivityOrchestrator(
                db, new FakeAgentRuns(), ai, qq, mapper, properties(), journal, null);

        orchestrator.run("a1");

        assertThat(ai.planCalls).isZero();
        assertThat(db.formInserts).isZero();
        assertThat(db.taskInserts).isZero();
        assertThat(db.reminderInserts).isZero();
        assertThat(qq.sentContents).isEmpty();
        assertThat(journal.steps()).doesNotContain("create_form", "assign_tasks", "create_calendar_event", "schedule_reminder", "publish_qq");
    }

    @Test
    void failedStepIsRecordedAndRecoveryReusesTheSuccessfulPlan() throws Exception {
        FakeDb db = new FakeDb(activityWithPlan(samplePlan()));
        db.failForm = true;
        FakeAiClient ai = new FakeAiClient(properties(), samplePlan());
        FakeAgentRuns runs = new FakeAgentRuns();
        FakeJournal journal = new FakeJournal();
        ActivityOrchestrator orchestrator = new ActivityOrchestrator(
                db, runs, ai, new FakeQqService(), mapper, properties(), journal, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.run("a1"))
                .isInstanceOf(com.activityassistant.support.ApiException.class);
        assertThat(journal.statuses).contains("create_form:failed");
        assertThat(journal.statuses).doesNotContain("create_form:done");
        assertThat(runs.events).contains("fail");

        db.failForm = false;
        orchestrator.run("a1");

        assertThat(ai.planCalls).isZero();
        assertThat(db.formInserts).isEqualTo(1);
        assertThat(journal.statuses).contains("create_form:done");
    }

    private Map<String, Object> activityWithPlan(JsonNode plan) {
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("id", "a1");
        activity.put("raw_input", "plan a welcome event");
        activity.put("title", "Welcome event");
        activity.put("plan_json", plan.toString());
        return activity;
    }

    private JsonNode samplePlan() throws Exception {
        return mapper.readTree("""
                {
                  "title": "Welcome event",
                  "event_time": "2026-10-20T19:00:00",
                  "description": "Meet new classmates",
                  "remind_minutes_before": 60,
                  "materials": ["water"],
                  "tool_intents": [
                    {"tool": "create_form"},
                    {"tool": "assign_tasks"},
                    {"tool": "create_calendar"},
                    {"tool": "schedule_reminder"},
                    {"tool": "publish_message"}
                  ],
                  "form_fields": [],
                  "tasks": [{"title": "Buy water", "account_id": "acct-1", "assignee": "Alice"}]
                }
                """);
    }

    private AppProperties properties() {
        return new AppProperties("http://localhost", false, 1, 1, 1, "/tmp",
                "Asia/Shanghai", "http://ai.local", "token", "", "", "", "", Duration.ofSeconds(1));
    }

    private static final class FakeDb extends Db {
        // 学习点: FakeDb 不连接 PostgreSQL，只返回固定数据并记录 INSERT/UPDATE 次数。
        private final Map<String, Object> activity;
        private final List<String> updates = new ArrayList<>();
        private final Set<String> taskKeys = new LinkedHashSet<>();
        private final Map<String,Map<String,Object>> planTasks=new HashMap<>();
        private final Map<String, Map<String, Object>> reminders = new HashMap<>();
        private int formInserts;
        private String formFieldsJson;
        private boolean failForm;
        private int taskInserts;
        private int reminderInserts;

        private FakeDb(Map<String, Object> activity) {
            super(new JdbcTemplate());
            this.activity = activity;
        }

        @Override
        public Optional<Map<String, Object>> one(String sql, Object... args) {
            if (sql.equals("SELECT * FROM activities WHERE id=?")) return Optional.of(activity);
            if (sql.startsWith("SELECT config_json FROM activity_workflows")) return Optional.empty();
            if (sql.startsWith("SELECT class_id, organization_id FROM activity_classes")) {
                return Optional.of(Map.of("class_id", "c1", "organization_id", "org-1"));
            }
            if(sql.startsWith("SELECT * FROM tasks WHERE activity_id=? AND plan_key=?"))return Optional.ofNullable(planTasks.get(String.valueOf(args[1])));
            if (sql.startsWith("SELECT t.id FROM tasks")) {
                return taskKeys.contains(args[1] + "\0" + args[2])
                        ? Optional.of(Map.of("id",planTasks.values().iterator().next().get("id"))) : Optional.empty();
            }
            if (sql.startsWith("SELECT a.id FROM class_members")) return Optional.of(Map.of("id", 1L));
            if (sql.startsWith("SELECT * FROM reminders")) return Optional.ofNullable(reminders.get(String.valueOf(args[1])));
            if (sql.startsWith("SELECT organization_id FROM activity_classes")) return Optional.of(Map.of("organization_id", "org-1"));
            if (sql.startsWith("SELECT group_openid FROM activity_channels")) {
                return Optional.of(Map.of("group_openid", "group-1"));
            }
            throw new AssertionError("Unexpected query: " + sql);
        }

        @Override
        public List<Map<String, Object>> list(String sql, Object... args) {
            if(sql.startsWith("SELECT t.id FROM tasks"))return List.of();
            if (sql.startsWith("SELECT a.id AS account_id, a.display_name FROM class_members")) {
                return List.of(Map.of("account_id", "acct-1", "display_name", "Alice"));
            }
            throw new AssertionError("Unexpected query: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql + " " + java.util.Arrays.asList(args));
            if (sql.startsWith("UPDATE activities SET title=?, plan_json=?")) {
                activity.put("title", args[0]);
                activity.put("plan_json", args[1]);
            }
            if (sql.startsWith("INSERT INTO forms")) {
                if (failForm) throw com.activityassistant.support.ApiException.badRequest("暂时无法创建表单");
                formInserts += 1;
                formFieldsJson = String.valueOf(args[3]);
            }
            if (sql.startsWith("INSERT INTO tasks")) {
                taskInserts += 1;
                planTasks.put(String.valueOf(args[8]),Map.of("id",args[0]));
                taskKeys.add(args[2] + "\0" + "acct-1");
            }
            if (sql.startsWith("INSERT INTO reminders")) {
                reminderInserts += 1;
                reminders.put(String.valueOf(args[3]), Map.of("id", args[0]));
            }
            return 1;
        }
    }

    private static final class FakeAgentRuns extends AgentRunService {
        // 学习点: FakeAgentRuns 把状态调用转换成事件列表，用来检查 start/phase/complete 的顺序。
        private final List<String> events = new ArrayList<>();
        private Map<String, Object> run;

        private FakeAgentRuns() {
            super(null, null);
        }

        @Override
        public void start(String refId) {
            events.add("start");
        }

        @Override
        public void phase(String refId, String phase) {
            events.add("phase:" + phase);
        }

        @Override
        public void planned(String refId, Object plan) {
            events.add("planned");
        }

        @Override
        public void complete(String refId, Object result) {
            events.add("complete");
        }

        @Override
        public void fail(String refId, Exception exception) {
            events.add("fail");
        }

        @Override
        public Map<String, Object> byRef(String kind, String refId) {
            return run;
        }
    }

    private static final class FakeAiClient extends AiClient {
        // 学习点: FakeAiClient 不访问 AI 服务，直接返回样例方案，并统计 plan 被调用了几次。
        private final JsonNode plan;
        private final List<String> inputs = new ArrayList<>();
        private int planCalls;

        private FakeAiClient(AppProperties properties, JsonNode plan) {
            super(RestClient.builder(), properties);
            this.plan = plan;
        }

        @Override
        public JsonNode plan(String rawInput, List<Map<String, Object>> availableAssignees, String organizationId) {
            planCalls += 1;
            inputs.add(rawInput);
            assertThat(rawInput).startsWith("plan a welcome event");
            assertThat(availableAssignees).containsExactly(Map.of("account_id", "acct-1", "display_name", "Alice"));
            assertThat(organizationId).isEqualTo("org-1");
            return plan;
        }
    }

    private static final class FakeQqService extends QqService {
        // 学习点: FakeQqService 不连接 QQ，只记录发送内容，供测试断言没有重复发送。
        private final List<String> sentContents = new ArrayList<>();

        private FakeQqService() {
            super(null, null, null, null);
        }

        @Override
        public boolean isGroupAuthorized(String organizationId, String groupOpenid) {
            return "org-1".equals(organizationId) && "group-1".equals(groupOpenid);
        }

        @Override
        public Map<String, Object> sendGroup(String groupOpenid, String content, String idempotencyKey) {
            sentContents.add(content);
            return Map.of("id", "qq-1");
        }
    }

    private static final class FakeJournal extends ExecutionJournal {
        // 学习点: FakeJournal 用内存 Map 代替数据库，保存幂等结果并记录步骤顺序。
        private final List<Entry> entries = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();
        private final Map<String, Map<String, Object>> saved = new HashMap<>();

        private FakeJournal() {
            super(null, null);
        }

        @Override
        public Map<String, Object> find(String key, String tool) {
            return saved.get(key);
        }

        @Override
        public void save(String key, String tool, Map<String, Object> result) {
            saved.put(key, result);
        }

        @Override
        public void record(String activityId, String phase, String step, String status, String detail,
                           String idempotencyKey, Object input, Object output, String error) {
            entries.add(new Entry(step, idempotencyKey));
            statuses.add(step + ":" + status);
        }

        private List<String> steps() {
            return entries.stream().map(Entry::step).toList();
        }

        private List<String> keys() {
            return entries.stream().map(Entry::key).toList();
        }

        private record Entry(String step, String key) {
        }
    }
}
