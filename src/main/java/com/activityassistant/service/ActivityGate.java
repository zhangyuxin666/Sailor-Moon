package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** All management mutations and workflow steps acquire the activity row first. */
@Service
public class ActivityGate {
    private final Db db; private final ObjectMapper mapper; private final AppProperties properties;
    private final AuditService audits; private final BackgroundJobService jobs;
    public ActivityGate(Db db,ObjectMapper mapper,AppProperties properties,AuditService audits,BackgroundJobService jobs) {
        this.db=db;this.mapper=mapper;this.properties=properties;this.audits=audits;this.jobs=jobs;
    }
    public Map<String,Object> lock(String id) {
        return db.one("SELECT * FROM activities WHERE id=? FOR UPDATE",id).orElseThrow(()->ApiException.notFound("活动不存在"));
    }
    public Map<String,Object> owner(String id,AccountPrincipal actor) {
        Map<String,Object> activity=lock(id);
        if (!actor.isManager() || !actor.username().equals(activity.get("user_id"))) throw ApiException.forbidden("只能管理自己创建的活动");
        return activity;
    }
    public void active(Map<String,Object> activity) {
        if ("cancelled".equals(activity.get("status"))) throw ApiException.badRequest("活动已取消，不能继续此操作");
    }
    @Transactional
    public <T> T during(String id,boolean allowCancelled,Supplier<T> operation) {
        JobLease.verify(db);
        Map<String,Object> activity=lock(id);
        if (!allowCancelled && "cancelled".equals(activity.get("status"))) throw new CancelledFlow();
        JobLease.verify(db);
        T result=operation.get();
        JobLease.verify(db);
        return result;
    }
    @Transactional
    public <T> T settle(String id,Supplier<T> operation) {
        lock(id);JobLease.verify(db);return operation.get();
    }
    @Transactional
    public <T> T todoDuring(String id,Supplier<T> operation) {
        JobLease.verify(db);
        db.required("SELECT id FROM todos WHERE id=? FOR UPDATE",id);
        JobLease.verify(db);T result=operation.get();JobLease.verify(db);return result;
    }
    public static final class CancelledFlow extends RuntimeException {}
    public JsonNode tree(Object value){return mapper.valueToTree(value);}
    public String timezone(){return properties.timezone();}
    public JsonNode parse(Object raw) {
        try { return mapper.readTree(raw==null ? "{}" : String.valueOf(raw)); }
        catch(Exception e) { throw ApiException.badRequest("已保存数据格式无效"); }
    }
    public String json(Object value) {
        try {return mapper.writeValueAsString(value);} catch(Exception e){throw new IllegalStateException(e);}
    }
    public Instant time(String raw) {
        try {return Times.instant(raw,properties.timezone());}catch(Exception e){throw ApiException.badRequest("请提供有效的日期时间");}
    }
    public static String hash(String text) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static String identity(String name,String contact) {
        return hash(Normalizer.normalize(name,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT)+"\n"+
                Normalizer.normalize(contact,Normalizer.Form.NFKC).replaceAll("\\s","").toLowerCase(Locale.ROOT));
    }
    public void requestId(String id) {
        if(id==null || !id.matches("[A-Za-z0-9_-]{8,80}")) throw ApiException.badRequest("请求标识无效");
    }
    public void version(Map<String,Object> row,JsonNode supplied) {
        if(!supplied.isIntegralNumber() || !supplied.canConvertToInt() || supplied.asInt()<1)throw ApiException.badRequest("请提供有效的内容版本号");
        int expected=supplied.asInt();
        if (((Number)row.get("version")).intValue()!=expected) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,"内容已被更新，请刷新后重试");
    }
    public Map<String,Object> replay(String activityId,String requestId,String kind,Object payload) {
        requestId(requestId);
        Map<String,Object> old=db.one("SELECT * FROM activity_changes WHERE activity_id=? AND request_id=?",activityId,requestId).orElse(null);
        if(old==null)return null;
        if(!kind.equals(old.get("kind")) || !parse(old.get("payload_json")).equals(mapper.valueToTree(payload)))
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,"该请求标识已用于不同内容");
        Map<String,Object> result=mapper.convertValue(parse(old.get("result_json")),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        result.put("idempotent",true);return result;
    }
    public Map<String,Object> change(String activityId,AccountPrincipal actor,String requestId,String kind,Object payload,
            Map<String,Object> result,String content,Collection<String> recipients) {
        String id=Ids.shortId(),now=LocalDateTime.now().toString();
        Map<String,Object> response=new LinkedHashMap<>(result);response.put("change_id",id);
        db.update("INSERT INTO activity_changes(id,activity_id,request_id,kind,actor_id,payload_json,result_json,content,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id,activityId,requestId,kind,actor.id(),json(payload),json(response),content,now);
        for(String accountId:new LinkedHashSet<>(recipients)) {
            if(accountId!=null && !accountId.isBlank()) db.update("INSERT INTO messages(activity_id,recipient,recipient_account_id,content,created_at) VALUES (?,?,?,?,?)",
                    activityId,accountId,accountId,content,now);
        }
        String org=db.one("SELECT organization_id FROM activity_classes WHERE activity_id=?",activityId).map(r->String.valueOf(r.get("organization_id"))).orElse(null);
        audits.write("activity."+kind,actor.id(),org,"activity",activityId,Map.of("change_id",id));
        jobs.enqueue("activity_change",id);return response;
    }
    public void audit(String activityId,String actorId,String action,String type,String resourceId,Map<String,?> detail) {
        String org=db.one("SELECT organization_id FROM activity_classes WHERE activity_id=?",activityId).map(r->String.valueOf(r.get("organization_id"))).orElse(null);
        audits.write(action,actorId,org,type,resourceId,detail);
    }
    public List<String> participants(String activityId) {
        return db.list("SELECT cm.account_id FROM class_members cm JOIN activity_classes ac ON ac.class_id=cm.class_id WHERE ac.activity_id=?",activityId)
            .stream().map(r->String.valueOf(r.get("account_id"))).toList();
    }
}
