package com.activityassistant.service;

import com.activityassistant.integration.AiClient;
import com.activityassistant.orchestration.ActivityOrchestrator;
import com.activityassistant.orchestration.ExecutionJournal;
import com.activityassistant.persistence.ReplicaReads;
import com.activityassistant.persistence.mapper.ActivityMapper;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Ids;
import com.activityassistant.support.Times;
import com.activityassistant.support.PublicErrors;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ActivityService {
    private final ReplicaReads replicaReads;
    private final ActivityMapper activities;
    private final BackgroundJobService jobs;
    private final AgentRunService agentRuns;
    private final AiClient ai;
    private final QqService qq;
    private final ObjectMapper mapper;
    private final ActivityOrchestrator orchestrator;
    private final ExecutionJournal journal;
    private final RunLifecycleService lifecycle;
    private final RegistrationService registrations;
    private final TaskManagementService taskManagement;
    private final ActivityGate gate;

    public ActivityService(ReplicaReads replicaReads, ActivityMapper activities, BackgroundJobService jobs, AgentRunService agentRuns, AiClient ai, QqService qq,
                           ObjectMapper mapper, ActivityOrchestrator orchestrator, ExecutionJournal journal,
                           RunLifecycleService lifecycle, RegistrationService registrations, TaskManagementService taskManagement, ActivityGate gate) {
        this.replicaReads = replicaReads;
        this.activities = activities;
        this.jobs = jobs;
        this.agentRuns = agentRuns;
        this.ai = ai;
        this.qq = qq;
        this.mapper = mapper;
        this.orchestrator = orchestrator;
        this.journal = journal;
        this.lifecycle = lifecycle;
        this.registrations = registrations;
        this.taskManagement = taskManagement;
        this.gate = gate;
    }

    // 学习点: 这个事务只覆盖入队前的数据库写入，不会把后续 AI、QQ 等后台流程包进来。
    @Transactional
    public Map<String, Object> queue(AccountPrincipal account, String text, boolean publishToQq,
                                     String classId, JsonNode workflow) {
        String id = Ids.shortId();
        String now = LocalDateTime.now().toString();
        // 学习点: 先把活动写成 queued，用户马上能拿到 ID，而不是等完整流程执行完。
        activities.insertActivity(id, account.username(), text.substring(0, Math.min(20, text.length())), text, now);
        String group = null;
        String organizationId = null;
        // 学习点: 有班级时保存班级、流程配置和 QQ 群绑定；这些是后续编排需要的上下文。
        if (classId != null && !classId.isBlank()) {
            Map<String, Object> classroom = activities.findClass(classId)
                    .orElseThrow(() -> ApiException.notFound("班级不存在"));
            activities.linkClass(id, classId, classroom.get("organization_id"));
            organizationId = String.valueOf(classroom.get("organization_id"));
            if (workflow != null) activities.insertWorkflow(id, workflow.toString());
            if (publishToQq && classroom.get("qq_group_openid") != null) {
                group = String.valueOf(classroom.get("qq_group_openid"));
                if (!qq.isGroupAuthorized(organizationId, group))
                    throw ApiException.forbidden("班级 QQ 群绑定已失效，请重新选择授权群");
                activities.insertChannel(id, account.username(), group, now);
            }
        } else if (publishToQq) {
            group = qq.attachActivity(account.username(), id);
        }
        // 学习点: 真正交给后台的是 background_jobs 中的任务，不是在这里直接执行活动流程。
        Map<String, Object> job = jobs.enqueue("activity", id);
        // 学习点: run_id 复用 job_id，用同一个编号关联任务执行状态和工作流状态。
        agentRuns.create(String.valueOf(job.get("id")), "activity", id, organizationId, account.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activity_id", id); result.put("run_id", job.get("id")); result.put("status", job.get("status")); result.put("qq_group_attached", group != null);
        return result;
    }

    public List<Map<String, Object>> list(AccountPrincipal account) {
        if (account.isManager()) return replicaReads.readMapper(ActivityMapper.class, activities, reader -> reader.listManaged(account.id(), account.username()));
        return replicaReads.readMapper(ActivityMapper.class, activities, reader -> reader.listAssigned(account.id()));
    }

    public void requireOwner(String activityId, String username) {
        Map<String, Object> row = activities.findOwner(activityId)
                .orElseThrow(() -> ApiException.notFound("活动不存在: " + activityId));
        if (!username.equals(row.get("user_id"))) throw ApiException.forbidden("只能操作自己创建的活动");
    }

    public Map<String, Object> detail(String activityId, String username) {
        requireOwner(activityId, username);
        Map<String, Object> activity = new LinkedHashMap<>(java.util.Objects.requireNonNull(activities.findActivity(activityId)));
        JsonNode plan = null;
        try { if (activity.get("plan_json") != null) plan = mapper.readTree(String.valueOf(activity.get("plan_json"))); } catch (Exception ignored) {}
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activity", activity); result.put("plan", plan);
        Map<String, Object> run = agentRuns.byRef("activity", activityId);
        result.put("run_id", run == null ? null : run.get("id"));
        result.put("tasks", taskManagement.tasks(activityId));
        result.put("forms", activities.listForms(activityId));
        result.put("reminders", activities.listReminders(activityId));
        result.put("steps", replicaReads.readMapper(ActivityMapper.class, activities, reader -> reader.listSteps(activityId))
                .stream().map(this::publicStep).toList());
        result.put("qq_group_attached", activities.findChannel(activityId).isPresent());
        result.put("deliveries", replicaReads.readMapper(ActivityMapper.class, activities, reader -> reader.listDeliveries(activityId))
                .stream().map(this::publicDelivery).toList());
        return result;
    }

    public Map<String,Object> registration(String formId,String name,String contact,Map<String,Object> extra,String requestId,String cancellationToken) {
        return registrations.register(formId,name,contact,extra,requestId,cancellationToken);
    }
    public Map<String,Object> publicForm(String formId){return registrations.metadata(formId);}
    public Map<String,Object> formStats(String activityId,String username){return registrations.stats(activityId,username);}

    public Map<String, Object> cancelReminder(String reminderId, String username) {
        Map<String, Object> row = activities.findReminderActivity(reminderId).orElseThrow(() -> ApiException.notFound("提醒不存在: " + reminderId));
        String activityId=String.valueOf(row.get("activity_id"));
        return gate.during(activityId,true,()->{
            requireOwner(activityId,username);
            var reminder=java.util.Objects.requireNonNull(activities.lockReminder(reminderId));
            if("sent".equals(reminder.get("status")))return Map.of("id",reminderId,"status","sent","message","提醒已经发送，不能撤回");
            activities.cancelReminder(reminderId);
            activities.cancelReminderJob(reminderId);
            return Map.of("id",reminderId,"status","cancelled");
        });
    }

    public Map<String,Object> updateTask(String taskId,String status,AccountPrincipal account){return taskManagement.status(taskId,status,account);}

    public String calendar(String activityId, String username) {
        requireOwner(activityId, username);
        Map<String, Object> result = journal.find("cal:" + activityId, "create_calendar_event");
        if (result == null) throw ApiException.notFound("该活动还没有日历事件");
        Map<String,Object> activity=java.util.Objects.requireNonNull(activities.findActivity(activityId));
        JsonNode plan=parse(String.valueOf(activity.get("plan_json")));
        return ActivityManagementService.calendarWithUid(activityId,String.valueOf(activity.get("title")),plan.path("event_time").asText(),plan.path("description").asText(),
                ((Number)activity.get("version")).intValue(),"cancelled".equals(activity.get("status")),String.valueOf(result.get("ics")),gate.timezone());
    }

    public Map<String, Object> sendManual(String activityId, String username, String content, String requestId) {
        requireOwner(activityId, username);
        String group = activities.findChannelGroup(activityId)
                .map(row -> String.valueOf(row.get("group_openid"))).orElseThrow(() -> ApiException.notFound("该活动没有绑定 QQ 群"));
        return orchestrator.sendGroupMessage(activityId, group, content, "manual", "manual:" + activityId + ":" + requestId);
    }

    public Map<String, Object> saveRunInput(String runId, String username, JsonNode input) {
        // 学习点: 权限检查放在业务门面，具体等待态校验和落库交给 RunLifecycleService。
        requireOwnedRun(runId, username);
        return lifecycle.saveInput(runId, input);
    }

    public Map<String, Object> resumeRun(String runId, String username) {
        // 学习点: 用户只能恢复自己活动对应的运行，不能只凭 runId 越权操作。
        requireOwnedRun(runId, username);
        return lifecycle.resume(runId);
    }

    private Map<String, Object> requireOwnedRun(String runId, String username) {
        Map<String, Object> run = agentRuns.byId(runId);
        if (run == null) throw ApiException.notFound("运行记录不存在: " + runId);
        if (!"activity".equals(run.get("kind"))) throw ApiException.forbidden("不能操作当前类型的运行记录");
        requireOwner(String.valueOf(run.get("ref_id")), username);
        return run;
    }

    private Map<String, Object> publicStep(Map<String, Object> source) {
        Map<String, Object> item = new LinkedHashMap<>(source);
        item.keySet().removeIf(key -> key.endsWith("_json"));
        boolean failed = "failed".equals(item.get("status")) || item.get("error") != null;
        item.put("error", PublicErrors.message(item.get("error")));
        if (failed) item.put("detail", PublicErrors.message(source.get("error") == null
                ? source.get("detail") : source.get("error")));
        return item;
    }

    private Map<String, Object> publicDelivery(Map<String, Object> source) {
        Map<String, Object> item = new LinkedHashMap<>(source);
        item.put("error", PublicErrors.message(source.get("error")));
        return item;
    }

    private JsonNode parse(String value) {
        try { return mapper.readTree(value == null || "null".equals(value) ? "{}" : value); }
        catch (JsonProcessingException exception) { return mapper.createObjectNode(); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
}
