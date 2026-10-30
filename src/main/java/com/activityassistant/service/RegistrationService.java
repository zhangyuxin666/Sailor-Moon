package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class RegistrationService {
    private final ActivityGate gate; private final Db db;
    public RegistrationService(ActivityGate gate,Db db){this.gate=gate;this.db=db;}
    private Map<String,Object> form(String id) {
        Map<String,Object> ref=db.one("SELECT activity_id FROM forms WHERE id=?",id).orElseThrow(()->ApiException.notFound("报名表不存在"));
        gate.lock(String.valueOf(ref.get("activity_id")));
        return db.required("SELECT * FROM forms WHERE id=? FOR UPDATE",id);
    }
    private long count(String id){return ((Number)db.required("SELECT COUNT(*) AS count FROM registrations WHERE form_id=? AND status='active'",id).get("count")).longValue();}
    public static Map<String,Object> validate(JsonNode fields,String name,String contact,Map<String,Object> extra) {
        if(name==null || name.isBlank() || name.length()>120)throw ApiException.badRequest("姓名必须为1至120字");
        if(contact==null || contact.isBlank() || contact.length()>255)throw ApiException.badRequest("联系方式必须为1至255字");
        Map<String,JsonNode> schema=new LinkedHashMap<>();
        for(JsonNode field:fields)if(!Set.of("name","contact").contains(field.path("name").asText()))schema.put(field.path("name").asText(),field);
        Map<String,Object> result=new LinkedHashMap<>();
        if(extra!=null) for(var item:extra.entrySet()) {
            if(!schema.containsKey(item.getKey()))throw ApiException.badRequest("不支持的回答字段："+item.getKey());
            if(!(item.getValue() instanceof String text) || text.length()>2000)throw ApiException.badRequest("回答须为最多2000字的文本："+schema.get(item.getKey()).path("label").asText());
            result.put(item.getKey(),text.strip());
        }
        for(var field:schema.entrySet()) {
            String value=String.valueOf(result.getOrDefault(field.getKey(),"")),label=field.getValue().path("label").asText();
            if(field.getValue().path("required").asBoolean() && value.isBlank())throw ApiException.badRequest("请填写："+label);
            if(!value.isBlank() && "select".equals(field.getValue().path("type").asText())) {
                boolean found=false;for(JsonNode option:field.getValue().path("options"))found|=option.asText().equals(value);
                if(!found)throw ApiException.badRequest("请选择有效选项："+label);
            }
        }
        return result;
    }
    @Transactional
    public Map<String,Object> register(String formId,String name,String contact,Map<String,Object> extra,String requestId,String cancelToken) {
        Map<String,Object> f=form(formId);String activityId=String.valueOf(f.get("activity_id"));
        Map<String,Object> answers=validate(gate.parse(f.get("fields_json")),name,contact,extra);
        name=name.strip();contact=contact.strip();
        boolean clientReceipt=requestId!=null;
        if(requestId==null)requestId=UUID.randomUUID().toString();gate.requestId(requestId);
        if(cancelToken==null && !clientReceipt)cancelToken=UUID.randomUUID().toString()+UUID.randomUUID();
        if(cancelToken==null || !cancelToken.matches("[A-Za-z0-9-]{32,100}"))throw ApiException.badRequest("报名回执标识无效");
        String tokenHash=ActivityGate.hash(cancelToken);
        Map<String,Object> old=db.one("SELECT * FROM registrations WHERE form_id=? AND request_id=?",formId,requestId).orElse(null);
        if(old!=null) {
            if(!name.equals(old.get("name")) || !contact.equals(old.get("contact")) || !gate.parse(old.get("extra_json")).equals(gate.tree(answers)) || !tokenHash.equals(old.get("cancel_token_hash")))
                throw new ApiException(HttpStatus.CONFLICT,"报名请求标识已经用于其他内容");
            return Map.of("registration_id",old.get("id"),"form_id",formId,"status",old.get("status"),"cancellation_token",cancelToken,"idempotent",true);
        }
        Map<String,Object> activity=db.required("SELECT * FROM activities WHERE id=?",activityId);gate.active(activity);
        if(!"open".equals(f.get("registration_status")))throw ApiException.badRequest("报名已关闭");
        if(f.get("registration_deadline")!=null && !gate.time(String.valueOf(f.get("registration_deadline"))).isAfter(Instant.now()))throw ApiException.badRequest("报名已截止");
        String identity=ActivityGate.identity(name,contact);
        boolean historicalDuplicate=db.list("SELECT name,contact FROM registrations WHERE form_id=? AND status='active' AND identity_key IS NULL",formId).stream()
                .anyMatch(r->identity.equals(ActivityGate.identity(String.valueOf(r.get("name")),String.valueOf(r.get("contact")))));
        if(historicalDuplicate || db.one("SELECT id FROM registrations WHERE form_id=? AND identity_key=? AND status='active'",formId,identity).isPresent())throw new ApiException(HttpStatus.CONFLICT,"该姓名和联系方式已报名，请勿重复提交");
        if(f.get("capacity")!=null && count(formId)>=((Number)f.get("capacity")).longValue())throw new ApiException(HttpStatus.CONFLICT,"报名名额已满");
        long id=db.insertReturningId("INSERT INTO registrations(form_id,name,contact,extra_json,created_at,identity_key,request_id,cancel_token_hash) VALUES (:form,:name,:contact,:extra,:now,:identity,:request,:token)",
                Map.of("form",formId,"name",name,"contact",contact,"extra",gate.json(answers),"now",LocalDateTime.now().toString(),"identity",identity,"request",requestId,"token",tokenHash));
        gate.audit(activityId,null,"registration.create","registration",String.valueOf(id),Map.of("form_id",formId));
        return Map.of("registration_id",id,"form_id",formId,"status","active","cancellation_token",cancelToken);
    }
    @Transactional
    public Map<String,Object> cancel(String formId,long id,String token) {
        Map<String,Object> f=form(formId);
        Map<String,Object> row=db.one("SELECT * FROM registrations WHERE id=? AND form_id=? FOR UPDATE",id,formId).orElseThrow(()->ApiException.notFound("报名记录不存在"));
        if(token==null || row.get("cancel_token_hash")==null || !MessageDigest.isEqual(ActivityGate.hash(token).getBytes(java.nio.charset.StandardCharsets.UTF_8),String.valueOf(row.get("cancel_token_hash")).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw ApiException.forbidden("报名回执无效，不能取消他人报名");
        boolean old="cancelled".equals(row.get("status"));
        if(!old){
            db.update("UPDATE registrations SET status='cancelled',cancelled_at=? WHERE id=?",LocalDateTime.now().toString(),id);
            gate.audit(String.valueOf(f.get("activity_id")),null,"registration.cancel","registration",String.valueOf(id),Map.of("form_id",formId));
        }
        return Map.of("registration_id",id,"status","cancelled","idempotent",old);
    }
    public Map<String,Object> receipt(String formId,long id,String token) {
        Map<String,Object> row=db.one("SELECT id,status,cancel_token_hash FROM registrations WHERE id=? AND form_id=?",id,formId).orElseThrow(()->ApiException.notFound("报名记录不存在"));
        if(token==null || token.length()<32 || token.length()>100 || row.get("cancel_token_hash")==null || !MessageDigest.isEqual(ActivityGate.hash(token).getBytes(java.nio.charset.StandardCharsets.UTF_8),String.valueOf(row.get("cancel_token_hash")).getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw ApiException.forbidden("报名回执无效");
        return Map.of("registration_id",row.get("id"),"status",row.get("status"));
    }
    @Transactional
    public Map<String,Object> cancelByOwner(String activityId,long id,AccountPrincipal actor,JsonNode input) {
        gate.owner(activityId,actor);String request=input.path("request_id").asText();
        Map<String,Object> payload=Map.of("registration_id",id,"input",input);
        var replay=gate.replay(activityId,request,"registration_cancel",payload);if(replay!=null)return replay;
        var row=db.one("SELECT r.id,r.form_id,r.name,r.status FROM registrations r JOIN forms f ON f.id=r.form_id WHERE r.id=? AND f.activity_id=?",id,activityId).orElseThrow(()->ApiException.notFound("报名记录不存在"));
        db.required("SELECT * FROM forms WHERE id=? FOR UPDATE",row.get("form_id"));
        String reason=input.path("reason").asText("").strip();if(reason.isBlank() || reason.length()>1000)throw ApiException.badRequest("请填写取消原因");
        if("cancelled".equals(row.get("status")))return Map.of("registration_id",id,"status","cancelled","idempotent",true);
        db.update("UPDATE registrations SET status='cancelled',cancelled_at=? WHERE id=?",LocalDateTime.now().toString(),id);
        return gate.change(activityId,actor,request,"registration_cancel",payload,Map.of("registration_id",id,"status","cancelled"),"报名取消："+row.get("name")+"；原因："+reason,List.of());
    }
    @Transactional
    public Map<String,Object> settings(String activityId,AccountPrincipal actor,JsonNode input) {
        gate.owner(activityId,actor);
        String request=input.path("request_id").asText();var replay=gate.replay(activityId,request,"registration_settings",input);if(replay!=null)return replay;
        gate.active(db.required("SELECT * FROM activities WHERE id=?",activityId));
        Map<String,Object> f=db.one("SELECT * FROM forms WHERE activity_id=? ORDER BY created_at LIMIT 1 FOR UPDATE",activityId).orElseThrow(()->ApiException.notFound("活动尚无报名表"));
        gate.version(f,input.path("expected_version"));
        if(input.hasNonNull("capacity") && (!input.path("capacity").isIntegralNumber() || !input.path("capacity").canConvertToInt()))throw ApiException.badRequest("报名容量必须为整数或留空");
        Integer capacity=input.path("capacity").isNull() || input.path("capacity").isMissingNode()?null:input.path("capacity").asInt(-1);
        if(capacity!=null && (capacity<1 || capacity>100000 || capacity<count(String.valueOf(f.get("id")))))throw ApiException.badRequest("容量须为1至100000且不少于当前有效报名人数");
        String deadline=input.path("registration_deadline").asText("");if(!deadline.isBlank())deadline=gate.time(deadline).toString();else deadline=null;
        String status=input.path("registration_status").asText();if(!Set.of("open","closed").contains(status))throw ApiException.badRequest("报名状态无效");
        db.update("UPDATE forms SET capacity=?,registration_deadline=?,registration_status=?,version=version+1 WHERE id=?",capacity,deadline,status,f.get("id"));
        return gate.change(activityId,actor,request,"registration_settings",input,Map.of("form_id",f.get("id"),"version",((Number)f.get("version")).intValue()+1),"报名规则已更新",List.of());
    }
    public Map<String,Object> metadata(String id) {
        Map<String,Object> f=new LinkedHashMap<>(db.one("SELECT f.*,a.title AS activity_title,a.status AS activity_status,a.plan_json FROM forms f JOIN activities a ON a.id=f.activity_id WHERE f.id=?",id).orElseThrow(()->ApiException.notFound("报名表不存在")));
        JsonNode plan=gate.parse(f.remove("plan_json"));f.put("fields",gate.parse(f.remove("fields_json")));f.put("description",plan.path("description").asText(""));f.put("event_time",plan.path("event_time").asText(null));
        long active=count(id);f.put("registration_count",active);f.put("remaining",f.get("capacity")==null?null:Math.max(0,((Number)f.get("capacity")).longValue()-active));
        String reason="cancelled".equals(f.get("activity_status"))?"活动已取消":!"open".equals(f.get("registration_status"))?"报名已关闭":f.get("registration_deadline")!=null && !gate.time(String.valueOf(f.get("registration_deadline"))).isAfter(Instant.now())?"报名已截止":f.get("capacity")!=null && active>=((Number)f.get("capacity")).longValue()?"名额已满":null;
        f.put("accepting_registrations",reason==null);f.put("closed_reason",reason);return f;
    }
    public Map<String,Object> stats(String activityId,String username) {
        Map<String,Object> owner=db.one("SELECT user_id FROM activities WHERE id=?",activityId).orElseThrow(()->ApiException.notFound("活动不存在"));
        if(!username.equals(owner.get("user_id")))throw ApiException.forbidden("只能查看自己活动的报名统计");
        Map<String,Object> f=db.one("SELECT * FROM forms WHERE activity_id=? ORDER BY created_at LIMIT 1",activityId).orElseThrow(()->ApiException.notFound("活动尚无报名表"));
        List<Map<String,Object>> rows=db.list("SELECT id,name,contact,extra_json,created_at,status,cancelled_at FROM registrations WHERE form_id=? ORDER BY id",f.get("id")).stream().map(r->(Map<String,Object>)new LinkedHashMap<>(r)).toList();
        for(var row:rows)row.put("extra",gate.parse(row.remove("extra_json")));
        Map<String,Object> result=new LinkedHashMap<>();result.put("form_id",f.get("id"));result.put("title",f.get("title"));result.put("fields",gate.parse(f.get("fields_json")));result.put("count",rows.stream().filter(r->"active".equals(r.get("status"))).count());result.put("registrations",rows);result.put("settings",metadata(String.valueOf(f.get("id"))));return result;
    }
}
