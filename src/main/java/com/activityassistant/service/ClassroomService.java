package com.activityassistant.service;

import com.activityassistant.api.ApiModels;
import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Ids;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ClassroomService {
    private DeliveryLogService deliveryLog;
    @org.springframework.beans.factory.annotation.Autowired
    public void deliveryLog(DeliveryLogService service){this.deliveryLog=service;}
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".zip", ".png", ".jpg", ".jpeg");

    private final Db db;
    private final AuthService auth;
    private final AuditService audits;
    private final StorageService storage;
    private final QqService qq;
    private final AppProperties properties;

    public ClassroomService(Db db, AuthService auth, AuditService audits, StorageService storage,
                            QqService qq, AppProperties properties) {
        this.db = db;
        this.auth = auth;
        this.audits = audits;
        this.storage = storage;
        this.qq = qq;
        this.properties = properties;
    }

    @Transactional
    public Map<String, Object> createClass(AccountPrincipal account, String name) {
        requireManager(account);
        String now = LocalDateTime.now().toString();
        String organizationId = ensureOrganization(account);
        // A class is bound explicitly after creation; never inherit a global or arbitrary group.
        String group = null;
        String id = Ids.shortId();
        db.update("INSERT INTO classes (id, name, manager_id, qq_group_openid, created_at) VALUES (?, ?, ?, ?, ?)",
                id, name.trim(), account.id(), group, now);
        db.update("INSERT INTO class_organizations (class_id, organization_id) VALUES (?, ?)", id, organizationId);
        audits.write("class.create", account.id(), organizationId, "class", id, Map.of("name", name.trim()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id); result.put("name", name); result.put("qq_group_bound", group != null);
        return result;
    }

    public Map<String, Object> requireClassManager(String classId, AccountPrincipal account) {
        Map<String, Object> classroom = db.one("SELECT * FROM classes WHERE id=?", classId)
                .orElseThrow(() -> ApiException.notFound("班级不存在"));
        Map<String, Object> membership = db.one("SELECT co.organization_id, om.role FROM class_organizations co " +
                "JOIN organization_members om ON om.organization_id=co.organization_id WHERE co.class_id=? AND om.account_id=?",
                classId, account.id()).orElse(null);
        if (!account.isManager() || membership == null || !("owner".equals(membership.get("role")) || "manager".equals(membership.get("role"))))
            throw ApiException.forbidden("只能管理自己组织的班级");
        Map<String, Object> result = new LinkedHashMap<>(classroom);
        result.put("organization_id", membership.get("organization_id"));
        return result;
    }

    public Map<String, Object> classQqBindings(AccountPrincipal account, String classId) {
        Map<String, Object> classroom = requireClassManager(classId, account);
        String organizationId = String.valueOf(classroom.get("organization_id"));
        Object current = classroom.get("qq_group_openid");
        boolean valid = current != null && qq.isGroupAuthorized(organizationId, String.valueOf(current));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", qq.authorizedGroups(organizationId));
        result.put("group_openid", valid ? current : null);
        result.put("qq_group_bound", valid);
        result.put("binding_needs_update", current != null && !valid);
        return result;
    }

    @Transactional
    public Map<String, Object> bindClassGroup(AccountPrincipal account, String classId, String groupOpenid) {
        Map<String, Object> classroom = requireClassManager(classId, account);
        String organizationId = String.valueOf(classroom.get("organization_id"));
        String group = groupOpenid == null || groupOpenid.isBlank() ? null : groupOpenid.trim();
        if (group != null && (group.length() > 255 || !qq.isGroupAuthorized(organizationId, group))) {
            throw ApiException.forbidden("只能选择本组织管理者已授权的 QQ 群");
        }
        db.update("UPDATE classes SET qq_group_openid=? WHERE id=?", group, classId);
        audits.write("class.qq_binding_update", account.id(), organizationId, "class", classId,
                Map.of("previous_group", classroom.get("qq_group_openid") == null ? "" : classroom.get("qq_group_openid"),
                        "group_openid", group == null ? "" : group));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class_id", classId);
        result.put("group_openid", group);
        result.put("qq_group_bound", group != null);
        return result;
    }

    @Transactional
    public Map<String, Object> importRoster(AccountPrincipal account, String classId, List<ApiModels.RosterMember> members) {
        requireManager(account);
        Map<String, Object> classroom = requireClassManager(classId, account);
        if (members == null || members.isEmpty()) throw ApiException.badRequest("请提供要导入的成员");
        if (members.size() > 500) throw ApiException.badRequest("单次最多导入 500 名成员");
        Set<String> usernames = new HashSet<>();
        for (ApiModels.RosterMember item : members) {
            if (item == null || item.username() == null || item.username().isBlank()) {
                throw ApiException.badRequest("成员登录名不能为空");
            }
            if (!usernames.add(item.username().trim())) throw ApiException.badRequest("导入成员的登录名重复");
        }

        // Validate the whole batch before creating accounts or membership rows.
        // Lock existing accounts in a stable order so reset/import cannot race on their ownership.
        String organizationId = String.valueOf(classroom.get("organization_id"));
        Map<String, Map<String, Object>> existingMembers = new HashMap<>();
        for (ApiModels.RosterMember item : members.stream()
                .sorted(java.util.Comparator.comparing(member -> member.username().trim())).toList()) {
            String username = item.username().trim();
            Map<String, Object> member = db.one("SELECT * FROM accounts WHERE username=? FOR UPDATE", username).orElse(null);
            if (member == null) continue;
            if (!"participant".equals(member.get("role")) || !"active".equals(member.get("status"))) {
                throw ApiException.forbidden("不能复用该已有账号，请使用本组织有效成员或新的登录名");
            }
            List<Map<String, Object>> organizations = db.list(
                    "SELECT organization_id FROM organization_members WHERE account_id=?", member.get("id"));
            if (organizations.size() != 1 || !organizationId.equals(organizations.get(0).get("organization_id"))) {
                throw ApiException.forbidden("不能导入其他组织或尚未授权加入本组织的已有账号");
            }
            existingMembers.put(username, member);
        }

        List<Map<String, Object>> created = new ArrayList<>();
        String now = LocalDateTime.now().toString();
        for (ApiModels.RosterMember item : members) {
            String username = item.username().trim();
            Map<String, Object> member = existingMembers.get(username);
            String memberId;
            if (member != null) {
                memberId = String.valueOf(member.get("id"));
            } else {
                memberId = auth.createAccount(username, item.displayName(), item.password(), "participant", item.studentNo());
                db.update("INSERT INTO account_security (account_id, force_password_change, failed_logins, password_changed_at) VALUES (?, 1, 0, ?)", memberId, now);
                db.update("INSERT INTO organization_members (organization_id, account_id, role, created_at) VALUES (?, ?, 'participant', ?)",
                        organizationId, memberId, now);
            }
            db.update("INSERT INTO class_members (class_id, account_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", classId, memberId, now);
            Object displayName = member == null ? item.displayName() : member.get("display_name");
            Object studentNo = member == null ? item.studentNo() : member.get("student_no");
            created.add(Map.of("id", memberId, "username", username, "display_name", displayName,
                    "student_no", studentNo == null ? "" : studentNo));
        }
        audits.write("roster.import", account.id(), String.valueOf(classroom.get("organization_id")), "class", classId, Map.of("count", created.size()));
        return Map.of("count", created.size(), "members", created);
    }

    @Transactional
    public Map<String, Object> importRosterExcel(AccountPrincipal account, String classId, byte[] content) {
        if (content.length > 5 * 1024 * 1024) throw ApiException.badRequest("Excel 文件不能超过 5MB");
        List<ApiModels.RosterMember> members = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) throw ApiException.badRequest("Excel 文件为空");
            Map<String, Integer> indexes = new HashMap<>();
            for (int i = 0; i < header.getLastCellNum(); i++) indexes.put(formatter.formatCellValue(header.getCell(i)).trim(), i);
            List<String> required = List.of("学号", "姓名", "登录名", "初始密码");
            List<String> missing = required.stream().filter(name -> !indexes.containsKey(name)).toList();
            if (!missing.isEmpty()) throw ApiException.badRequest("缺少必填列：" + String.join("、", missing));
            Set<String> usernames = new HashSet<>();
            for (int rowIndex = header.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                Map<String, String> values = new HashMap<>();
                for (String name : required) values.put(name, formatter.formatCellValue(row.getCell(indexes.get(name))).trim());
                if (values.values().stream().allMatch(String::isBlank)) continue;
                if (values.values().stream().anyMatch(String::isBlank)) throw ApiException.badRequest("第 " + (rowIndex + 1) + " 行存在空白必填项");
                if (!usernames.add(values.get("登录名"))) throw ApiException.badRequest("第 " + (rowIndex + 1) + " 行登录名重复：" + values.get("登录名"));
                members.add(new ApiModels.RosterMember(values.get("登录名"), values.get("姓名"), values.get("学号"), values.get("初始密码")));
                if (members.size() > 500) throw ApiException.badRequest("单次最多导入 500 名成员");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ApiException.badRequest("无法读取 Excel 文件，请使用 .xlsx 格式");
        }
        if (members.isEmpty()) throw ApiException.badRequest("Excel 中没有可导入的成员数据");
        return importRoster(account, classId, members);
    }

    @Transactional
    public Map<String, Object> createTodo(AccountPrincipal account, String classId, ApiModels.TodoCreateRequest body) {
        requireManager(account);
        String deadline = normalizeTime(body.deadline(), "截止时间格式无效");
        Map<String, Object> classroom = requireClassManager(classId, account);
        String classGroup = validatedClassGroup(classroom);
        String id = Ids.shortId();
        String now = LocalDateTime.now().toString();
        String kind = body.kind() == null || body.kind().isBlank() ? "homework" : body.kind();
        db.update("INSERT INTO todos (id, class_id, creator_id, title, description, kind, deadline, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'open', ?)",
                id, classId, account.id(), body.title(), body.description() == null ? "" : body.description(), kind, deadline, now);
        List<Map<String, Object>> members = db.list("SELECT account_id FROM class_members WHERE class_id=?", classId);
        members.forEach(member -> db.update("INSERT INTO todo_assignees (todo_id, account_id, status, updated_at) VALUES (?, ?, 'pending', ?)",
                id, member.get("account_id"), now));
        audits.write("todo.create", account.id(), String.valueOf(classroom.get("organization_id")), "todo", id,
                Map.of("title", body.title(), "assigned_count", members.size()));
        if (classGroup != null) {
            String label = "homework".equals(kind) ? "作业" : "待办";
            sendAndRecord(id, classGroup,
                    "【新" + label + "】" + body.title() + "\n截止时间：" + deadline + "\n" +
                            (body.description() == null ? "" : body.description()) + "\n请登录活动管家平台完成提交。", "publish");
        }
        return Map.of("id", id, "title", body.title(), "assigned_count", members.size());
    }

    public Map<String, Object> dashboard(AccountPrincipal account) {
        if (account.isManager()) {
            List<Map<String, Object>> classes = mutable(db.list("SELECT c.* FROM classes c JOIN class_organizations co ON co.class_id=c.id " +
                    "JOIN organization_members om ON om.organization_id=co.organization_id WHERE om.account_id=? AND om.role IN ('owner','manager') ORDER BY c.created_at DESC", account.id()));
            List<Map<String, Object>> todos = new ArrayList<>();
            for (Map<String, Object> classroom : classes) {
                long count = count("SELECT COUNT(*) AS count FROM class_members WHERE class_id=?", classroom.get("id"));
                classroom.put("member_count", count);
                List<Map<String, Object>> rows = mutable(db.list("SELECT t.*, c.name AS class_name FROM todos t JOIN classes c ON c.id=t.class_id WHERE t.class_id=? ORDER BY t.created_at DESC", classroom.get("id")));
                for (Map<String, Object> todo : rows) {
                    List<Map<String, Object>> counts = db.list("SELECT status, COUNT(*) AS count FROM todo_assignees WHERE todo_id=? GROUP BY status", todo.get("id"));
                    long done = counts.stream().filter(row -> "done".equals(row.get("status"))).mapToLong(row -> ((Number) row.get("count")).longValue()).sum();
                    long total = counts.stream().mapToLong(row -> ((Number) row.get("count")).longValue()).sum();
                    todo.put("done_count", done); todo.put("total_count", total);
                    todos.add(todo);
                }
            }
            return Map.of("role", "manager", "classes", classes, "todos", todos);
        }
        List<Map<String, Object>> todos = db.list("SELECT t.*, c.name AS class_name, ta.status AS my_status, ta.note, ta.original_filename, ta.submitted_at " +
                "FROM todo_assignees ta JOIN todos t ON t.id=ta.todo_id JOIN classes c ON c.id=t.class_id WHERE ta.account_id=? ORDER BY t.deadline", account.id());
        List<Map<String, Object>> activityTasks = mutable(db.list("SELECT t.id, t.title, t.status, t.status AS my_status, t.activity_id, ata.account_id, assigned.display_name AS assignee, " +
                "t.description, t.deadline, t.priority, t.version, a.title AS activity_title, a.status AS activity_status, a.plan_json, a.created_at " +
                "FROM activity_task_assignees ata JOIN tasks t ON t.id=ata.task_id JOIN accounts assigned ON assigned.id=ata.account_id " +
                "JOIN activities a ON a.id=t.activity_id WHERE ata.account_id=? ORDER BY a.created_at DESC", account.id()));
        for (Map<String, Object> item : activityTasks) {
            String eventTime = item.get("created_at").toString();
            try {
                if (item.get("plan_json") != null) eventTime = new com.fasterxml.jackson.databind.ObjectMapper().readTree(item.get("plan_json").toString()).path("event_time").asText(eventTime);
            } catch (Exception ignored) {}
            item.remove("plan_json"); item.put("event_time", eventTime);
        }
        return Map.of("role", "participant", "todos", todos, "activity_tasks", activityTasks);
    }

    public Map<String, Object> todoDetail(AccountPrincipal account, String todoId) {
        Map<String, Object> todo = db.one("SELECT t.*, c.name AS class_name, c.manager_id FROM todos t JOIN classes c ON c.id=t.class_id WHERE t.id=?", todoId)
                .orElseThrow(() -> ApiException.notFound("待办不存在"));
        if (account.isManager()) {
            requireClassManager(String.valueOf(todo.get("class_id")), account);
            return Map.of("todo", todo,
                    "participants", db.list("SELECT a.id AS account_id, a.display_name, a.student_no, a.username, ta.status, ta.note, ta.original_filename, ta.submitted_at " +
                            "FROM todo_assignees ta JOIN accounts a ON a.id=ta.account_id WHERE ta.todo_id=? ORDER BY a.student_no, a.display_name", todoId),
                    "reminders", db.list("SELECT * FROM todo_reminders WHERE todo_id=? ORDER BY remind_at", todoId),
                    "deliveries", db.list("SELECT * FROM classroom_deliveries WHERE todo_id=? ORDER BY created_at DESC", todoId));
        }
        Map<String, Object> assignment = db.one("SELECT * FROM todo_assignees WHERE todo_id=? AND account_id=?", todoId, account.id())
                .orElseThrow(() -> ApiException.forbidden("该待办未分配给你"));
        return Map.of("todo", todo, "assignment", assignment);
    }

    @Transactional
    public Map<String, Object> submit(AccountPrincipal account, String todoId, String note, String filename, byte[] content) {
        if (account.isManager()) throw ApiException.forbidden("只有参与者可以提交待办");
        if (db.one("SELECT 1 FROM todo_assignees WHERE todo_id=? AND account_id=?", todoId, account.id()).isEmpty())
            throw ApiException.forbidden("该待办未分配给你");
        String stored = null;
        String safeName = null;
        if (content != null && filename != null && !filename.isBlank()) {
            if (content.length > properties.uploadMaxMb() * 1024L * 1024L) throw ApiException.badRequest("文件不能超过 " + properties.uploadMaxMb() + "MB");
            safeName = Path.of(filename).getFileName().toString();
            String extension = safeName.contains(".") ? safeName.substring(safeName.lastIndexOf('.')).toLowerCase() : "";
            if (!ALLOWED_EXTENSIONS.contains(extension)) throw ApiException.badRequest("不支持该文件类型");
            // Serialize quota checks across both workers for the same account.
            db.required("SELECT id FROM accounts WHERE id=? FOR UPDATE", account.id());
            long used = count("SELECT COALESCE(SUM(size_bytes),0) AS count FROM file_assets WHERE account_id=?", account.id());
            if (used + content.length > properties.userStorageQuotaMb() * 1024L * 1024L)
                throw ApiException.badRequest("个人存储空间不能超过 " + properties.userStorageQuotaMb() + "MB");
            stored = storage.save(content, safeName, "todos/" + todoId + "/" + account.id());
        }
        String now = LocalDateTime.now().toString();
        if (stored != null) {
            String assetId = Ids.accountId();
            db.update("UPDATE todo_assignees SET status='done', note=?, file_path=?, original_filename=?, submitted_at=?, updated_at=? WHERE todo_id=? AND account_id=?",
                    note, assetId, safeName, now, now, todoId, account.id());
            String contentType;
            try { contentType = Files.probeContentType(Path.of(safeName)); } catch (Exception ignored) { contentType = null; }
            db.update("INSERT INTO file_assets (id, todo_id, account_id, storage_key, original_filename, content_type, size_bytes, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    assetId, todoId, account.id(), stored, safeName, contentType == null ? "application/octet-stream" : contentType, content.length, now);
        } else {
            db.update("UPDATE todo_assignees SET status='done', note=?, submitted_at=?, updated_at=? WHERE todo_id=? AND account_id=?", note, now, now, todoId, account.id());
        }
        String org = String.valueOf(db.required("SELECT co.organization_id FROM todos t JOIN class_organizations co ON co.class_id=t.class_id WHERE t.id=?", todoId).get("organization_id"));
        audits.write("todo.submit", account.id(), org, "todo", todoId, Map.of("has_file", stored != null));
        return Map.of("todo_id", todoId, "status", "done", "submitted_at", now);
    }

    @Transactional
    public Map<String, Object> scheduleReminder(AccountPrincipal account, String todoId, String remindAt) {
        requireManager(account);
        Map<String, Object> todo = db.one("SELECT class_id FROM todos WHERE id=?", todoId).orElseThrow(() -> ApiException.notFound("待办不存在"));
        requireClassManager(String.valueOf(todo.get("class_id")), account);
        String time = normalizeTime(remindAt, "提醒时间格式无效");
        Map<String, Object> old = db.one("SELECT * FROM todo_reminders WHERE todo_id=? AND remind_at=? AND status='scheduled'", todoId, time).orElse(null);
        if (old != null) return old;
        String id = Ids.shortId();
        db.update("INSERT INTO todo_reminders (id, todo_id, remind_at, status, created_at) VALUES (?, ?, ?, 'scheduled', ?)", id, todoId, time, LocalDateTime.now().toString());
        return Map.of("id", id, "todo_id", todoId, "remind_at", time, "status", "scheduled");
    }

    public Map<String,Object> remindMissing(AccountPrincipal account,String todoId,boolean scheduled){return remindMissing(account,todoId,scheduled,null);}
    public Map<String, Object> remindMissing(AccountPrincipal account, String todoId, boolean scheduled,String stableKey) {
        Map<String, Object> todo = db.one("SELECT t.*, c.qq_group_openid, co.organization_id FROM todos t JOIN classes c ON c.id=t.class_id " +
                "JOIN class_organizations co ON co.class_id=c.id WHERE t.id=?", todoId)
                .orElseThrow(() -> ApiException.notFound("待办不存在"));
        if (!scheduled) requireClassManager(String.valueOf(todo.get("class_id")), account);
        List<String> names = db.list("SELECT a.display_name FROM todo_assignees ta JOIN accounts a ON a.id=ta.account_id WHERE ta.todo_id=? AND ta.status!='done' ORDER BY a.student_no, a.display_name", todoId)
                .stream().map(row -> String.valueOf(row.get("display_name"))).toList();
        if (names.isEmpty()) return Map.of("status", "skipped", "message", "所有人都已完成", "missing", names);
        String classGroup = validatedClassGroup(todo);
        if (classGroup == null) throw ApiException.notFound("班级尚未绑定 QQ 群");
        Map<String, Object> delivery = sendAndRecord(todoId, classGroup,
                "【未完成提醒】" + todo.get("title") + "\n截止时间：" + todo.get("deadline") + "\n以下同学尚未完成：" + String.join("、", names) + "\n请尽快登录活动管家平台提交。",
                scheduled ? "scheduled" : "manual",stableKey);
        if ("failed".equals(delivery.get("status")))
            throw new ApiException(HttpStatus.BAD_GATEWAY, "QQ 消息发送失败：" + delivery.get("error"));
        return Map.of("status", "sent", "missing", names, "delivery", delivery);
    }

    public List<Map<String, Object>> classMembers(AccountPrincipal account, String classId) {
        requireClassManager(classId, account);
        return db.list("SELECT a.id, a.username, a.display_name, a.student_no, a.status, a.created_at FROM class_members cm JOIN accounts a ON a.id=cm.account_id WHERE cm.class_id=? ORDER BY a.student_no, a.display_name", classId);
    }

    public Map<String,Object> sendAndRecord(String todoId,String groupOpenid,String content,String kind){return sendAndRecord(todoId,groupOpenid,content,kind,null);}
    public Map<String, Object> sendAndRecord(String todoId, String groupOpenid, String content, String kind,String stableKey) {
        String key=stableKey==null?"todo:"+todoId+":"+kind+":"+ActivityGate.hash(content):stableKey;
        var previous=db.one("SELECT group_openid,content FROM outbound_deliveries WHERE logical_key=?",key).orElse(null);
        if(previous!=null) {
            if(!groupOpenid.equals(previous.get("group_openid")))throw ApiException.badRequest("提醒渠道已变化，请重新确认通知");
            content=String.valueOf(previous.get("content"));
        }
        if(deliveryLog!=null)deliveryLog.todo(todoId,key,kind,content,"sending",null,null);
        try {
            var result=qq.sendGroup(groupOpenid,content,key);
            if(deliveryLog!=null)deliveryLog.todo(todoId,key,kind,content,"sent",(String)result.get("id"),null);
            return result;
        }catch(RuntimeException error) {
            String state=error instanceof ApiException e&&e.status().value()==409?"unknown":"failed";
            if(deliveryLog!=null)deliveryLog.todo(todoId,key,kind,content,state,null,com.activityassistant.support.PublicErrors.failure(error));
            if("unknown".equals(state))throw error;
            return Map.of("status","failed","error",com.activityassistant.support.PublicErrors.failure(error));
        }
    }

    private String validatedClassGroup(Map<String, Object> classroom) {
        Object group = classroom.get("qq_group_openid");
        if (group == null) return null;
        Object organizationId = classroom.get("organization_id");
        if (organizationId == null || !qq.isGroupAuthorized(String.valueOf(organizationId), String.valueOf(group))) {
            throw ApiException.badRequest("班级 QQ 群绑定已失效，请重新选择本组织授权的群");
        }
        return String.valueOf(group);
    }

    private String ensureOrganization(AccountPrincipal account) {
        String organization = auth.organizationOf(account.id());
        if (organization != null) return organization;
        if (!account.isManager()) throw ApiException.forbidden("账号尚未加入组织");
        String id = Ids.shortId();
        String now = LocalDateTime.now().toString();
        db.update("INSERT INTO organizations (id, name, owner_account_id, created_at) VALUES (?, ?, ?, ?)", id, account.username() + " 的组织", account.id(), now);
        db.update("INSERT INTO organization_members (organization_id, account_id, role, created_at) VALUES (?, ?, 'owner', ?)", id, account.id(), now);
        return id;
    }

    private void requireManager(AccountPrincipal account) {
        if (!account.isManager()) throw ApiException.forbidden("只有管理者可以执行此操作");
    }

    private String normalizeTime(String value, String message) {
        try {
            if (value.endsWith("Z") || value.matches(".*[+-]\\d{2}:\\d{2}$")) return OffsetDateTime.parse(value).toString();
            return LocalDateTime.parse(value).toString();
        } catch (Exception exception) {
            throw ApiException.badRequest(message);
        }
    }

    private long count(String sql, Object... args) {
        return ((Number) db.required(sql, args).get("count")).longValue();
    }

    private List<Map<String, Object>> mutable(List<Map<String, Object>> rows) {
        return rows.stream().map(LinkedHashMap::new).map(row -> (Map<String, Object>) row).toList();
    }
}
