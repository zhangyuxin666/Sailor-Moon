package com.activityassistant.orchestration;

import com.activityassistant.config.AppProperties;
import com.activityassistant.integration.AiClient;
import com.activityassistant.persistence.Db;
import com.activityassistant.service.AgentRunService;
import com.activityassistant.service.QqService;
import com.activityassistant.service.ActivityGate;
import com.activityassistant.service.JobLease;
import com.activityassistant.service.TaskManagementService;
import com.activityassistant.service.ActivityManagementService;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Ids;
import com.activityassistant.support.PublicErrors;
import com.activityassistant.support.Times;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

/**
 * Runs the fixed activity workflow from planning through delivery.
 *
 * <p>This class deliberately has no broad transaction. The workflow calls AI and QQ services,
 * so wrapping the whole method would hold a database transaction across network I/O. Step-level
 * consistency is handled by {@link ExecutionJournal} and idempotency keys.</p>
 */
@Service
public class ActivityOrchestrator {
    private final Db db;
    private final AgentRunService agentRuns;
    private final AiClient ai;
    private final QqService qq;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final ExecutionJournal journal;
    private final ActivityGate gate;
    private com.activityassistant.service.DeliveryLogService deliveryLog;
    @org.springframework.beans.factory.annotation.Autowired
    public void deliveryLog(com.activityassistant.service.DeliveryLogService service){deliveryLog=service;}

    public ActivityOrchestrator(Db db, AgentRunService agentRuns, AiClient ai, QqService qq,
                                ObjectMapper mapper, AppProperties properties, ExecutionJournal journal, ActivityGate gate) {
        this.db = db;
        this.agentRuns = agentRuns;
        this.ai = ai;
        this.qq = qq;
        this.mapper = mapper;
        this.properties = properties;
        this.journal = journal;
        this.gate = gate;
    }

    private <T> T guarded(String id,boolean allowCancelled,java.util.function.Supplier<T> operation) {
        return gate==null?operation.get():gate.during(id,allowCancelled,operation);
    }
    public Map<String,Object> run(String activityId) {
        try {
            Map<String,Object> activity=guarded(activityId,false,()->{
                var row=db.one("SELECT * FROM activities WHERE id=?",activityId).orElseThrow(()->ApiException.notFound("活动不存在"));
                db.update("UPDATE activities SET status='running' WHERE id=?",activityId);agentRuns.start(activityId);return row;
            });
            var result=orchestrate(activity);
            if("waiting_input".equals(result.get("status")))return result;
            return guarded(activityId,false,()->{db.update("UPDATE activities SET status='ready' WHERE id=?",activityId);agentRuns.complete(activityId,result);return result;});
        } catch(ActivityGate.CancelledFlow cancelled){return Map.of("activity_id",activityId,"status","cancelled");}
        catch(JobLease.Lost lost){throw lost;}
        catch(RuntimeException error) {
            try {guarded(activityId,false,()->{db.update("UPDATE activities SET status='failed' WHERE id=?",activityId);agentRuns.fail(activityId,error);return null;});}
            catch(ActivityGate.CancelledFlow cancelled){return Map.of("activity_id",activityId,"status","cancelled");}
            throw error;
        }
    }

    private Map<String, Object> orchestrate(Map<String, Object> activity) {
        String activityId = String.valueOf(activity.get("id"));
        // 学习点: 先准备 Flow 所需上下文：流程开关、班级、可分配成员。
        JsonNode workflow = workflow(activityId);
        Map<String, Object> scope = db.one(
                "SELECT class_id, organization_id FROM activity_classes WHERE activity_id=?", activityId).orElse(Map.of());
        String classId = scope.get("class_id") == null ? null : String.valueOf(scope.get("class_id"));
        String organizationId = scope.get("organization_id") == null ? null : String.valueOf(scope.get("organization_id"));
        List<Map<String, Object>> assignees = classId == null ? List.of() : db.list(
                "SELECT a.id AS account_id, a.display_name FROM class_members cm JOIN accounts a ON a.id=cm.account_id " +
                        "WHERE cm.class_id=? AND a.status='active' ORDER BY a.student_no, a.id", classId);

        guarded(activityId,false,()->{agentRuns.phase(activityId,"planning");return null;});
        // 学习点: plan_json 已存在时直接复用，这是重跑不再调用 AI 的第一层原因。
        String rawInput = String.valueOf(activity.get("raw_input"));
        String planningInput = planningInput(activityId, rawInput);
        JsonNode plan;
        try {
            plan = activity.get("plan_json") == null
                    ? ai.plan(planningInput, assignees, organizationId)
                    : parse(String.valueOf(activity.get("plan_json")));
            if (flag(workflow, "assign_tasks", true) && requested(plan, "assign_tasks"))
                plan = normalizeTaskIdentities(activityId, plan, assignees, activity.get("plan_json") != null);
        } catch (RuntimeException exception) {
            recordFailure(activityId, "planning", "generate_plan", "plan:" + activityId, exception);
            throw exception;
        }
        String title = plan.path("title").asText(String.valueOf(activity.get("title")));
        JsonNode confirmedPlan=plan;
        guarded(activityId,false,()->{
        db.update("UPDATE activities SET title=?, plan_json=? WHERE id=?", title, confirmedPlan.toString(), activityId);
        agentRuns.planned(activityId, confirmedPlan);
        journal.record(activityId, "planning", "generate_plan", "done",
                "confirmed_draft".equals(confirmedPlan.path("plan_source").asText()) ? "使用已确认方案：" + title : title,
                "plan:" + activityId,
                Map.of("raw_input", planningInput, "available_assignees", assignees), confirmedPlan, null);
        return null;
        });

        if(plan.path("event_time").asText().isBlank() && (plan.path("requires_event_time").asBoolean(false)||flag(workflow,"create_calendar",false)||flag(workflow,"schedule_reminder",false))) {
            guarded(activityId,false,()->{db.update("UPDATE activities SET status='waiting_input' WHERE id=?",activityId);agentRuns.waiting(activityId);return null;});
            return Map.of("activity_id",activityId,"status","waiting_input");
        }
        Flow flow = new Flow(activityId, workflow, classId, assignees, plan, title);
        guarded(activityId,false,()->{agentRuns.phase(activityId,"execution");return null;});
        // 学习点: 下面四行就是固定业务顺序；时间、下发和失败边界都围绕这条主链展开。
        String formId = step(activityId, "execution", "create_form", "form:" + activityId, () -> createForm(flow));
        step(activityId, "execution", "assign_tasks", "tasks:" + activityId, () -> { assignTasks(flow); return null; });
        step(activityId, "execution", "create_calendar_event", "cal:" + activityId, () -> { createCalendar(flow); return null; });
        String reminderId = step(activityId, "execution", "schedule_reminder", "reminder:" + activityId,
                () -> scheduleReminder(flow));

        guarded(activityId,false,()->{agentRuns.phase(activityId,"delivery");return null;});
        return step(activityId, "delivery", "complete_delivery", "delivery:" + activityId,
                () -> deliver(flow, formId, reminderId));
    }

    private String createForm(Flow flow) {
        if (!flag(flow.workflow(), "create_form", true) || !requested(flow.plan(), "create_form")) return null;
        // 学习点: form:活动ID 是稳定幂等键，用来识别“同一个活动创建表单”这个逻辑操作。
        String key = "form:" + flow.activityId();
        Map<String, Object> old = journal.find(key, "create_form");
        String formId;
        if (old == null) {
            formId = Ids.shortId();
            ArrayNode fields = mapper.createArrayNode();
            boolean hasName = false;
            boolean hasContact = false;
            if (flow.plan().path("form_fields").isArray()) {
                for (JsonNode field : flow.plan().path("form_fields")) {
                    fields.add(field.deepCopy());
                    hasName |= "name".equals(field.path("name").asText());
                    hasContact |= "contact".equals(field.path("name").asText());
                }
            }
            // Confirmed drafts already include these fields; retain their reviewed labels and order.
            if (!hasName) fields.insert(0, mapper.createObjectNode().put("name", "name").put("label", "姓名"));
            if (!hasContact) fields.insert(Math.min(1, fields.size()),
                    mapper.createObjectNode().put("name", "contact").put("label", "联系方式"));
            db.update("INSERT INTO forms (id, activity_id, title, fields_json, created_at) VALUES (?, ?, ?, ?, ?)",
                    formId, flow.activityId(), flow.title() + "报名表", fields.toString(), LocalDateTime.now().toString());
            journal.save(key, "create_form", Map.of("form_id", formId));
        } else {
            formId = String.valueOf(old.get("form_id"));
        }
        journal.record(flow.activityId(), "execution", "create_form", "done", formId, key,
                Map.of("title", flow.title() + "报名表", "form_fields", flow.plan().path("form_fields")),
                Map.of("form_id", formId), null);
        return formId;
    }

    private String taskKey(JsonNode task,int index){String key=task.path("key").asText();return key.isBlank()?"task_"+index:key;}
    private void assignTasks(Flow flow) {
        if(!flag(flow.workflow(),"assign_tasks",true) || !requested(flow.plan(),"assign_tasks") || !flow.plan().path("tasks").isArray())return;
        List<JsonNode> planned=new ArrayList<>();flow.plan().path("tasks").forEach(planned::add);
        Map<String,String> ids=new LinkedHashMap<>();Map<String,Map<String,Object>> existing=new LinkedHashMap<>();
        int index=0;
        for(JsonNode task:planned) {
            TaskManagementService.validateTask(task);
            String key=taskKey(task,index);
            if(!key.matches("[A-Za-z0-9_-]{1,40}") || ids.containsKey(key))throw ApiException.badRequest("任务标识无效或重复");
            String planKey="plan:"+index++;
            Map<String,Object> old=db.one("SELECT * FROM tasks WHERE activity_id=? AND plan_key=?",flow.activityId(),planKey).orElse(null);
            if(old==null && !task.path("account_id").asText().isBlank()) {
                var legacy=db.list("SELECT t.id FROM tasks t JOIN activity_task_assignees ata ON ata.task_id=t.id WHERE t.activity_id=? AND t.title=? AND ata.account_id=? AND t.plan_key LIKE 'legacy:%'",flow.activityId(),task.path("title").asText(),task.path("account_id").asText());
                if(legacy.size()>1)throw ApiException.badRequest("历史任务关联不唯一，请确认原任务后再恢复");
                if(legacy.size()==1)old=legacy.get(0);
            }
            String id=old==null?UUID.nameUUIDFromBytes((flow.activityId()+":"+planKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-","").substring(0,12):String.valueOf(old.get("id"));
            ids.put(key,id);existing.put(key,old);
        }
        Map<String,List<String>> graph=new LinkedHashMap<>();index=0;
        for(JsonNode task:planned) {
            String key=taskKey(task,index++);List<String> deps=new ArrayList<>();
            for(JsonNode dep:task.path("depends_on")) {if(!dep.isTextual() || !ids.containsKey(dep.asText()) || deps.contains(ids.get(dep.asText())))throw ApiException.badRequest("任务依赖无效或重复");deps.add(ids.get(dep.asText()));}
            graph.put(ids.get(key),deps);
        }
        TaskManagementService.validateGraph(graph);index=0;int assigned=0;
        Set<String> created=new HashSet<>();
        for(JsonNode task:planned) {
            String key=taskKey(task,index),id=ids.get(key),planKey="plan:"+index++;
            String account=task.path("account_id").asText(),name=task.path("assignee").asText("待分配");
            if(existing.get(key)==null) {
                String deadline=task.path("deadline").asText("");if(!deadline.isBlank())deadline=Times.instant(deadline,properties.timezone()).toString();else deadline=null;
                db.update("INSERT INTO tasks (id, activity_id, title, assignee, status, created_at, description, deadline, priority, plan_key, unassigned_reason) VALUES (?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?, ?)",
                    id,flow.activityId(),task.path("title").asText(),name,LocalDateTime.now().toString(),task.path("description").asText(""),deadline,task.path("priority").asText("normal"),planKey,account.isBlank()?task.path("unassigned_reason").asText("尚未指定负责人"):null);
                if(!account.isBlank())db.update("INSERT INTO activity_task_assignees (task_id, account_id) VALUES (?, ?)",id,account);
                created.add(id);
            } else db.update("UPDATE tasks SET plan_key=? WHERE id=? AND (plan_key IS NULL OR plan_key LIKE 'legacy:%')",planKey,id);
            if(account.isBlank())continue;
            String content="【"+flow.title()+"】你被分配了任务「"+task.path("title").asText()+"」，活动时间 "+flow.plan().path("event_time").asText()+"，请及时完成。";
            String messageKey="msg:"+flow.activityId()+":"+id;
            String legacyKey="msg:"+flow.activityId()+":"+UUID.nameUUIDFromBytes((account+":"+task.path("title").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(journal.find(messageKey,"send_message")==null && journal.find(legacyKey,"send_message")==null) {
                db.update("INSERT INTO messages (activity_id, recipient, content, created_at, recipient_account_id) VALUES (?, ?, ?, ?, ?)",flow.activityId(),name,content,LocalDateTime.now().toString(),account);
                journal.save(messageKey,"send_message",Map.of("status","sent"));
            }
            assigned++;
        }
        for(var entry:graph.entrySet())if(created.contains(entry.getKey()))for(String dep:entry.getValue())db.update("INSERT INTO task_dependencies(task_id,depends_on_id) VALUES (?,?)",entry.getKey(),dep);
        journal.record(flow.activityId(),"execution","assign_tasks","done",assigned+" 项已分配，"+(planned.size()-assigned)+" 项待分配","tasks:"+flow.activityId(),flow.plan().path("tasks"),Map.of("requested_count",planned.size(),"assigned_count",assigned),null);
    }

    private void createCalendar(Flow flow) {
        if (!flag(flow.workflow(), "create_calendar", true) || !requested(flow.plan(), "create_calendar")) return;
        // 学习点: 日历结果也按活动 ID 缓存，重跑时复用同一份 ICS，而不是重新生成。
        String key = "cal:" + flow.activityId();
        String eventTime = flow.plan().path("event_time").asText();
        String description = flow.plan().path("description").asText();
        if (journal.find(key, "create_calendar_event") == null) {
            String ics = ActivityManagementService.calendar(flow.activityId(),flow.title(),eventTime,description,1,false,properties.timezone());
            journal.save(key, "create_calendar_event", Map.of("title", flow.title(), "event_time", eventTime, "ics", ics));
        }
        journal.record(flow.activityId(), "execution", "create_calendar_event", "done", eventTime, key,
                Map.of("title", flow.title(), "event_time", eventTime, "description", description),
                Map.of("event_time", eventTime), null);
    }

    private String scheduleReminder(Flow flow) {
        String eventTime = flow.plan().path("event_time").asText();
        if (!flag(flow.workflow(), "schedule_reminder", true)
                || !requested(flow.plan(), "schedule_reminder")
                || eventTime.isBlank()) return null;

        long remindMinutesBefore = flow.plan().path("remind_minutes_before").asLong(60);
        // 学习点: remind_at 是“活动开始时间减去提前量”后的绝对提醒时间。
        Instant remindAt = Times.instant(eventTime, properties.timezone()).minusSeconds(remindMinutesBefore * 60);
        String time = LocalDateTime.ofInstant(remindAt, java.time.ZoneId.of(properties.timezone())).toString();
        // 学习点: 同一活动、同一提醒时间只复用一条提醒；数据库唯一约束还会再做一层兜底。
        Map<String, Object> old = db.one("SELECT * FROM reminders WHERE activity_id=? AND remind_at=?",
                flow.activityId(), time).orElse(null);
        String reminderId;
        if (old == null) {
            reminderId = Ids.shortId();
            List<String> materials = new ArrayList<>();
            flow.plan().path("materials").forEach(node -> materials.add(node.asText()));
            db.update("INSERT INTO reminders (id, activity_id, message, remind_at, status, created_at) VALUES (?, ?, ?, ?, 'scheduled', ?)",
                    reminderId, flow.activityId(), "活动「" + flow.title() + "」将于 " + eventTime + " 开始，物料："
                            + String.join("、", materials) + "，请各负责人就位！", time, LocalDateTime.now().toString());
        } else {
            reminderId = String.valueOf(old.get("id"));
        }
        journal.record(flow.activityId(), "execution", "schedule_reminder", "done", reminderId,
                "reminder:" + flow.activityId(),
                Map.of("event_time", eventTime, "remind_at", time, "remind_minutes_before", remindMinutesBefore),
                Map.of("reminder_id", reminderId), null);
        return reminderId;
    }

    private Map<String, Object> deliver(Flow flow, String formId, String reminderId) {
        // 学习点: delivery 阶段负责外部投递；没有 QQ 群时仍要记录业务结果已经就绪。
        String group = db.one("SELECT group_openid FROM activity_channels WHERE activity_id=?", flow.activityId())
                .map(row -> String.valueOf(row.get("group_openid"))).orElse(null);
        boolean publishRequested = flag(flow.workflow(), "publish_message", true)
                && requested(flow.plan(), "publish_message");
        if (group != null && publishRequested) {
            StringBuilder announcement = new StringBuilder("【发布】").append(flow.title()).append("\n\n时间：")
                    .append(flow.plan().path("event_time").asText()).append("\n\n")
                    .append(flow.plan().path("description").asText());
            if (formId != null) {
                announcement.append("\n\n填写链接：").append(properties.publicBaseUrl().replaceAll("/$", ""))
                        .append("/forms/").append(formId);
            }
            sendGroupMessage(flow.activityId(), group, announcement.toString(), "announcement",
                    "qq:announcement:" + flow.activityId());
        }

        Map<String, Object> deliverySummary = new LinkedHashMap<>();
        deliverySummary.put("qq_group_attached", group != null);
        deliverySummary.put("published", group != null && publishRequested);
        journal.record(flow.activityId(), "delivery", "complete_delivery", "done",
                group == null ? "无需外部群投递，业务结果已就绪" : "交付阶段完成",
                "delivery:" + flow.activityId(),
                Map.of("group_attached", group != null, "form_id", formId == null ? "" : formId),
                deliverySummary, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activity_id", flow.activityId());
        result.put("plan", flow.plan());
        result.put("form_id", formId);
        result.put("reminder_id", reminderId);
        return result;
    }

    public Map<String, Object> sendGroupMessage(String activityId, String group, String content, String kind, String key) {
        return guarded(activityId,"activity_cancelled".equals(kind),()->sendGroupMessageUnchecked(activityId,group,content,kind,key));
    }
    private Map<String,Object> sendGroupMessageUnchecked(String activityId,String group,String content,String kind,String key) {
        // 学习点: 外部副作用之前先查幂等结果；命中就说明这条消息以前已经成功发过。
        if(deliveryLog!=null) {
            var original=db.one("SELECT target,content FROM delivery_events WHERE logical_key=?",key).orElse(null);
            if(original!=null&&(!group.equals(original.get("target"))||!content.equals(original.get("content"))))throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,"投递标识已用于不同内容");
        }
        Map<String,Object> cached=journal.find(key,"send_qq_group_message");
        if(cached!=null)return cached;
        String organizationId = db.one("SELECT organization_id FROM activity_classes WHERE activity_id=?", activityId)
                .map(row -> String.valueOf(row.get("organization_id"))).orElse(null);
        if (!qq.isGroupAuthorized(organizationId, group)) {
            throw ApiException.forbidden("活动 QQ 群绑定已失效，请重新选择授权群");
        }
        if(deliveryLog!=null)deliveryLog.record(activityId,key,kind,group,content,"sending",null,null);
        try {
            Map<String,Object> sent=qq.sendGroup(group,content,key);
            String external=sent.get("id")==null?null:String.valueOf(sent.get("id"));
            if(deliveryLog!=null)deliveryLog.record(activityId,key,kind,group,content,"sent",external,null);
            Map<String,Object> result=new LinkedHashMap<>();result.put("external_id",external);result.put("recipient",group);result.put("status","sent");
            journal.save(key,"send_qq_group_message",result);
            journal.record(activityId,"delivery","publish_qq","done","活动消息已发布到 QQ 群",key,null,result,null);
            return result;
        }catch(RuntimeException error) {
            String state=error instanceof ApiException api&&api.status().value()==409?"unknown":"failed";
            if(deliveryLog!=null)deliveryLog.record(activityId,key,kind,group,content,state,null,PublicErrors.failure(error));
            throw error;
        }
    }

    private <T> T step(String activityId, String phase, String name, String key, java.util.function.Supplier<T> action) {
        try { return guarded(activityId,false,action); }
        catch (ActivityGate.CancelledFlow cancelled) { throw cancelled; }
        catch (JobLease.Lost lost) { throw lost; }
        catch (RuntimeException exception) {
            recordFailure(activityId, phase, name, key, exception);
            throw exception;
        }
    }

    private void recordFailure(String activityId, String phase, String name, String key, RuntimeException exception) {
        String safe = PublicErrors.failure(exception);
        guarded(activityId,false,()->{journal.record(activityId, phase, name, "failed", safe, key, null, null, safe);return null;});
    }

    JsonNode normalizeTaskIdentities(String activityId, JsonNode plan,
                                     List<Map<String, Object>> candidates, boolean storedPlan) {
        if (!plan.path("tasks").isArray()) return plan;
        Map<String, String> namesById = new LinkedHashMap<>();
        candidates.forEach(member -> namesById.put(
                String.valueOf(member.get("account_id")), String.valueOf(member.get("display_name"))));
        JsonNode normalized = plan.deepCopy();
        for (JsonNode task : normalized.path("tasks")) {
            if (!task.isObject() || task.path("title").asText().isBlank()) {
                throw ApiException.badRequest("活动任务必须包含标题");
            }
            String accountId = task.path("account_id").asText("");
            if (accountId.isBlank() && storedPlan) {
                // Legacy plans may only reuse an existing authoritative relationship.
                // Never infer a new account from a name, even when it appears unique.
                List<Map<String, Object>> existing = db.list(
                        "SELECT DISTINCT ata.account_id FROM tasks t JOIN activity_task_assignees ata ON ata.task_id=t.id " +
                                "WHERE t.activity_id=? AND t.title=? AND t.assignee=?",
                        activityId, task.path("title").asText(), task.path("assignee").asText());
                if (existing.size() == 1) accountId = String.valueOf(existing.get(0).get("account_id"));
            }
            if (!namesById.containsKey(accountId)) {
                ((ObjectNode)task).put("account_id","").put("assignee","待分配").put("unassigned_reason","负责人未指定、已失效或不属于当前班级");
                continue;
            }
            ((ObjectNode) task).put("account_id", accountId).put("assignee", namesById.get(accountId));
        }
        return normalized;
    }

    private JsonNode workflow(String activityId) {
        return db.one("SELECT config_json FROM activity_workflows WHERE activity_id=?", activityId)
                .map(row -> parse(String.valueOf(row.get("config_json")))).orElseGet(() -> {
                    ObjectNode node = mapper.createObjectNode();
                    node.put("create_form", true).put("assign_tasks", true).put("create_calendar", true)
                            .put("schedule_reminder", true).put("publish_message", true);
                    return node;
                });
    }

    private String planningInput(String activityId, String rawInput) {
        // 学习点: 恢复执行时把 input_json 合并进规划文本，但已完成 plan_json 时仍然优先复用旧方案。
        Map<String, Object> run = agentRuns.byRef("activity", activityId);
        if (run == null || run.get("input_json") == null) return rawInput;
        JsonNode input = parse(String.valueOf(run.get("input_json")));
        if (!input.isObject() || input.isEmpty()) return rawInput;
        return rawInput + "\n用户补充信息: " + input;
    }

    private boolean flag(JsonNode workflow, String key, boolean fallback) {
        return workflow.has(key) ? workflow.path(key).asBoolean() : fallback;
    }

    private boolean requested(JsonNode plan, String tool) {
        JsonNode intents = plan.path("tool_intents");
        if (!intents.isArray()) return true;
        if (intents.isEmpty()) return !"confirmed_draft".equals(plan.path("plan_source").asText());
        for (JsonNode intent : intents) if (tool.equals(intent.path("tool").asText())) return true;
        return false;
    }

    private String calendar(String title, String eventTime, String description) {
        return String.join("\r\n", "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Sailor-Moon//Activity Assistant//CN",
                "BEGIN:VEVENT", "UID:" + UUID.randomUUID().toString().replace("-", "") + "@sailor-moon",
                "DTSTAMP:" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()),
                "DTSTART:" + Times.ics(eventTime, properties.timezone()), "SUMMARY:" + Times.escapeIcs(title),
                "DESCRIPTION:" + Times.escapeIcs(description), "END:VEVENT", "END:VCALENDAR", "");
    }

    private JsonNode parse(String value) {
        try {
            return mapper.readTree(value == null || "null".equals(value) ? "{}" : value);
        } catch (JsonProcessingException exception) {
            return mapper.createObjectNode();
        }
    }

    private record Flow(String activityId, JsonNode workflow, String classId, List<Map<String, Object>> assignees,
                        JsonNode plan, String title) {
    }
}
