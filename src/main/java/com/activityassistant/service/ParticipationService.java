package com.activityassistant.service;

import com.activityassistant.persistence.mapper.ParticipationMapper;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.math.*;

/** Attendance/feedback share the activity lock and a stable, append-only participant roster. */
@Service
public class ParticipationService {
    private final ParticipationMapper participation;
    private final ActivityGate gate;
    private final ClassroomService classroom;
    public ParticipationService(ParticipationMapper participation,ActivityGate gate,ClassroomService classroom){this.participation = participation;this.gate=gate;this.classroom=classroom;}

    private Map<String,Object> owner(String id,AccountPrincipal actor){
        var activity=gate.owner(id,actor);
        if(participation.findActivityClass(id).isPresent()&&participation.findValidClass(id).isEmpty())throw ApiException.forbidden("活动组织范围已变化，请联系管理员");
        participation.findClassId(id).ifPresent(row->classroom.requireClassManager(String.valueOf(row.get("class_id")),actor));
        return activity;
    }
    private boolean currentMember(String id,String account){
        return participation.findMember(id,account).isPresent();
    }
    private void member(String id,String account){if(!currentMember(id,account))throw ApiException.forbidden("只有当前有效班级成员可以查看或参与此活动");}
    private boolean rostered(String id,String account){return participation.findRosterMember(id,account).isPresent();}
    private Map<String,Object> settings(String id){return participation.findSettings(id).orElse(Map.of("attendance_open",false,"feedback_open",false,"version",1,"roster_confirmed",false));}
    private void fields(JsonNode input,Set<String> allowed){if(input==null||!input.isObject())throw ApiException.badRequest("请求格式无效");input.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw ApiException.badRequest("请求含不支持的字段："+key);});}
    private Map<String,Object> replay(String id,String actor,String request,String kind,Object payload){
        gate.requestId(request);
        var row=participation.findRequest(id,actor,request).orElse(null);
        if(row==null)return null;
        if(!kind.equals(row.get("kind"))||!gate.parse(row.get("payload_json")).equals(gate.tree(payload)))throw new ApiException(HttpStatus.CONFLICT,"请求标识已用于不同参与操作");
        Map<String,Object> result=new LinkedHashMap<>();gate.parse(row.get("result_json")).fields().forEachRemaining(e->result.put(e.getKey(),e.getValue()));result.put("idempotent",true);return result;
    }
    private Map<String,Object> remember(String id,AccountPrincipal actor,String request,String kind,Object payload,Map<String,Object> result){
        participation.saveRequest(id,actor.id(),request,kind,gate.json(payload),gate.json(result),LocalDateTime.now().toString());return result;
    }
    @Transactional
    public Map<String,Object> configure(String id,AccountPrincipal actor,JsonNode input){
        fields(input,Set.of("request_id","expected_version","attendance_open","feedback_open"));var activity=owner(id,actor);
        if(!input.path("attendance_open").isBoolean()||!input.path("feedback_open").isBoolean())throw ApiException.badRequest("开放状态必须为布尔值");
        String request=input.path("request_id").asText();var payload=input.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)payload).remove("request_id");var old=replay(id,actor.id(),request,"settings",payload);if(old!=null)return old;
        String status=String.valueOf(activity.get("status"));if(!Set.of("ready","finished").contains(status))throw ApiException.badRequest("仅就绪或已结束活动可配置参与规则");
        boolean attendance=input.path("attendance_open").asBoolean(),feedback=input.path("feedback_open").asBoolean();if(attendance&&!"ready".equals(status))throw ApiException.badRequest("活动已结束，不能重新开放签到");
        var config=settings(id);gate.version(config,input.path("expected_version"));
        if("ready".equals(status)&&(attendance||feedback))participation.snapshotRoster(LocalDateTime.now().toString(),id);
        participation.saveSettings(id,attendance,feedback,attendance||feedback);
        gate.audit(id,actor.id(),"participation.settings","activity",id,Map.of("attendance_open",attendance,"feedback_open",feedback));
        return remember(id,actor,request,"settings",payload,Map.of("activity_id",id,"version",((Number)config.get("version")).intValue()+1,"attendance_open",attendance,"feedback_open",feedback));
    }
    @Transactional
    public Map<String,Object> checkIn(String id,AccountPrincipal actor,JsonNode input){
        fields(input,Set.of("request_id","account_id"));var activity=gate.lock(id);
        String account=input.path("account_id").asText(actor.id());
        if(actor.isManager()&&!actor.id().equals(account))owner(id,actor);else{member(id,actor.id());if(!actor.id().equals(account))throw ApiException.forbidden("只能为本人签到");}
        member(id,account);if(!rostered(id,account))throw ApiException.forbidden("活动成员范围尚未确认，请联系创建者同步成员");
        String request=input.path("request_id").asText();var payload=Map.of("account_id",account);var old=replay(id,actor.id(),request,"check_in",payload);if(old!=null)return old;
        if(!"ready".equals(activity.get("status"))||!Boolean.TRUE.equals(settings(id).get("attendance_open")))throw ApiException.badRequest("当前未开放签到或活动已结束/取消");
        int inserted=participation.insertCheckIn(id,account,actor.id(),LocalDateTime.now().toString());
        var row=participation.findCheckIn(id,account).orElseThrow();
        if(inserted==1)gate.audit(id,actor.id(),"participation.check_in","account",account,Map.of());
        return remember(id,actor,request,"check_in",payload,Map.of("activity_id",id,"account_id",account,"checked_in",true,"checked_in_at",row.get("checked_in_at"),"idempotent",inserted==0));
    }
    @Transactional
    public Map<String,Object> feedback(String id,AccountPrincipal actor,JsonNode input){
        fields(input,Set.of("request_id","rating","comment","expected_version"));var activity=gate.lock(id);member(id,actor.id());
        if(!rostered(id,actor.id())||participation.hasCheckIn(id,actor.id()).isEmpty())throw ApiException.forbidden("只有已签到的有效活动成员可以提交反馈");
        if(!input.path("rating").isIntegralNumber()||!input.path("rating").canConvertToInt()||input.path("rating").asInt()<1||input.path("rating").asInt()>5)throw ApiException.badRequest("评分须为1至5的整数");
        if(input.has("comment")&&!input.path("comment").isTextual())throw ApiException.badRequest("反馈意见须为文字");String comment=input.path("comment").asText("").strip();if(comment.length()>2000)throw ApiException.badRequest("反馈意见不能超过2000字");
        if(!input.path("expected_version").isIntegralNumber()||!input.path("expected_version").canConvertToInt()||input.path("expected_version").asInt()<0)throw ApiException.badRequest("请提供有效的反馈版本");
        int expected=input.path("expected_version").asInt(),rating=input.path("rating").asInt();String request=input.path("request_id").asText();var payload=Map.of("rating",rating,"comment",comment,"expected_version",expected);var replay=replay(id,actor.id(),request,"feedback",payload);if(replay!=null)return replay;
        if(!Set.of("ready","finished").contains(String.valueOf(activity.get("status")))||!Boolean.TRUE.equals(settings(id).get("feedback_open")))throw ApiException.badRequest("当前未开放反馈或活动已取消");
        var old=participation.findFeedbackVersion(id,actor.id()).orElse(null);int current=old==null?0:((Number)old.get("version")).intValue();if(current!=expected)throw new ApiException(HttpStatus.CONFLICT,"反馈已更新，请刷新本人反馈后重试");
        String now=LocalDateTime.now().toString();if(old==null)participation.insertFeedback(id,actor.id(),rating,comment,now,now);else participation.updateFeedback(rating,comment,now,id,actor.id());
        gate.audit(id,actor.id(),"participation.feedback","activity",id,Map.of("version",current+1));return remember(id,actor,request,"feedback",payload,Map.of("activity_id",id,"rating",rating,"comment",comment,"version",current+1));
    }
    private Map<String,Object> basic(Map<String,Object> activity){var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","title","status"))result.put(key,activity.get(key));result.put("event_time",gate.parse(activity.get("plan_json")).path("event_time").asText());return result;}
    @Transactional
    public Map<String,Object> mine(String id,AccountPrincipal actor){var activity=gate.lock(id);member(id,actor.id());var result=new LinkedHashMap<String,Object>(settings(id));result.put("activity",basic(activity));boolean eligible=rostered(id,actor.id());result.put("eligible",eligible);result.put("message",eligible?"":"尚未进入成员快照，请联系创建者再次保存开放设置");var check=participation.findCheckIn(id,actor.id()).orElse(null);result.put("checked_in",check!=null);result.put("checked_in_at",check==null?null:check.get("checked_in_at"));result.put("feedback",participation.findFeedback(id,actor.id()).orElse(null));return result;}
    public Map<String,Object> activities(AccountPrincipal actor){
        var rows=participation.listMine(actor.id());
        for(var row:rows){row.put("event_time",gate.parse(row.remove("plan_json")).path("event_time").asText());if(!"ready".equals(row.get("status")))row.put("attendance_open",false);if("cancelled".equals(row.get("status")))row.put("feedback_open",false);}return Map.of("activities",rows);
    }
    @Transactional
    public Map<String,Object> management(String id,AccountPrincipal actor){
        owner(id,actor);var config=settings(id);boolean confirmed=Boolean.TRUE.equals(config.get("roster_confirmed"));
        var participants=participation.listParticipants(id,id,confirmed,id,id,id);
        long eligible=confirmed?participants.stream().filter(p->Boolean.TRUE.equals(p.get("rostered"))).count():participants.size(),checked=participants.stream().filter(p->Boolean.TRUE.equals(p.get("checked_in"))).count();
        var feedback=participation.listFeedback(id);
        Map<String,Long> counts=new LinkedHashMap<>();for(int rating=1;rating<=5;rating++)counts.put(String.valueOf(rating),0L);long sum=0;for(var item:feedback){int rating=((Number)item.get("rating")).intValue();counts.merge(String.valueOf(rating),1L,Long::sum);sum+=rating;}
        Map<String,Object> summary=new LinkedHashMap<>();summary.put("eligible",eligible);summary.put("checked_in",checked);summary.put("absent",Math.max(0,eligible-checked));summary.put("feedback_count",feedback.size());summary.put("rating_counts",counts);summary.put("rating_average",feedback.isEmpty()?null:BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(feedback.size()),2,RoundingMode.HALF_UP));
        var result=new LinkedHashMap<String,Object>(config);result.put("activity_id",id);result.put("summary",summary);result.put("participants",participants);result.put("feedback",feedback);return result;
    }
}
