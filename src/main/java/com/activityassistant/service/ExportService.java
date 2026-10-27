package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.CsvExport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Export explicit business columns at one database snapshot, never internal credentials or metadata. */
@Service
public class ExportService {
    private final Db db;
    private final ClassroomService classroom;
    private final AuditService audits;
    private final ObjectMapper mapper;

    public ExportService(Db db, ClassroomService classroom, AuditService audits, ObjectMapper mapper) {
        this.db = db;
        this.classroom = classroom;
        this.audits = audits;
        this.mapper = mapper;
    }

    private String activityOwner(String id, AccountPrincipal actor) {
        Map<String,Object> activity = db.one("SELECT a.user_id,ac.class_id,ac.organization_id FROM activities a LEFT JOIN activity_classes ac ON ac.activity_id=a.id WHERE a.id=?", id)
                .orElseThrow(() -> ApiException.notFound("活动不存在"));
        if (!actor.isManager() || !actor.username().equals(activity.get("user_id")))
            throw ApiException.forbidden("只能导出自己活动的数据");
        Object classId = activity.get("class_id");
        if (classId == null) return null;
        String organization=String.valueOf(classroom.requireClassManager(String.valueOf(classId), actor).get("organization_id"));
        if(!Objects.equals(organization,activity.get("organization_id")))throw ApiException.forbidden("活动组织范围已变化，请联系管理员");
        return organization;
    }

    private JsonNode parse(Object value) {
        try { JsonNode node=mapper.readTree(value == null ? "{}" : String.valueOf(value)); return node == null ? mapper.nullNode() : node; }
        catch (Exception error) { return mapper.getNodeFactory().textNode(String.valueOf(value)); }
    }

    private Object answer(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "";
        if (value.isTextual()) return value.asText();
        if (value.isNumber()) return value.numberValue();
        if (value.isBoolean()) return value.asBoolean();
        return value.toString();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public byte[] registrations(String id, AccountPrincipal actor) {
        String org = activityOwner(id, actor);
        List<Map<String,Object>> forms = db.list("SELECT id,title,fields_json FROM forms WHERE activity_id=? ORDER BY created_at,id", id);
        List<Map<String,Object>> registrations = db.list("SELECT r.id,r.form_id,f.title AS form_title,r.name,r.contact,r.status,r.created_at,r.cancelled_at,r.extra_json FROM registrations r JOIN forms f ON f.id=r.form_id WHERE f.activity_id=? ORDER BY r.created_at,r.id", id);
        Map<String,LinkedHashSet<String>> labels = new LinkedHashMap<>();
        for (Map<String,Object> form : forms) {
            for (JsonNode field : parse(form.get("fields_json"))) {
                String name = field.path("name").asText();
                if (name.isBlank() || name.equals("name") || name.equals("contact")) continue;
                labels.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(field.path("label").asText(name));
            }
        }
        List<JsonNode> answers = new ArrayList<>();
        SortedSet<String> historical = new TreeSet<>();
        boolean nonObject = false;
        for (Map<String,Object> registration : registrations) {
            JsonNode extra = parse(registration.get("extra_json"));
            answers.add(extra);
            if (extra.isObject()) extra.fieldNames().forEachRemaining(key -> { if (!labels.containsKey(key)) historical.add(key); });
            else if (!extra.isNull()) nonObject = true;
        }
        List<String> keys = new ArrayList<>(labels.keySet());
        keys.addAll(historical);
        List<String> headers = new ArrayList<>(List.of("报名编号", "报名表编号", "报名表", "姓名", "联系方式", "报名状态", "报名时间", "取消时间"));
        for (String key : keys) headers.add(labels.containsKey(key)
                ? "问卷：" + String.join(" / ", labels.get(key)) + " [" + key + "]"
                : "历史字段 [" + key + "]");
        if (nonObject) headers.add("历史原始回答");
        List<List<?>> rows = new ArrayList<>();
        for (int index = 0; index < registrations.size(); index++) {
            Map<String,Object> row = registrations.get(index);
            List<Object> values = new ArrayList<>(Arrays.asList(row.get("id"),row.get("form_id"),row.get("form_title"),row.get("name"),row.get("contact"),
                    "cancelled".equals(row.get("status")) ? "已取消" : "有效",row.get("created_at"),row.get("cancelled_at")));
            JsonNode extra = answers.get(index);
            for (String key : keys) values.add(answer(extra.isObject() ? extra.path(key) : null));
            if (nonObject) values.add(extra.isObject() || extra.isNull() ? "" : answer(extra));
            rows.add(values);
        }
        byte[] csv = CsvExport.bytes(headers, rows);
        audits.write("export.registrations",actor.id(),org,"activity",id,Map.of("row_count",rows.size()));
        return csv;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public byte[] tasks(String id, AccountPrincipal actor) {
        String org = activityOwner(id, actor);
        List<Map<String,Object>> tasks = db.list("SELECT t.id,t.title,t.description,t.deadline,t.priority,t.status,t.assignee AS historical_assignee,t.unassigned_reason,ata.account_id,a.username,a.student_no,a.display_name FROM tasks t LEFT JOIN activity_task_assignees ata ON ata.task_id=t.id LEFT JOIN accounts a ON a.id=ata.account_id WHERE t.activity_id=? ORDER BY t.created_at,t.id", id);
        Map<String,List<String>> dependencies = new LinkedHashMap<>();
        for (Map<String,Object> dependency : db.list("SELECT d.task_id,d.depends_on_id,dep.title FROM task_dependencies d JOIN tasks own ON own.id=d.task_id LEFT JOIN tasks dep ON dep.id=d.depends_on_id AND dep.activity_id=own.activity_id WHERE own.activity_id=? ORDER BY d.task_id,d.depends_on_id", id)) {
            dependencies.computeIfAbsent(String.valueOf(dependency.get("task_id")), key -> new ArrayList<>())
                    .add(String.valueOf(dependency.get("depends_on_id")) + "：" + (dependency.get("title") == null ? "无效依赖" : dependency.get("title")));
        }
        List<List<?>> rows = new ArrayList<>();
        for (Map<String,Object> task : tasks) rows.add(Arrays.asList(task.get("id"),task.get("title"),task.get("description"),task.get("deadline"),task.get("priority"),task.get("status"),
                task.get("account_id"),task.get("username"),task.get("student_no"),task.get("account_id") == null ? "待分配" : task.get("display_name"),task.get("historical_assignee"),task.get("unassigned_reason"),String.join("；",dependencies.getOrDefault(String.valueOf(task.get("id")),List.of()))));
        byte[] csv = CsvExport.bytes(List.of("任务编号","任务标题","说明","截止时间","优先级","完成状态","负责人账号编号","登录账号","学号","负责人","历史显示名","待分配原因","前置依赖（编号与标题）"),rows);
        audits.write("export.tasks",actor.id(),org,"activity",id,Map.of("row_count",rows.size()));
        return csv;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public byte[] missing(String id, AccountPrincipal actor) {
        if (!actor.isManager()) throw ApiException.forbidden("只有管理者可以导出未交名单");
        Map<String,Object> todo = db.one("SELECT id,class_id,title,deadline FROM todos WHERE id=?",id)
                .orElseThrow(() -> ApiException.notFound("待办不存在"));
        String org = String.valueOf(classroom.requireClassManager(String.valueOf(todo.get("class_id")),actor).get("organization_id"));
        List<List<?>> rows = new ArrayList<>();
        for (Map<String,Object> member : db.list("SELECT ta.account_id,a.username,a.student_no,a.display_name,ta.status FROM todo_assignees ta JOIN accounts a ON a.id=ta.account_id WHERE ta.todo_id=? AND ta.status<>'done' ORDER BY a.student_no,a.username,a.id",id))
            rows.add(Arrays.asList(todo.get("id"),todo.get("title"),todo.get("deadline"),member.get("account_id"),member.get("username"),member.get("student_no"),member.get("display_name"),member.get("status")));
        byte[] csv = CsvExport.bytes(List.of("待办编号","待办标题","截止时间","账号编号","登录账号","学号","姓名","完成状态"),rows);
        audits.write("export.missing",actor.id(),org,"todo",id,Map.of("row_count",rows.size()));
        return csv;
    }
}
