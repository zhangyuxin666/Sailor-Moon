package com.activityassistant.api;

import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.ActivityService;
import com.activityassistant.service.BackgroundJobService;
import com.activityassistant.service.ClassroomService;
import com.activityassistant.service.QqService;
import com.activityassistant.support.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class ActivityController {
    private final ActivityService activities;
    private final BackgroundJobService jobs;
    private final ClassroomService classroom;
    private final QqService qq;

    public ActivityController(ActivityService activities, BackgroundJobService jobs,
                              ClassroomService classroom, QqService qq) {
        this.activities = activities;
        this.jobs = jobs;
        this.classroom = classroom;
        this.qq = qq;
    }

    @PostMapping("/activities")
    ResponseEntity<Map<String, Object>> create(Authentication authentication,
                                                @Valid @RequestBody ApiModels.ActivityCreateRequest body) {
        AccountPrincipal account = AuthController.required(authentication);
        if (!account.isManager()) throw ApiException.forbidden("只有管理者可以发起活动");
        if (body.classId() == null || body.classId().isBlank()) throw ApiException.badRequest("请选择活动所属班级");
        classroom.requireClassManager(body.classId(), account);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(activities.queue(account, body.text(), body.publishToQq() == null || body.publishToQq(), body.classId(), null));
    }

    @GetMapping("/portal/activities")
    Map<String, Object> list(Authentication authentication) {
        return Map.of("activities", activities.list(AuthController.required(authentication)));
    }

    @GetMapping("/integrations/qq/status")
    Map<String, Object> qqStatus(Authentication authentication) {
        return qq.status(AuthController.required(authentication).username());
    }

    @PostMapping("/activities/{activityId}/messages")
    Map<String, Object> message(Authentication authentication, @PathVariable String activityId,
                                @Valid @RequestBody ApiModels.ActivityMessageRequest body) {
        AccountPrincipal account = AuthController.required(authentication);
        if (!account.isManager()) throw ApiException.forbidden("只有管理者可以发送活动通知");
        return activities.sendManual(activityId, account.username(), body.content(), body.requestId());
    }

    @GetMapping("/public/forms/{formId}")
    Map<String, Object> publicForm(@PathVariable String formId) {
        return activities.publicForm(formId);
    }

    @PostMapping("/forms/{formId}/registrations")
    Map<String, Object> register(@PathVariable String formId,
                                 @Valid @RequestBody ApiModels.RegistrationRequest body) {
        return activities.registration(formId, body.name(), body.contact(), body.extra());
    }

    @GetMapping("/runs/{runId}")
    Map<String, Object> run(Authentication authentication, @PathVariable String runId) {
        AccountPrincipal account = AuthController.required(authentication);
        Map<String, Object> run = jobs.get(runId);
        if (run == null) throw ApiException.notFound("执行记录不存在: " + runId);
        if ("activity".equals(run.get("kind"))) activities.requireOwner(String.valueOf(run.get("ref_id")), account.username());
        else throw ApiException.forbidden("不能查看不属于当前账号的执行记录");
        return run;
    }

    @GetMapping("/activities/{activityId}")
    Map<String, Object> activity(Authentication authentication, @PathVariable String activityId) {
        return activities.detail(activityId, AuthController.required(authentication).username());
    }

    @GetMapping("/activities/{activityId}/form-stats")
    Map<String, Object> stats(Authentication authentication, @PathVariable String activityId) {
        return activities.formStats(activityId, AuthController.required(authentication).username());
    }

    @PostMapping("/activities/{activityId}/recap")
    Map<String, Object> recap(Authentication authentication, @PathVariable String activityId) {
        return activities.recap(activityId, AuthController.required(authentication).username());
    }

    @PostMapping("/reminders/{reminderId}/cancel")
    Map<String, Object> cancel(Authentication authentication, @PathVariable String reminderId) {
        return activities.cancelReminder(reminderId, AuthController.required(authentication).username());
    }

    @PatchMapping("/tasks/{taskId}")
    Map<String, Object> updateTask(Authentication authentication, @PathVariable String taskId,
                                   @Valid @RequestBody ApiModels.TaskUpdateRequest body) {
        return activities.updateTask(taskId, body.status(), AuthController.required(authentication));
    }

    @GetMapping(value = "/activities/{activityId}/calendar.ics", produces = "text/calendar;charset=UTF-8")
    ResponseEntity<byte[]> calendar(Authentication authentication, @PathVariable String activityId) {
        String ics = activities.calendar(activityId, AuthController.required(authentication).username());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"activity.ics\"")
                .body(ics.getBytes(StandardCharsets.UTF_8));
    }
}
