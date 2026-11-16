package com.activityassistant.service;

import com.activityassistant.support.ApiException;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 组织数据大屏：只统计当前管理员所属组织的数据。 */
@Service
public class AnalyticsService {
    private final Db db;
    private final AuthService auth;

    public AnalyticsService(Db db, AuthService auth) {
        this.db = db;
        this.auth = auth;
    }

    public Map<String, Object> overview(AccountPrincipal actor, int days, String classId) {
        if (!actor.isManager()) throw new ApiException(HttpStatus.FORBIDDEN, "仅管理员可以查看数据大屏");
        if (days != 7 && days != 30 && days != 90) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "统计周期只能是 7、30 或 90 天");
        }
        String organizationId = auth.organizationOf(actor.id());
        if (organizationId == null) throw new ApiException(HttpStatus.FORBIDDEN, "账号尚未加入组织");
        if (classId != null && !classId.isBlank() && db.one(
                "SELECT 1 FROM class_organizations WHERE organization_id=? AND class_id=?", organizationId, classId).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权查看该班级");
        }
        String selectedClass = classId == null || classId.isBlank() ? null : classId;
        Scope scope = new Scope(organizationId, selectedClass);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generated_at", OffsetDateTime.now().toString());
        result.put("days", days);
        result.put("classes", classes(organizationId));
        result.put("summary", summary(scope));
        result.put("trend", trend(scope, days));
        result.put("activity_status", activityStatuses(scope));
        result.put("task_status", taskStatuses(scope));
        result.put("participation", participation(scope));
        result.put("alerts", alerts(scope));
        return result;
    }

    private List<Map<String, Object>> classes(String organizationId) {
        return db.list("SELECT c.id,c.name FROM classes c JOIN class_organizations co ON co.class_id=c.id " +
                "WHERE co.organization_id=? ORDER BY c.name", organizationId);
    }

    private Map<String, Object> summary(Scope scope) {
        String cte = scope.cte();
        Object[] args = scope.args();
        long activities = count(cte + " SELECT count(*) n FROM scoped", args);
        long active = count(cte + " SELECT count(*) n FROM scoped WHERE status NOT IN ('finished','cancelled','failed')", args);
        long members = count("SELECT count(DISTINCT cm.account_id) n FROM class_members cm JOIN class_organizations co ON co.class_id=cm.class_id " +
                "WHERE co.organization_id=?" + (scope.hasClass() ? " AND cm.class_id=?" : ""), args);
        long registrations = count(cte + " SELECT count(*) n FROM registrations r JOIN forms f ON f.id=r.form_id " +
                "JOIN scoped s ON s.id=f.activity_id WHERE r.status='active'", args);
        Map<String, Object> tasks = db.required(cte + " SELECT count(t.id) total,count(t.id) FILTER (WHERE t.status='done') done " +
                "FROM tasks t JOIN scoped s ON s.id=t.activity_id", args);
        Map<String, Object> attendance = db.required(cte + " SELECT count(DISTINCT p.account_id) participants,count(DISTINCT ci.account_id) checked_in " +
                "FROM scoped s LEFT JOIN activity_participants p ON p.activity_id=s.id " +
                "LEFT JOIN activity_check_ins ci ON ci.activity_id=s.id AND ci.account_id=p.account_id", args);
        long taskTotal = number(tasks.get("total"));
        long taskDone = number(tasks.get("done"));
        long participants = number(attendance.get("participants"));
        long checkedIn = number(attendance.get("checked_in"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("activities", activities);
        summary.put("active_activities", active);
        summary.put("members", members);
        summary.put("registrations", registrations);
        summary.put("task_completion_rate", percent(taskDone, taskTotal));
        summary.put("attendance_rate", percent(checkedIn, participants));
        return summary;
    }

    private List<Map<String, Object>> trend(Scope scope, int days) {
        String cte = scope.cte();
        Object[] args = scope.args();
        List<Map<String, Object>> activityRows = db.list(cte + " SELECT substring(created_at,1,10) AS trend_date,count(*) n FROM scoped " +
                "WHERE substring(created_at,1,10)>=CAST(? AS text) GROUP BY 1", append(args, LocalDate.now().minusDays(days - 1).toString()));
        List<Map<String, Object>> registrationRows = db.list(cte + " SELECT substring(r.created_at,1,10) AS trend_date,count(*) n FROM registrations r " +
                "JOIN forms f ON f.id=r.form_id JOIN scoped s ON s.id=f.activity_id WHERE r.status='active' " +
                "AND substring(r.created_at,1,10)>=CAST(? AS text) GROUP BY 1", append(args, LocalDate.now().minusDays(days - 1).toString()));
        Map<String, Long> activities = indexed(activityRows);
        Map<String, Long> registrations = indexed(registrationRows);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int offset = days - 1; offset >= 0; offset--) {
            String day = LocalDate.now().minusDays(offset).toString();
            result.add(Map.of("date", day, "activities", activities.getOrDefault(day, 0L),
                    "registrations", registrations.getOrDefault(day, 0L)));
        }
        return result;
    }

    private List<Map<String, Object>> activityStatuses(Scope scope) {
        return db.list(scope.cte() + " SELECT status,count(*) value FROM scoped GROUP BY status ORDER BY value DESC", scope.args());
    }

    private List<Map<String, Object>> taskStatuses(Scope scope) {
        return db.list(scope.cte() + " SELECT CASE WHEN trim(coalesce(t.assignee,''))='' THEN 'unassigned' ELSE t.status END status," +
                "count(*) value FROM tasks t JOIN scoped s ON s.id=t.activity_id GROUP BY 1 ORDER BY value DESC", scope.args());
    }

    private Map<String, Object> participation(Scope scope) {
        Map<String, Object> row = db.required(scope.cte() + " SELECT count(DISTINCT p.account_id) participants," +
                "count(DISTINCT ci.account_id) checked_in,count(DISTINCT f.account_id) feedback_count," +
                "coalesce(round(avg(f.rating),1),0) average_rating FROM scoped s " +
                "LEFT JOIN activity_participants p ON p.activity_id=s.id " +
                "LEFT JOIN activity_check_ins ci ON ci.activity_id=s.id AND ci.account_id=p.account_id " +
                "LEFT JOIN activity_feedback f ON f.activity_id=s.id AND f.account_id=p.account_id", scope.args());
        return row;
    }

    private List<Map<String, Object>> alerts(Scope scope) {
        String cte = scope.cte();
        Object[] args = scope.args();
        long overdue = count(cte + " SELECT count(*) n FROM tasks t JOIN scoped s ON s.id=t.activity_id " +
                "WHERE t.status<>'done' AND t.deadline IS NOT NULL AND t.deadline<>'' AND t.deadline<?", append(args, OffsetDateTime.now().toString()));
        long unassigned = count(cte + " SELECT count(*) n FROM tasks t JOIN scoped s ON s.id=t.activity_id " +
                "WHERE t.status<>'done' AND trim(coalesce(t.assignee,''))=''", args);
        long failedDelivery = count(cte + " SELECT count(*) n FROM delivery_events d JOIN scoped s ON s.id=d.activity_id WHERE d.status='failed'", args);
        long failedReminder = count(cte + " SELECT count(*) n FROM reminders r JOIN scoped s ON s.id=r.activity_id WHERE r.status='failed'", args);
        List<Map<String, Object>> alerts = new ArrayList<>();
        alerts.add(alert("overdue", "逾期任务", overdue, "存在已过截止时间但尚未完成的任务"));
        alerts.add(alert("unassigned", "未分配任务", unassigned, "任务尚未指定负责人"));
        alerts.add(alert("delivery_failed", "消息发送失败", failedDelivery, "消息渠道返回失败，需要重试"));
        alerts.add(alert("reminder_failed", "提醒执行失败", failedReminder, "定时提醒未成功执行"));
        return alerts;
    }

    private Map<String, Object> alert(String code, String title, long count, String description) {
        return Map.of("code", code, "title", title, "count", count, "description", description);
    }

    private long count(String sql, Object... args) { return number(db.required(sql, args).get("n")); }
    private long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private double percent(long value, long total) { return total == 0 ? 0 : Math.round(value * 1000.0 / total) / 10.0; }
    private Map<String, Long> indexed(List<Map<String, Object>> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(String.valueOf(row.get("trend_date")), number(row.get("n"))));
        return result;
    }
    private Object[] append(Object[] values, Object last) {
        Object[] result = java.util.Arrays.copyOf(values, values.length + 1);
        result[values.length] = last;
        return result;
    }

    private record Scope(String organizationId, String classId) {
        boolean hasClass() { return classId != null; }
        String cte() {
            return "WITH scoped AS (SELECT DISTINCT a.id,a.status,a.created_at FROM activities a " +
                    "JOIN activity_classes ac ON ac.activity_id=a.id WHERE ac.organization_id=?" +
                    (hasClass() ? " AND ac.class_id=?" : "") + ")";
        }
        Object[] args() { return hasClass() ? new Object[]{organizationId, classId} : new Object[]{organizationId}; }
    }
}
