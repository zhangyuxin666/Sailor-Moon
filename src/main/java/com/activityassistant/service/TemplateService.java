package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** Portable structures only: applying a template never publishes or invokes a model. */
@Service
public class TemplateService {
    private static final Set<String> INTENTS=Set.of("activity","assignment","survey","notice");
    private static final Set<String> WORKFLOW=Set.of("create_plan","create_form","assign_tasks","create_calendar","schedule_reminder","publish_message","collect_submission");
    private static final Set<String> PRIORITIES=Set.of("low","normal","high","urgent");
    private final Db db;
    private final AuthService auth;
    private final ClassroomService classroom;
    private final ActivityGate gate;
    private final AuditService audits;
    private final ObjectMapper mapper;

    public TemplateService(Db db,AuthService auth,ClassroomService classroom,ActivityGate gate,AuditService audits,ObjectMapper mapper){
        this.db=db;this.auth=auth;this.classroom=classroom;this.gate=gate;this.audits=audits;this.mapper=mapper;
    }
    private String organization(AccountPrincipal actor){
        if(!actor.isManager())throw ApiException.forbidden("只有管理者可以使用业务模板");
        String organization=auth.organizationOf(actor.id());
        if(organization==null)throw ApiException.forbidden("账号尚未加入组织");
        String role=db.one("SELECT role FROM organization_members WHERE organization_id=? AND account_id=?",organization,actor.id())
                .map(row->String.valueOf(row.get("role"))).orElse("");
        if(!Set.of("owner","manager").contains(role))throw ApiException.forbidden("只有当前组织的管理者可以使用业务模板");
        return organization;
    }
    private static String text(JsonNode value,int max,String label,boolean required){
        if(!value.isMissingNode()&&!value.isNull()&&!value.isTextual())throw ApiException.badRequest(label+"必须是文本");
        String result=value.asText("").strip();
        if(result.length()>max || required&&result.isEmpty())throw ApiException.badRequest(label+"为空或过长");
        return result;
    }
    private static void array(JsonNode value,int max,String label){
        if(!value.isMissingNode()&&(!value.isArray()||value.size()>max))throw ApiException.badRequest(label+"必须为数组且数量不能超过"+max);
    }
    /** Source records copy topology only; reusable free text must be entered explicitly. */
    static ObjectNode portable(JsonNode input,ObjectMapper mapper,boolean sourceRecord,String newTitle){
        if(!input.isObject()||input.toString().length()>500000)throw ApiException.badRequest("模板结构无效或过大");
        String intent=input.path("intent_type").asText("activity");
        if(!INTENTS.contains(intent))throw ApiException.badRequest("模板事务类型无效");
        ObjectNode result=mapper.createObjectNode();
        result.put("intent_type",intent).put("complexity","activity".equals(intent)?"complex":"assignment".equals(intent)?"simple":"standard");
        result.put("title",newTitle==null?text(input.path("title"),120,"模板标题",true):newTitle);
        result.put("summary","由业务模板创建，请确认当前班级、日期与负责人后发布。");
        result.put("description",sourceRecord?"请补充本次事务的通用要求。":text(input.path("description"),4000,"模板说明",false));
        result.put("event_time","").put("deadline","");
        ArrayNode missing=result.putArray("missing_information");
        if("activity".equals(intent))missing.add("请确认活动时间");
        if("assignment".equals(intent))missing.add("请确认截止时间");
        ObjectNode workflow=result.putObject("workflow");
        JsonNode rawWorkflow=input.path("workflow");
        if(!rawWorkflow.isMissingNode()&&!rawWorkflow.isObject())throw ApiException.badRequest("模板流程配置无效");
        for(String key:WORKFLOW){
            JsonNode supplied=rawWorkflow.path(key);
            if(!supplied.isMissingNode()&&!supplied.isBoolean())throw ApiException.badRequest("模板流程标记必须为布尔值");
            boolean fallback=key.equals("create_plan")||key.equals("collect_submission")&&intent.equals("assignment");
            workflow.put(key,supplied.asBoolean(fallback));
        }
        // Group delivery is always an explicit choice on the newly created draft.
        workflow.put("publish_message",false);
        if("assignment".equals(intent))workflow.put("collect_submission",true);
        JsonNode execution=input.path("execution_plan").isObject()?input.path("execution_plan"):input;
        ObjectNode plan=result.putObject("execution_plan");
        ArrayNode materials=plan.putArray("materials");array(execution.path("materials"),100,"物料");
        if(!sourceRecord)for(JsonNode material:execution.path("materials"))materials.add(text(material,1000,"物料",true));
        int minutes=60;
        if(execution.has("remind_minutes_before")){
            JsonNode supplied=execution.path("remind_minutes_before");
            if(!supplied.isIntegralNumber()||!supplied.canConvertToInt()||supplied.asInt()<0||supplied.asInt()>10080)throw ApiException.badRequest("模板提醒提前量应为0至10080分钟");
            minutes=supplied.asInt();
        }
        plan.put("remind_minutes_before",minutes);
        JsonNode tasks=execution.path("tasks");array(tasks,100,"模板任务");
        ArrayNode cleanTasks=plan.putArray("tasks");
        Map<String,String> keys=new LinkedHashMap<>();int position=0;
        for(JsonNode task:tasks){
            if(!task.isObject())throw ApiException.badRequest("模板任务格式无效");
            String old=text(task.path("key"),80,"任务标识",false);
            if(old.isEmpty())old=text(task.path("id"),80,"任务标识",false);
            if(old.isEmpty())old="index_"+position;
            if(keys.putIfAbsent(old,"task_"+(++position))!=null)throw ApiException.badRequest("模板任务标识重复");
        }
        Map<String,List<String>> graph=new LinkedHashMap<>();position=0;
        for(JsonNode task:tasks){
            String key="task_"+(++position);
            ObjectNode clean=cleanTasks.addObject();clean.put("key",key);
            clean.put("title",sourceRecord?"任务"+position:text(task.path("title"),255,"任务标题",true));
            clean.put("description",sourceRecord?"":text(task.path("description"),4000,"任务说明",false));
            String priority=task.path("priority").asText("normal");
            if(!PRIORITIES.contains(priority))throw ApiException.badRequest("任务优先级无效");
            clean.put("priority",priority).put("deadline","").put("account_id","").put("assignee","待分配").put("unassigned_reason","模板任务需要为当前班级重新指定负责人");
            JsonNode dependencies=task.path("depends_on");array(dependencies,100,"前置依赖");
            ArrayNode cleanDependencies=clean.putArray("depends_on");List<String> edges=new ArrayList<>();
            for(JsonNode dependency:dependencies){
                if(!dependency.isTextual()||!keys.containsKey(dependency.asText())||edges.contains(keys.get(dependency.asText())))throw ApiException.badRequest("模板依赖不存在或重复");
                edges.add(keys.get(dependency.asText()));cleanDependencies.add(keys.get(dependency.asText()));
            }
            graph.put(key,edges);
        }
        TaskManagementService.validateGraph(graph);
        JsonNode fields=input.path("form_fields").isArray()?input.path("form_fields"):execution.path("form_fields");
        array(fields,100,"问卷题目");ArrayNode cleanFields=mapper.createArrayNode();int question=0;
        for(JsonNode field:fields){
            if(!field.isObject())throw ApiException.badRequest("模板题目格式无效");
            String oldName=field.path("name").asText();
            if("name".equals(oldName)||"contact".equals(oldName))continue;
            ObjectNode clean=cleanFields.addObject();question++;
            clean.put("name","field_"+question);
            clean.put("label",sourceRecord?"问题"+question:text(field.path("label"),255,"题目标签",true));
            String type=field.path("type").asText("text");if(!Set.of("text","select").contains(type))throw ApiException.badRequest("模板题型无效");
            clean.put("type",type);
            if(field.has("required")&&!field.path("required").isBoolean())throw ApiException.badRequest("题目必填标记无效");
            clean.put("required",field.path("required").asBoolean(false));
            JsonNode options=field.path("options");array(options,100,"题目选项");ArrayNode cleanOptions=clean.putArray("options");int option=0;
            for(JsonNode value:options)cleanOptions.add(sourceRecord?"选项"+(++option):text(value,1000,"题目选项",true));
            if("select".equals(type)&&cleanOptions.isEmpty())throw ApiException.badRequest("选择题至少有一个选项");
        }
        if("activity".equals(intent)||"survey".equals(intent))result.set("form_fields",ConfirmedPlans.fields(cleanFields,mapper));
        else result.set("form_fields",cleanFields);
        ConfirmedPlans.validateDraft(result);
        if("activity".equals(intent)||"survey".equals(intent))result.set("execution_plan",ConfirmedPlans.fromDraft(result,mapper));
        return result;
    }
    private Map<String,Object> builtin(String id,String name,String description,String raw){
        ObjectNode blueprint=portable(gate.parse(raw),mapper,false,null);
        return new LinkedHashMap<>(Map.of("id",id,"name",name,"description",description,"version",1,"builtin",true,"editable",false,"blueprint",blueprint));
    }
    private List<Map<String,Object>> builtins(){return List.of(
        builtin("builtin-class-meeting","班会","班会报名、议程与分工", "{\"intent_type\":\"activity\",\"title\":\"班会\",\"description\":\"确认班会议题、主持与场地，发布前填写本次时间。\",\"workflow\":{\"create_form\":true,\"assign_tasks\":true,\"create_calendar\":true,\"schedule_reminder\":true},\"form_fields\":[{\"name\":\"agenda\",\"label\":\"建议议题\",\"type\":\"text\"}],\"execution_plan\":{\"tasks\":[{\"key\":\"prepare\",\"title\":\"准备议程与场地\"},{\"key\":\"host\",\"title\":\"主持和记录\",\"depends_on\":[\"prepare\"]}],\"materials\":[\"议程清单\"],\"remind_minutes_before\":60}}"),
        builtin("builtin-autumn-trip","秋游","报名、出行方式与安全准备", "{\"intent_type\":\"activity\",\"title\":\"班级秋游\",\"description\":\"确认路线、安全预案和集合方式，发布前填写本次时间。\",\"workflow\":{\"create_form\":true,\"assign_tasks\":true,\"create_calendar\":true,\"schedule_reminder\":true},\"form_fields\":[{\"name\":\"travel\",\"label\":\"出行方式\",\"type\":\"select\",\"required\":true,\"options\":[\"集体出行\",\"自行前往\"]}],\"execution_plan\":{\"tasks\":[{\"key\":\"route\",\"title\":\"确认路线与安全预案\",\"priority\":\"high\"},{\"key\":\"materials\",\"title\":\"准备物料与集合清单\",\"depends_on\":[\"route\"]}],\"materials\":[\"饮用水\",\"应急用品\"],\"remind_minutes_before\":1440}}"),
        builtin("builtin-assignment","作业收集","逐人提交与完成追踪", "{\"intent_type\":\"assignment\",\"title\":\"课程作业收集\",\"description\":\"请填写本次作业要求、格式和实际截止时间。\",\"workflow\":{\"create_plan\":false,\"collect_submission\":true}}")
    );}
    private Map<String,Object> view(Map<String,Object> row,AccountPrincipal actor){
        var result=new LinkedHashMap<>(row);result.put("blueprint",gate.parse(result.remove("blueprint_json")));
        result.put("editable",actor.id().equals(result.remove("creator_id")));result.put("builtin",false);return result;
    }
    public List<Map<String,Object>> list(AccountPrincipal actor){String organization=organization(actor);List<Map<String,Object>> result=new ArrayList<>(builtins());db.list("SELECT * FROM business_templates WHERE organization_id=? ORDER BY updated_at DESC,id LIMIT 200",organization).forEach(row->result.add(view(row,actor)));return result;}
    public Map<String,Object> get(AccountPrincipal actor,String id){String organization=organization(actor);for(var builtin:builtins())if(id.equals(builtin.get("id")))return builtin;return view(db.one("SELECT * FROM business_templates WHERE id=? AND organization_id=?",id,organization).orElseThrow(()->ApiException.notFound("模板不存在")),actor);}
    /** Import choices have the same organization and creator scope as the import mutation. */
    public Map<String,Object> sources(AccountPrincipal actor){
        String organization=organization(actor);
        List<Map<String,Object>> drafts=new ArrayList<>();
        for(var row:db.list("SELECT id,status,draft_json FROM agent_drafts WHERE organization_id=? AND creator_id=? ORDER BY updated_at DESC,id LIMIT 200",organization,actor.id())){
            String title=gate.parse(row.get("draft_json")).path("title").asText("草案");
            drafts.add(Map.of("id",row.get("id"),"title",title,"status",row.get("status")));
        }
        var activities=db.list("SELECT a.id,a.title,a.status FROM activities a JOIN activity_classes ac ON ac.activity_id=a.id WHERE ac.organization_id=? AND a.user_id=? ORDER BY a.created_at DESC,a.id LIMIT 200",organization,actor.username());
        return Map.of("drafts",drafts,"activities",activities);
    }
    private JsonNode source(AccountPrincipal actor,JsonNode input,String organization){
        boolean draft=input.hasNonNull("source_draft_id"),activity=input.hasNonNull("source_activity_id");
        if(draft&&activity||input.has("blueprint")&&(draft||activity))throw ApiException.badRequest("一次只能选择一种模板来源");
        if(draft){String id=text(input.path("source_draft_id"),32,"草案编号",true);return gate.parse(db.one("SELECT draft_json FROM agent_drafts WHERE id=? AND organization_id=? AND creator_id=?",id,organization,actor.id()).orElseThrow(()->ApiException.notFound("来源草案不存在或不属于你")).get("draft_json"));}
        if(activity){String id=text(input.path("source_activity_id"),32,"活动编号",true);gate.owner(id,actor);var scope=db.one("SELECT organization_id FROM activity_classes WHERE activity_id=?",id).orElseThrow(()->ApiException.notFound("来源活动没有组织"));if(!organization.equals(scope.get("organization_id")))throw ApiException.forbidden("不能复制其他组织的活动");
            ObjectNode result=mapper.createObjectNode().put("intent_type","activity");
            var plan=gate.parse(db.required("SELECT plan_json FROM activities WHERE id=?",id).get("plan_json"));result.set("form_fields",plan.path("form_fields"));
            result.set("workflow",gate.parse(db.one("SELECT config_json FROM activity_workflows WHERE activity_id=?",id).map(row->row.get("config_json")).orElse("{}")));
            ObjectNode execution=result.putObject("execution_plan");execution.put("remind_minutes_before",plan.path("remind_minutes_before").asInt(60));ArrayNode tasks=execution.putArray("tasks");
            for(var task:db.list("SELECT id,title,priority FROM tasks WHERE activity_id=? ORDER BY id",id)){ObjectNode node=mapper.valueToTree(task);ArrayNode deps=node.putArray("depends_on");db.list("SELECT depends_on_id FROM task_dependencies WHERE task_id=? ORDER BY depends_on_id",task.get("id")).forEach(row->deps.add(String.valueOf(row.get("depends_on_id"))));tasks.add(node);}
            return result;
        }
        if(!input.path("blueprint").isObject())throw ApiException.badRequest("请提供模板蓝图或已授权来源");return input.path("blueprint");
    }
    @Transactional
    public Map<String,Object> create(AccountPrincipal actor,JsonNode input){
        String organization=organization(actor),name=text(input.path("name"),120,"模板名称",true),description=text(input.path("description"),1000,"模板简介",false);
        JsonNode raw=source(actor,input,organization);boolean fromRecord=input.hasNonNull("source_draft_id")||input.hasNonNull("source_activity_id");ObjectNode blueprint=portable(raw,mapper,fromRecord,name);
        String id=Ids.shortId(),now=LocalDateTime.now().toString();db.update("INSERT INTO business_templates(id,organization_id,creator_id,name,description,blueprint_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",id,organization,actor.id(),name,description,gate.json(blueprint),now,now);
        audits.write("template.create",actor.id(),organization,"template",id,Map.of("structure_only",fromRecord));return get(actor,id);
    }
    @Transactional
    public Map<String,Object> update(AccountPrincipal actor,String id,JsonNode input){String organization=organization(actor);if(id.startsWith("builtin-"))throw ApiException.forbidden("内置模板只读，可另存为自己的模板");var row=db.one("SELECT * FROM business_templates WHERE id=? AND organization_id=? FOR UPDATE",id,organization).orElseThrow(()->ApiException.notFound("模板不存在"));if(!actor.id().equals(row.get("creator_id")))throw ApiException.forbidden("只能修改自己创建的模板");gate.version(row,input.path("expected_version"));String name=text(input.path("name"),120,"模板名称",true),description=text(input.path("description"),1000,"模板简介",false);ObjectNode blueprint=portable(input.path("blueprint"),mapper,false,name);db.update("UPDATE business_templates SET name=?,description=?,blueprint_json=?,version=version+1,updated_at=? WHERE id=?",name,description,gate.json(blueprint),LocalDateTime.now().toString(),id);audits.write("template.update",actor.id(),organization,"template",id,null);return get(actor,id);}
    @Transactional
    public Map<String,Object> delete(AccountPrincipal actor,String id,int version){String organization=organization(actor);if(id.startsWith("builtin-"))throw ApiException.forbidden("内置模板只读");var row=db.one("SELECT * FROM business_templates WHERE id=? AND organization_id=? FOR UPDATE",id,organization).orElseThrow(()->ApiException.notFound("模板不存在"));if(!actor.id().equals(row.get("creator_id")))throw ApiException.forbidden("只能删除自己创建的模板");if(((Number)row.get("version")).intValue()!=version)throw new ApiException(HttpStatus.CONFLICT,"模板已更新，请刷新后重试");db.update("DELETE FROM business_templates WHERE id=?",id);audits.write("template.delete",actor.id(),organization,"template",id,null);return Map.of("status","deleted");}
    private Map<String,Object> replay(AccountPrincipal actor,String request,String hash){var old=db.one("SELECT * FROM template_applications WHERE account_id=? AND request_id=?",actor.id(),request).orElse(null);if(old==null)return null;if(!hash.equals(old.get("payload_hash")))throw new ApiException(HttpStatus.CONFLICT,"该请求标识已用于另一模板或班级");return Map.of("draft_id",old.get("draft_id"),"status","draft","idempotent",true);}
    @Transactional
    public Map<String,Object> apply(AccountPrincipal actor,String id,JsonNode input){String organization=organization(actor),classId=text(input.path("class_id"),32,"班级编号",true),request=input.path("request_id").asText();gate.requestId(request);var target=classroom.requireClassManager(classId,actor);if(!organization.equals(target.get("organization_id")))throw ApiException.forbidden("模板只能应用到当前组织的班级");String hash=ActivityGate.hash(id+"\n"+classId);var old=replay(actor,request,hash);if(old!=null)return old;
        var template=get(actor,id);ObjectNode blueprint=portable((JsonNode)template.get("blueprint"),mapper,false,null);String draftId=Ids.shortId(),now=LocalDateTime.now().toString();int inserted=db.update("INSERT INTO template_applications(account_id,request_id,template_id,class_id,payload_hash,draft_id,created_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",actor.id(),request,id,classId,hash,draftId,now);if(inserted==0)return Objects.requireNonNull(replay(actor,request,hash));
        ObjectNode generation=blueprint.putObject("generation");generation.put("mode","offline").put("fallback_reason","template").put("retrieval_status","empty");generation.putArray("sources");ArrayNode pending=generation.putArray("pending_fields");String intent=blueprint.path("intent_type").asText();if(intent.equals("activity"))pending.add("event_time");if(intent.equals("assignment"))pending.add("deadline");
        if(Set.of("activity","survey").contains(intent))blueprint.set("execution_plan",ConfirmedPlans.fromDraft(blueprint,mapper));
        db.update("INSERT INTO agent_drafts(id,organization_id,class_id,creator_id,raw_input,intent_type,complexity,draft_json,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'draft',?,?)",draftId,organization,classId,actor.id(),"从业务模板创建，请确认当前日期和负责人后发布。",intent,blueprint.path("complexity").asText(),gate.json(blueprint),now,now);
        audits.write("template.apply",actor.id(),organization,"agent_draft",draftId,Map.of("template_id",id,"template_version",template.get("version")));return Map.of("draft_id",draftId,"status","draft","idempotent",false);
    }
}
