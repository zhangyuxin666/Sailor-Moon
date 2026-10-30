package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class TaskManagementService {
    private final Db db; private final ActivityGate gate;
    public TaskManagementService(Db db,ActivityGate gate){this.db=db;this.gate=gate;}
    public List<Map<String,Object>> members(String activityId) {
        return db.list("SELECT a.id AS account_id,a.display_name,a.username,a.student_no,COUNT(t.id) FILTER (WHERE t.status='pending' AND ev.status NOT IN ('cancelled','finished')) AS pending_count " +
            "FROM activity_classes ac JOIN class_members cm ON cm.class_id=ac.class_id JOIN accounts a ON a.id=cm.account_id " +
            "LEFT JOIN activity_task_assignees ata ON ata.account_id=a.id LEFT JOIN tasks t ON t.id=ata.task_id LEFT JOIN activities ev ON ev.id=t.activity_id " +
            "WHERE ac.activity_id=? AND a.status='active' GROUP BY a.id,a.display_name,a.username,a.student_no ORDER BY pending_count,a.display_name,a.id",activityId);
    }
    public Map<String,Object> view(String activityId,AccountPrincipal actor) {
        Map<String,Object> a=db.one("SELECT * FROM activities WHERE id=?",activityId).orElseThrow(()->ApiException.notFound("活动不存在"));
        if(!actor.isManager() || !actor.username().equals(a.get("user_id")))throw ApiException.forbidden("只能管理自己的活动任务");
        return Map.of("members",members(activityId),"tasks",tasks(activityId));
    }
    public List<Map<String,Object>> tasks(String activityId) {
        List<Map<String,Object>> rows=db.list("SELECT t.*,ata.account_id,COALESCE(a.display_name,t.assignee) AS assignee FROM tasks t LEFT JOIN activity_task_assignees ata ON ata.task_id=t.id LEFT JOIN accounts a ON a.id=ata.account_id WHERE t.activity_id=? ORDER BY t.created_at,t.id",activityId);
        for(var row:rows) row.put("depends_on",db.list("SELECT depends_on_id FROM task_dependencies WHERE task_id=? ORDER BY depends_on_id",row.get("id")).stream().map(r->r.get("depends_on_id")).toList());
        return rows;
    }
    public static void validateTask(JsonNode task) {
        for(String key:List.of("title","description","deadline","priority"))if(task.hasNonNull(key) && !task.path(key).isTextual())throw ApiException.badRequest("任务字段必须为文本："+key);
        if(!task.isObject() || task.path("title").asText().isBlank() || task.path("title").asText().length()>255 || task.path("description").asText().length()>4000)
            throw ApiException.badRequest("任务标题或说明无效");
        if(!Set.of("low","normal","high","urgent").contains(task.path("priority").asText("normal")))throw ApiException.badRequest("任务优先级无效");
        if(task.has("depends_on") && (!task.path("depends_on").isArray() || task.path("depends_on").size()>100))throw ApiException.badRequest("任务依赖必须为最多100项的数组");
        if(task.has("account_id") && !task.path("account_id").isNull() && task.path("account_id").asText().length()>32)throw ApiException.badRequest("负责人账号无效");
    }
    public static void validateGraph(Map<String,List<String>> graph) {
        Set<String> visiting=new HashSet<>(),done=new HashSet<>();
        for(String id:graph.keySet())visit(id,graph,visiting,done);
    }
    private static void visit(String id,Map<String,List<String>> graph,Set<String> visiting,Set<String> done) {
        if(done.contains(id))return;
        if(!visiting.add(id))throw ApiException.badRequest("任务依赖存在循环");
        for(String dependency:graph.getOrDefault(id,List.of())) {
            if(!graph.containsKey(dependency))throw ApiException.badRequest("依赖任务不属于当前活动");
            visit(dependency,graph,visiting,done);
        }
        visiting.remove(id);done.add(id);
    }
    @Transactional
    public Map<String,Object> save(String activityId,String taskId,AccountPrincipal actor,JsonNode input) {
        Map<String,Object> activity=gate.owner(activityId,actor);
        String kind=taskId==null?"task_create":"task_update",request=input.path("request_id").asText();
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("task_id",taskId);payload.put("input",input);
        var oldResult=gate.replay(activityId,request,kind,payload);if(oldResult!=null)return oldResult;gate.active(activity);
        validateTask(input);
        Map<String,Object> old=taskId==null?null:db.one("SELECT t.*,ata.account_id FROM tasks t LEFT JOIN activity_task_assignees ata ON ata.task_id=t.id WHERE t.id=? AND t.activity_id=?",taskId,activityId).orElseThrow(()->ApiException.notFound("任务不存在"));
        if(old!=null)gate.version(old,input.path("expected_version"));
        String account=input.path("account_id").asText(""),name="待分配",reason="尚未指定负责人";
        if(!account.isBlank()) {
            var member=members(activityId).stream().filter(m->account.equals(m.get("account_id"))).findFirst();
            if(member.isEmpty())throw ApiException.badRequest("负责人必须是当前班级的有效成员");
            name=String.valueOf(member.get().get("display_name"));reason=null;
        }
        String deadline=input.path("deadline").asText("");if(!deadline.isBlank())deadline=gate.time(deadline).toString();else deadline=null;
        String id=taskId==null?Ids.shortId():taskId;
        List<String> dependencies=new ArrayList<>();for(JsonNode d:input.path("depends_on")) {
            if(!d.isTextual() || dependencies.contains(d.asText()))throw ApiException.badRequest("依赖任务标识无效或重复");dependencies.add(d.asText());
        }
        Map<String,List<String>> graph=new LinkedHashMap<>();
        for(var task:tasks(activityId)) {
            @SuppressWarnings("unchecked") List<String> list=(List<String>)task.get("depends_on");graph.put(String.valueOf(task.get("id")),list);
        }
        graph.put(id,dependencies);validateGraph(graph);
        String oldAccount=old==null || old.get("account_id")==null?"":String.valueOf(old.get("account_id"));
        String status=old==null || !oldAccount.equals(account)?"pending":String.valueOf(old.get("status"));
        if("done".equals(status) && !dependencies.isEmpty()) {
            for(String d:dependencies)if(!"done".equals(db.required("SELECT status FROM tasks WHERE id=?",d).get("status")))throw ApiException.badRequest("已完成任务不能依赖未完成任务");
        }
        if(old==null)db.update("INSERT INTO tasks(id,activity_id,title,assignee,status,created_at,description,deadline,priority,unassigned_reason) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id,activityId,input.path("title").asText().strip(),name,status,LocalDateTime.now().toString(),input.path("description").asText(""),deadline,input.path("priority").asText("normal"),reason);
        else db.update("UPDATE tasks SET title=?,assignee=?,description=?,deadline=?,priority=?,status=?,unassigned_reason=?,version=version+1 WHERE id=?",
                input.path("title").asText().strip(),name,input.path("description").asText(""),deadline,input.path("priority").asText("normal"),status,reason,id);
        db.update("DELETE FROM activity_task_assignees WHERE task_id=?",id);
        if(!account.isBlank())db.update("INSERT INTO activity_task_assignees(task_id,account_id) VALUES (?,?)",id,account);
        db.update("DELETE FROM task_dependencies WHERE task_id=?",id);
        for(String d:dependencies)db.update("INSERT INTO task_dependencies(task_id,depends_on_id) VALUES (?,?)",id,d);
        Map<String,Object> result=new LinkedHashMap<>();result.put("task_id",id);result.put("version",old==null?1:((Number)old.get("version")).intValue()+1);result.put("previous_account_id",oldAccount);result.put("account_id",account);
        String content="【任务变更】"+activity.get("title")+"："+input.path("title").asText()+"，当前负责人："+name+(oldAccount.equals(account)?"":"；原负责人分配已结束，新分配从待完成开始。旧任务状态保留在变更记录中。");
        // Replay compares request data, while the persisted result carries the previous state for review.
        result.put("previous",old);
        return gate.change(activityId,actor,request,kind,payload,result,content,List.of(oldAccount,account));
    }
    @Transactional
    public Map<String,Object> status(String taskId,String status,AccountPrincipal actor) {
        if(!Set.of("pending","done").contains(status))throw ApiException.badRequest("任务状态无效");
        Map<String,Object> ref=db.one("SELECT * FROM tasks WHERE id=?",taskId).orElseThrow(()->ApiException.notFound("任务不存在"));
        String activityId=String.valueOf(ref.get("activity_id"));Map<String,Object> activity=gate.lock(activityId);
        if(actor.isManager()) {if(!actor.username().equals(activity.get("user_id")))throw ApiException.forbidden("只能管理自己的活动任务");}
        else if(db.one("SELECT 1 FROM activity_task_assignees WHERE task_id=? AND account_id=?",taskId,actor.id()).isEmpty())throw ApiException.forbidden("该活动任务未分配给你");
        gate.active(activity);
        if("done".equals(status) && !db.list("SELECT t.id FROM task_dependencies d JOIN tasks t ON t.id=d.depends_on_id WHERE d.task_id=? AND t.status<>'done'",taskId).isEmpty())throw ApiException.badRequest("请先完成前置依赖任务");
        if("pending".equals(status) && !db.list("SELECT t.id FROM task_dependencies d JOIN tasks t ON t.id=d.task_id WHERE d.depends_on_id=? AND t.status='done'",taskId).isEmpty())throw ApiException.badRequest("已有依赖此任务的已完成任务，请先撤回后续任务");
        var current=db.required("SELECT * FROM tasks WHERE id=?",taskId);
        if(status.equals(current.get("status")))return current;
        db.update("UPDATE tasks SET status=?,version=version+1 WHERE id=?",status,taskId);
        gate.audit(activityId,actor.id(),"task.status","task",taskId,Map.of("from",String.valueOf(current.get("status")),"to",status));
        return db.required("SELECT * FROM tasks WHERE id=?",taskId);
    }
}
