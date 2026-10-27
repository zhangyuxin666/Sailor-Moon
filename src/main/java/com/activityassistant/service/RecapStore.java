package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class RecapStore {
    private final Db db;private final ActivityGate gate;private final ObjectMapper mapper;private final ClassroomService classroom;
    public RecapStore(Db db,ActivityGate gate,ObjectMapper mapper,ClassroomService classroom){this.db=db;this.gate=gate;this.mapper=mapper;this.classroom=classroom;}
    private Map<String,Object> owner(String id,AccountPrincipal actor){
        var activity=gate.owner(id,actor);
        if(db.one("SELECT 1 FROM activity_classes WHERE activity_id=?",id).isPresent()&&db.one("SELECT 1 FROM activity_classes ac JOIN class_organizations co ON co.class_id=ac.class_id AND co.organization_id=ac.organization_id WHERE ac.activity_id=?",id).isEmpty())throw ApiException.forbidden("活动组织范围已变化，请联系管理员");
        db.one("SELECT class_id FROM activity_classes WHERE activity_id=?",id).ifPresent(row->classroom.requireClassManager(String.valueOf(row.get("class_id")),actor));return activity;
    }
    public static String fallback(JsonNode stats){return "统计快照复盘\n有效报名："+stats.path("registration_active").asInt()+"；已取消报名："+stats.path("registration_cancelled").asInt()+"\n任务完成："+stats.path("tasks_done").asInt()+"/"+stats.path("tasks_total").asInt()+"；待分配："+stats.path("tasks_unassigned").asInt()+"\n到场："+stats.path("attendance_checked_in").asInt()+"/"+stats.path("attendance_eligible").asInt()+"；反馈："+stats.path("feedback_count").asInt()+"份\n建议结合未完成任务、失败步骤和待核对投递逐项跟进。具体数字以本版本结构化快照为准。";}
    private long count(String sql,String id){return ((Number)db.required(sql,id).get("n")).longValue();}
    private Map<String,Long> groups(String table,String id){Map<String,Long> values=new LinkedHashMap<>();for(var r:db.list("SELECT status,count(*) n FROM "+table+" WHERE activity_id=? GROUP BY status",id))values.put(String.valueOf(r.get("status")),((Number)r.get("n")).longValue());return values;}
    private void stale(String id){db.update("UPDATE activity_recaps SET status='failed',error='生成超时，请新建复盘版本',completed_at=clock_timestamp() WHERE activity_id=? AND status='generating' AND created_at<clock_timestamp()-interval '180 seconds'",id);}
    @Transactional
    public Map<String,Object> begin(String id,AccountPrincipal actor,String request){
        gate.requestId(request);var activity=owner(id,actor);stale(id);
        var old=db.one("SELECT * FROM activity_recaps WHERE activity_id=? AND request_id=?",id,request).orElse(null);if(old!=null){var replay=view(old);replay.put("fresh",false);return replay;}
        if(!Set.of("ready","finished","cancelled","failed").contains(String.valueOf(activity.get("status"))))throw ApiException.badRequest("请等待当前流程稳定后生成复盘");
        Map<String,Object> stats=new LinkedHashMap<>();
        stats.put("registration_active",count("SELECT count(*) n FROM registrations r JOIN forms f ON f.id=r.form_id WHERE f.activity_id=? AND r.status='active'",id));
        stats.put("registration_cancelled",count("SELECT count(*) n FROM registrations r JOIN forms f ON f.id=r.form_id WHERE f.activity_id=? AND r.status='cancelled'",id));
        stats.put("tasks_total",count("SELECT count(*) n FROM tasks WHERE activity_id=?",id));
        stats.put("tasks_done",count("SELECT count(*) n FROM tasks WHERE activity_id=? AND status='done'",id));
        stats.put("tasks_unassigned",count("SELECT count(*) n FROM tasks t WHERE activity_id=? AND NOT EXISTS(SELECT 1 FROM activity_task_assignees a WHERE a.task_id=t.id)",id));
        stats.put("attendance_configured",count("SELECT count(*) n FROM participation_settings WHERE activity_id=? AND roster_confirmed=true",id));
        long roster=count("SELECT count(*) n FROM activity_participants WHERE activity_id=?",id),present=count("SELECT count(*) n FROM activity_check_ins WHERE activity_id=?",id);
        stats.put("attendance_eligible",roster);stats.put("attendance_checked_in",present);stats.put("attendance_absent",Math.max(0,roster-present));
        stats.put("feedback_count",count("SELECT count(*) n FROM activity_feedback WHERE activity_id=?",id));
        stats.put("feedback_rating_sum",count("SELECT COALESCE(sum(rating),0) n FROM activity_feedback WHERE activity_id=?",id));
        Map<String,Long> ratings=new LinkedHashMap<>();for(int rating=1;rating<=5;rating++)ratings.put(String.valueOf(rating),0L);
        for(var rating:db.list("SELECT rating,count(*) n FROM activity_feedback WHERE activity_id=? GROUP BY rating",id))ratings.put(String.valueOf(rating.get("rating")),((Number)rating.get("n")).longValue());stats.put("feedback_rating_counts",ratings);
        stats.put("reminders_total",count("SELECT count(*) n FROM reminders WHERE activity_id=?",id));
        stats.put("steps",groups("steps",id));stats.put("deliveries",groups("delivery_events",id));stats.put("reminders",groups("reminders",id));
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("captured_at",Instant.now().toString());snapshot.put("activity_version",activity.get("version"));snapshot.put("activity_status",activity.get("status"));snapshot.put("title",activity.get("title"));snapshot.put("stats",stats);
        int version=((Number)db.required("SELECT COALESCE(max(version),0)+1 n FROM activity_recaps WHERE activity_id=?",id).get("n")).intValue();String recapId=Ids.shortId();
        db.update("INSERT INTO activity_recaps(id,activity_id,version,request_id,snapshot_json) VALUES(?,?,?,?,?)",recapId,id,version,request,gate.json(snapshot));
        gate.audit(id,actor.id(),"recap.create","recap",recapId,Map.of("version",version));
        var result=view(db.required("SELECT * FROM activity_recaps WHERE id=?",recapId));result.put("fresh",true);return result;
    }
    @Transactional
    public void complete(String id,String body,JsonNode generation){db.update("UPDATE activity_recaps SET status='succeeded',body=?,generation_json=?,completed_at=clock_timestamp(),error=NULL WHERE id=? AND status='generating'",body,gate.json(generation),id);}
    public Map<String,Object> view(Map<String,Object> row){var result=new LinkedHashMap<>(row);result.put("snapshot",gate.parse(result.remove("snapshot_json")));result.put("generation",gate.parse(result.remove("generation_json")));result.put("recap",result.remove("body"));result.remove("request_id");return result;}
    @Transactional
    public List<Map<String,Object>> list(String id,AccountPrincipal actor){owner(id,actor);stale(id);return db.list("SELECT * FROM activity_recaps WHERE activity_id=? ORDER BY version DESC LIMIT 20",id).stream().map(this::view).toList();}
    @Transactional
    public Map<String,Object> get(String id,String recapId,AccountPrincipal actor){owner(id,actor);return view(db.one("SELECT * FROM activity_recaps WHERE id=? AND activity_id=?",recapId,id).orElseThrow(()->ApiException.notFound("复盘不存在")));}
    @Transactional
    public Map<String,Object> finish(String id,AccountPrincipal actor,JsonNode input){var activity=owner(id,actor);String request=input.path("request_id").asText();var replay=gate.replay(id,request,"finish",input);if(replay!=null)return replay;if("finished".equals(activity.get("status")))return Map.of("status","finished","idempotent",true);if(!"ready".equals(activity.get("status")))throw ApiException.badRequest("只能结束已就绪的活动");gate.version(activity,input.path("expected_version"));db.update("UPDATE activities SET status='finished',version=version+1 WHERE id=?",id);db.update("UPDATE forms SET registration_status='closed',version=version+1 WHERE activity_id=?",id);db.update("UPDATE participation_settings SET attendance_open=false,version=version+1 WHERE activity_id=?",id);db.update("UPDATE reminders SET status='cancelled' WHERE activity_id=? AND status='scheduled'",id);db.update("UPDATE background_jobs SET status='failed',error='活动已结束',locked_by=NULL,lease_until=NULL,lease_version=lease_version+1 WHERE kind='reminder' AND ref_id IN(SELECT id FROM reminders WHERE activity_id=? AND status='cancelled') AND status IN('queued','running')",id);return gate.change(id,actor,request,"finish",input,Map.of("status","finished"),"【活动结束】"+activity.get("title"),gate.participants(id));}
}
