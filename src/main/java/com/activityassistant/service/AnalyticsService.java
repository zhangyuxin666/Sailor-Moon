package com.activityassistant.service;

import com.activityassistant.support.ApiException;
import com.activityassistant.persistence.mapper.AnalyticsMapper;
import com.activityassistant.persistence.mapper.AnalyticsMapper.Scope;
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
    private final AuthService auth;
    private final AnalyticsMapper mapper;

    public AnalyticsService(AuthService auth, AnalyticsMapper mapper) {
        this.auth = auth;
        this.mapper = mapper;
    }

    public Map<String, Object> overview(AccountPrincipal actor, int days, String classId) {
        if (!actor.isManager()) throw new ApiException(HttpStatus.FORBIDDEN, "仅管理员可以查看数据大屏");
        if (days != 7 && days != 30 && days != 90) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "统计周期只能是 7、30 或 90 天");
        }
        String organizationId = auth.organizationOf(actor.id());
        if (organizationId == null) throw new ApiException(HttpStatus.FORBIDDEN, "账号尚未加入组织");
        if (classId != null && !classId.isBlank() && !mapper.containsClass(organizationId, classId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权查看该班级");
        }
        String selectedClass = classId == null || classId.isBlank() ? null : classId;
        Scope scope = new Scope(organizationId, selectedClass);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generated_at", OffsetDateTime.now().toString());
        result.put("days", days);
        result.put("classes", mapper.findClasses(organizationId));
        Map<String, Object> participation = mapper.participation(scope);
        result.put("summary", summary(scope, participation));
        result.put("trend", trend(scope, days));
        result.put("activity_status", mapper.activityStatuses(scope));
        result.put("task_status", mapper.taskStatuses(scope));
        result.put("participation", participation);
        result.put("alerts", alerts(scope));
        return result;
    }

    private Map<String, Object> summary(Scope scope, Map<String, Object> attendance) {
        long activities = mapper.countActivities(scope);
        long active = mapper.countActiveActivities(scope);
        long members = mapper.countMembers(scope);
        long registrations = mapper.countRegistrations(scope);
        Map<String, Object> tasks = mapper.taskTotals(scope);
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
        LocalDate today = LocalDate.now();
        String start = today.minusDays(days - 1).toString();
        List<Map<String, Object>> activityRows = mapper.activityTrend(scope, start);
        List<Map<String, Object>> registrationRows = mapper.registrationTrend(scope, start);
        Map<String, Long> activities = indexed(activityRows);
        Map<String, Long> registrations = indexed(registrationRows);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int offset = days - 1; offset >= 0; offset--) {
            String day = today.minusDays(offset).toString();
            result.add(Map.of("date", day, "activities", activities.getOrDefault(day, 0L),
                    "registrations", registrations.getOrDefault(day, 0L)));
        }
        return result;
    }

    private List<Map<String, Object>> alerts(Scope scope) {
        long overdue = mapper.countOverdue(scope, OffsetDateTime.now().toString());
        long unassigned = mapper.countUnassigned(scope);
        long failedDelivery = mapper.countFailedDeliveries(scope);
        long failedReminder = mapper.countFailedReminders(scope);
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

    private long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private double percent(long value, long total) { return total == 0 ? 0 : Math.round(value * 1000.0 / total) / 10.0; }
    private Map<String, Long> indexed(List<Map<String, Object>> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(String.valueOf(row.get("trend_date")), number(row.get("n"))));
        return result;
    }
}
