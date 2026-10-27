package com.activityassistant.api;

import com.activityassistant.service.*;
import com.activityassistant.persistence.Db;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class ManagementController {
    private final ActivityManagementService activities;private final RegistrationService registrations;private final TaskManagementService tasks;private final Db db;
    public ManagementController(ActivityManagementService activities,RegistrationService registrations,TaskManagementService tasks,Db db){this.activities=activities;this.registrations=registrations;this.tasks=tasks;this.db=db;}
    @GetMapping("/notifications")
    public Map<String,Object> notifications(Authentication auth){return Map.of("notifications",db.list("SELECT id,activity_id,content,created_at FROM messages WHERE recipient_account_id=? ORDER BY id DESC LIMIT 100",AuthController.required(auth).id()));}
    @PutMapping("/activities/{id}/registration-settings")
    public Map<String,Object> settings(Authentication auth,@PathVariable String id,@RequestBody JsonNode input){return registrations.settings(id,AuthController.required(auth),input);}
    @PostMapping("/forms/{formId}/registrations/{registrationId}/receipt")
    public Map<String,Object> receipt(@PathVariable String formId,@PathVariable long registrationId,@RequestBody JsonNode input){return registrations.receipt(formId,registrationId,input.path("cancellation_token").asText());}
    @PostMapping("/activities/{id}/registrations/{registrationId}/cancel")
    public Map<String,Object> cancelRegistration(Authentication auth,@PathVariable String id,@PathVariable long registrationId,@RequestBody JsonNode input){return registrations.cancelByOwner(id,registrationId,AuthController.required(auth),input);}
    @PostMapping("/forms/{formId}/registrations/{registrationId}/cancel")
    public Map<String,Object> withdraw(@PathVariable String formId,@PathVariable long registrationId,@RequestBody JsonNode input){return registrations.cancel(formId,registrationId,input.path("cancellation_token").asText());}
    @PostMapping("/activities/{id}/cancel")
    public Map<String,Object> cancel(Authentication auth,@PathVariable String id,@RequestBody JsonNode input){return activities.cancel(id,AuthController.required(auth),input);}
    @PostMapping("/runs/{runId}/cancel")
    public Map<String,Object> cancelRun(Authentication auth,@PathVariable String runId,@RequestBody JsonNode input){
        var run=db.one("SELECT * FROM agent_runs WHERE id=?",runId).orElseThrow(()->ApiException.notFound("运行不存在"));
        if(!"activity".equals(run.get("kind")))throw ApiException.forbidden("只能取消活动运行");
        var actor=AuthController.required(auth);
        var owner=db.one("SELECT user_id FROM activities WHERE id=?",run.get("ref_id")).orElseThrow(()->ApiException.notFound("活动不存在"));
        if(!actor.isManager() || !actor.username().equals(owner.get("user_id")))throw ApiException.forbidden("只能取消自己的运行");
        if("succeeded".equals(run.get("status")))throw ApiException.badRequest("成功运行已结束，请使用活动取消入口");
        return activities.cancel(String.valueOf(run.get("ref_id")),AuthController.required(auth),input);
    }
    @PostMapping("/activities/{id}/reschedule")
    public Map<String,Object> reschedule(Authentication auth,@PathVariable String id,@RequestBody JsonNode input){return activities.reschedule(id,AuthController.required(auth),input);}
    @GetMapping("/activities/{id}/changes")
    public Map<String,Object> changes(Authentication auth,@PathVariable String id){return Map.of("changes",activities.changes(id,AuthController.required(auth)));}
    @PostMapping("/activities/{id}/changes/{changeId}/retry")
    public Map<String,Object> retry(Authentication auth,@PathVariable String id,@PathVariable String changeId){return activities.retry(id,changeId,AuthController.required(auth));}
    @GetMapping("/activities/{id}/task-management")
    public Map<String,Object> taskView(Authentication auth,@PathVariable String id){return tasks.view(id,AuthController.required(auth));}
    @PostMapping("/activities/{id}/tasks")
    public Map<String,Object> createTask(Authentication auth,@PathVariable String id,@RequestBody JsonNode input){return tasks.save(id,null,AuthController.required(auth),input);}
    @PutMapping("/activities/{id}/tasks/{taskId}")
    public Map<String,Object> editTask(Authentication auth,@PathVariable String id,@PathVariable String taskId,@RequestBody JsonNode input){return tasks.save(id,taskId,AuthController.required(auth),input);}
}
