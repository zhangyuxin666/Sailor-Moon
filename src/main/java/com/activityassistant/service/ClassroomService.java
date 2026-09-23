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
        List<Map<String, Object>> bindings = db.list("SELECT group_openid FROM qq_bindings WHERE user_id=? AND status='active' ORDER BY created_at", account.username());
        if (bindings.isEmpty()) {
            List<Map<String, Object>> all = db.list("SELECT group_openid FROM qq_bindings WHERE status='active' ORDER BY created_at");
            if (all.size() == 1) bindings = all;
        }
        String group = bindings.isEmpty() ? null : String.valueOf(bindings.get(0).get("group_openid"));
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

    @Transactional
    public Map<String, Object> importRoster(AccountPrincipal account, String classId, List<ApiModels.RosterMember> members) {
        requireManager(account);
        Map<String, Object> classroom = requireClassManager(classId, account);
        List<Map<String, Object>> created = new ArrayList<>();
        String now = LocalDateTime.now().toString();
        for (ApiModels.RosterMember item : members) {
            Map<String, Object> member = db.one("SELECT * FROM accounts WHERE username=?", item.username().trim()).orElse(null);
            String memberId;
            if (member != null) {
                if (!"participant".equals(member.get("role"))) throw ApiException.badRequest("登录名 " + item.username() + " 已被管理者占用");
                memberId = String.valueOf(member.get("id"));
            } else {
                memberId = auth.createAccount(item.username(), item.displayName(), item.password(), "participant", item.studentNo());
                db.update("INSERT INTO account_security (account_id, force_password_change, failed_logins, password_changed_at) VALUES (?, 1, 0, ?)", memberId, now);
            }
            db.update("INSERT INTO organization_members (organization_id, account_id, role, created_at) VALUES (?, ?, 'participant', ?) ON CONFLICT DO NOTHING",
                    classroom.get("organization_id"), memberId, now);
            db.update("INSERT INTO class_members (class_id, account_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", classId, memberId, now);
            created.add(Map.of("id", memberId, "username", item.username(), "display_name", item.displayName(),
                    "student_no", item.studentNo() == null ? "" : item.studentNo()));
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
        if (classroom.get("qq_group_openid") != null) {
            String label = "homework".equals(kind) ? "作业" : "待办";
            sendAndRecord(id, String.valueOf(classroom.get("qq_group_openid")),
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
        List<Map<String, Object>> activityTasks = mutable(db.list("SELECT t.id, t.title, t.status AS my_status, a.title AS activity_title, a.plan_json, a.created_at " +
                "FROM activity_task_assignees ata JOIN tasks t ON t.id=ata.task_id JOIN activities a ON a.id=t.activity_id WHERE ata.account_id=? ORDER BY a.created_at DESC", account.id()));
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

    public Map<String, Object> remindMissing(AccountPrincipal account, String todoId, boolean scheduled) {
        Map<String, Object> todo = db.one("SELECT t.*, c.qq_group_openid FROM todos t JOIN classes c ON c.id=t.class_id WHERE t.id=?", todoId)
                .orElseThrow(() -> ApiException.notFound("待办不存在"));
        if (!scheduled) requireClassManager(String.valueOf(todo.get("class_id")), account);
        List<String> names = db.list("SELECT a.display_name FROM todo_assignees ta JOIN accounts a ON a.id=ta.account_id WHERE ta.todo_id=? AND ta.status!='done' ORDER BY a.student_no, a.display_name", todoId)
                .stream().map(row -> String.valueOf(row.get("display_name"))).toList();
        if (names.isEmpty()) return Map.of("status", "skipped", "message", "所有人都已完成", "missing", names);
        if (todo.get("qq_group_openid") == null) throw ApiException.notFound("班级尚未绑定 QQ 群");
        Map<String, Object> delivery = sendAndRecord(todoId, String.valueOf(todo.get("qq_group_openid")),
                "【未完成提醒】" + todo.get("title") + "\n截止时间：" + todo.get("deadline") + "\n以下同学尚未完成：" + String.join("、", names) + "\n请尽快登录活动管家平台提交。",
                scheduled ? "scheduled" : "manual");
        if ("failed".equals(delivery.get("status")))
            throw new ApiException(HttpStatus.BAD_GATEWAY, "QQ 消息发送失败：" + delivery.get("error"));
        return Map.of("status", "sent", "missing", names, "delivery", delivery);
    }

    public List<Map<String, Object>> classMembers(AccountPrincipal account, String classId) {
        requireClassManager(classId, account);
        return db.list("SELECT a.id, a.username, a.display_name, a.student_no, a.status, a.created_at FROM class_members cm JOIN accounts a ON a.id=cm.account_id WHERE cm.class_id=? ORDER BY a.student_no, a.display_name", classId);
    }

    public Map<String, Object> sendAndRecord(String todoId, String groupOpenid, String content, String kind) {
        String id = Ids.shortId();
        String now = LocalDateTime.now().toString();
        try {
            Map<String, Object> result = qq.sendGroup(groupOpenid, content,
                    "todo:" + todoId + ":" + kind + ":" + Integer.toHexString(content.hashCode()));
            db.update("INSERT INTO classroom_deliveries (id, todo_id, kind, content, status, external_id, created_at) VALUES (?, ?, ?, ?, 'sent', ?, ?)",
                    id, todoId, kind, content, result.get("id"), now);
            return result;
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            db.update("INSERT INTO classroom_deliveries (id, todo_id, kind, content, status, error, created_at) VALUES (?, ?, ?, ?, 'failed', ?, ?)",
                    id, todoId, kind, content, message, now);
            return Map.of("status", "failed", "error", message);
        }
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
