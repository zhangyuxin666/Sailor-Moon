package com.activityassistant.service;

import com.activityassistant.persistence.ReplicaReads;
import com.activityassistant.persistence.mapper.ActivityManagementMapper;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.*;
import com.activityassistant.orchestration.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ActivityManagementService {
    private final ReplicaReads replicaReads;
    private final ActivityGate gate;
    private final ActivityManagementMapper repository;
    public ActivityManagementService(ReplicaReads replicaReads, ActivityGate gate, ActivityManagementMapper repository) {
        this.replicaReads = replicaReads;
        this.gate = gate;
        this.repository = repository;
    }
    public static String calendar(String id,String title,String eventTime,String description,int version,boolean cancelled,String timezone) {
        return String.join("\r\n","BEGIN:VCALENDAR","VERSION:2.0","PRODID:-//Sailor-Moon//Activity Assistant//CN","METHOD:PUBLISH","BEGIN:VEVENT",
            "UID:"+id+"@sailor-moon","SEQUENCE:"+version,"STATUS:"+(cancelled?"CANCELLED":"CONFIRMED"),
            "DTSTAMP:"+DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()),
            "DTSTART:"+Times.ics(eventTime,timezone),"SUMMARY:"+Times.escapeIcs(title),"DESCRIPTION:"+Times.escapeIcs(description),"END:VEVENT","END:VCALENDAR","");
    }
    public static String calendarWithUid(String id,String title,String time,String description,int version,boolean cancelled,String old,String timezone) {
        String value=calendar(id,title,time,description,version,cancelled,timezone);
        for(String line:old.split("\\r?\\n"))if(line.startsWith("UID:"))return value.replace("UID:"+id+"@sailor-moon",line);
        return value;
    }
    @Transactional
    public Map<String,Object> cancel(String id,AccountPrincipal actor,JsonNode input) {
        Map<String,Object> activity=gate.owner(id,actor);String request=input.path("request_id").asText();
        var replay=gate.replay(id,request,"cancel",input);if(replay!=null)return replay;
        if("cancelled".equals(activity.get("status")))return Map.of("activity_id",id,"status","cancelled","idempotent",true);
        if("finished".equals(activity.get("status")))throw ApiException.badRequest("已结束活动不能取消");
        String reason=input.path("reason").asText("").strip();if(reason.isBlank() || reason.length()>1000)throw ApiException.badRequest("请填写最多1000字的取消原因");
        String now=LocalDateTime.now().toString();
        // Activity first, then run/job/form/reminder: same lock order as execution and supplemental input.
        repository.cancelActivity(now,reason,id);
        AgentResult result=new AgentResult(RunStatus.CANCELLED,Map.of("activity_id",id,"reason",reason),List.of(),List.of(),null);
        repository.cancelRun(now,gate.json(result),now,id);
        repository.cancelActivityJobs(now,id);
        repository.closeForms(id);
        repository.closeParticipation(id);
        repository.cancelReminderJobs(now,id);
        repository.cancelReminders(id);
        return gate.change(id,actor,request,"cancel",input,Map.of("activity_id",id,"status","cancelled","version",((Number)activity.get("version")).intValue()+1),
            "【活动取消】"+activity.get("title")+"："+reason+"。此前已完成和已投递的操作仍保留，取消不代表撤回。",gate.participants(id));
    }
    @Transactional
    public Map<String,Object> reschedule(String id,AccountPrincipal actor,JsonNode input) {
        Map<String,Object> activity=gate.owner(id,actor);String request=input.path("request_id").asText();
        var replay=gate.replay(id,request,"reschedule",input);if(replay!=null)return replay;gate.active(activity);
        if(!"ready".equals(activity.get("status")))throw ApiException.badRequest("仅已就绪的活动可以改期，执行中请等待或取消");
        gate.version(activity,input.path("expected_version"));
        String time=gate.time(input.path("event_time").asText()).toString();if(!gate.time(time).isAfter(Instant.now()))throw ApiException.badRequest("新活动时间必须在未来");
        ObjectNode plan=(ObjectNode)gate.parse(activity.get("plan_json"));String previous=plan.path("event_time").asText();
        if(!previous.isBlank() && gate.time(previous).equals(gate.time(time)))return Map.of("activity_id",id,"status","unchanged","idempotent",true);
        String reason=input.path("reason").asText("").strip();if(reason.isBlank() || reason.length()>1000)throw ApiException.badRequest("请填写最多1000字的改期原因");
        String deadline=input.path("registration_deadline").asText("");String policy=input.path("deadline_policy").asText("keep");
        if(!Set.of("keep","set","clear").contains(policy))throw ApiException.badRequest("请选择报名截止时间处理方式");
        if("set".equals(policy)) {deadline=gate.time(deadline).toString();if(gate.time(deadline).isAfter(gate.time(time)))throw ApiException.badRequest("报名截止时间不得晚于新活动时间");}
        if("keep".equals(policy))for(var f:repository.listDeadlines(id))if(f.get("registration_deadline")!=null && gate.time(String.valueOf(f.get("registration_deadline"))).isAfter(gate.time(time)))throw ApiException.badRequest("原报名截止晚于新活动时间，请同时调整截止时间");
        int version=((Number)activity.get("version")).intValue()+1;
        plan.put("event_time",time);repository.rescheduleActivity(gate.json(plan),version,id);
        // Update both plan and stored success data; clients must not see an old event time in the run protocol.
        for(var run:repository.listRuns(id)) {
            ObjectNode resolution=(ObjectNode)gate.parse(run.get("resolution_json"));ObjectNode result=(ObjectNode)gate.parse(run.get("result_json"));
            if(resolution.path("data").isObject())((ObjectNode)resolution.path("data")).set("plan",plan);
            result.set("plan",plan);repository.updateRunPlan(gate.json(plan),gate.json(resolution),gate.json(result),LocalDateTime.now().toString(),run.get("id"));
        }
        repository.invalidateReminderJobs(id);
        List<Map<String,Object>> reminders=repository.listScheduledReminders(id);
        repository.cancelReminders(id);
        if(!reminders.isEmpty()) {
            String remindAt=LocalDateTime.ofInstant(gate.time(time).minusSeconds(plan.path("remind_minutes_before").asLong(60)*60),ZoneId.of(gate.timezone())).toString();
            repository.upsertReminder(Ids.shortId(),id,"活动「"+activity.get("title")+"」改期至 "+time+"，请按新时间参加。",remindAt,LocalDateTime.now().toString());
            // A formerly used reminder time is reactivated with its existing ID; reset that logical job explicitly.
            repository.requeueReminderJob(remindAt,id,remindAt);
        }
        if("set".equals(policy))repository.setDeadline(deadline,id);
        if("clear".equals(policy))repository.clearDeadline(id);
        String oldIcs=repository.findCalendar("cal:"+id)
                .map(r->gate.parse(r.get("result_json")).path("ics").asText()).orElse("");
        String ics=calendarWithUid(id,String.valueOf(activity.get("title")),time,plan.path("description").asText(),version,false,oldIcs,gate.timezone());
        repository.updateCalendar(gate.json(Map.of("ics",ics,"event_time",time,"title",activity.get("title"))),"cal:"+id);
        Map<String,Object> result=new LinkedHashMap<>();result.put("activity_id",id);result.put("version",version);result.put("event_time",time);result.put("previous_event_time",previous);result.put("deadline_policy",policy);
        return gate.change(id,actor,request,"reschedule",input,result,"【活动改期】"+activity.get("title")+"：原时间 "+previous+"，新时间 "+time+"；原因："+reason+"。报名截止处理："+policy,gate.participants(id));
    }
    public List<Map<String,Object>> changes(String id,AccountPrincipal actor) {
        var activity=repository.findActivity(id).orElseThrow(()->ApiException.notFound("活动不存在"));
        if(!actor.isManager() || !actor.username().equals(activity.get("user_id")))throw ApiException.forbidden("无权查看活动变更");
        List<Map<String,Object>> rows=replicaReads.readMapper(ActivityManagementMapper.class, repository, mapper -> mapper.listChanges(id));
        for(var row:rows){row.put("result",gate.parse(row.remove("result_json")));row.put("error",PublicErrors.message(row.get("error")));}return rows;
    }
    @Transactional
    public Map<String,Object> retry(String id,String changeId,AccountPrincipal actor) {
        var activity=gate.owner(id,actor);
        var event=repository.lockChange(changeId,id).orElseThrow(()->ApiException.notFound("变更记录不存在"));
        if("cancelled".equals(activity.get("status")) && !"cancel".equals(event.get("kind")))throw ApiException.badRequest("活动已取消，无需继续旧变更通知");
        if("sent".equals(event.get("notification_status")) || "local".equals(event.get("notification_status")))return Map.of("change_id",changeId,"idempotent",true);
        repository.requeueChangeJob(changeId);
        repository.markChangePending(changeId);
        return Map.of("change_id",changeId,"status","pending");
    }
}
