package com.activityassistant.service;

import com.activityassistant.integration.AiClient;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import org.springframework.http.HttpStatus;

@Service
public class KnowledgeService {
    private final Db db;private final AuthService auth;private final AiClient ai;private final ObjectMapper mapper;
    private AuditService audits;
    @org.springframework.beans.factory.annotation.Autowired
    public void audits(AuditService service){audits=service;}
    public KnowledgeService(Db db,AuthService auth,AiClient ai,ObjectMapper mapper){this.db=db;this.auth=auth;this.ai=ai;this.mapper=mapper;}
    public String organization(AccountPrincipal account){String org=auth.organizationOf(account.id());if(org==null)throw ApiException.forbidden("账号尚未加入组织");return org;}
    private void manager(AccountPrincipal account){if(!account.isManager())throw ApiException.forbidden("只有管理者可以维护知识库");}
    private String vector(JsonNode result){var values=result.path("embedding");if(!values.isArray()||values.size()!=1536)throw ApiException.badRequest("嵌入向量维度必须为1536");List<String> list=new ArrayList<>();for(var v:values){if(!v.isNumber()||!Double.isFinite(v.asDouble()))throw ApiException.badRequest("嵌入向量无效");list.add(Double.toString(v.asDouble()));}return "["+String.join(",",list)+"]";}
    private String profile(JsonNode result){String value=result.path("embedding_profile").asText();if(value.isBlank()||value.length()>160)throw ApiException.badRequest("缺少向量模型标识");return value;}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw ApiException.badRequest("资料元数据无效");}}
    private void validate(String source,String content){if(source==null||source.isBlank()||source.length()>500||content==null||content.isBlank()||content.length()>100000)throw ApiException.badRequest("资料来源和内容不能为空或超长");}
    private String hash(String source,String content){return ActivityGate.hash(source.strip()+"\n"+content.strip());}
    @Transactional
    public Map<String,Object> add(AccountPrincipal actor,String source,String content,Map<String,Object> metadata){
        manager(actor);validate(source,content);String org=organization(actor),hash=hash(source,content);
        var old=db.one("SELECT id::text,source,version,embedding_profile FROM rag_documents WHERE organization_id=? AND (content_hash=? OR(content_hash IS NULL AND btrim(source)=? AND btrim(content)=?)) ORDER BY created_at,id LIMIT 1",org,hash,source.strip(),content.strip()).orElse(null);
        if(old!=null){var result=new LinkedHashMap<>(old);result.put("idempotent",true);result.put("status","legacy_unknown".equals(old.get("embedding_profile"))?"needs_reindex":"indexed");return result;}
        var embedding=ai.embeddingResult(content.strip());String id=UUID.randomUUID().toString();
        int inserted=db.update("INSERT INTO rag_documents(id,organization_id,source,content,metadata_json,embedding,content_hash,created_by,embedding_profile) VALUES(CAST(? AS uuid),?,?,?,CAST(? AS jsonb),CAST(? AS vector),?,?,?) ON CONFLICT DO NOTHING",id,org,source.strip(),content.strip(),json(metadata==null?Map.of():metadata),vector(embedding),hash,actor.id(),profile(embedding));
        var result=new LinkedHashMap<>(db.required("SELECT id::text,source,version,embedding_profile FROM rag_documents WHERE organization_id=? AND content_hash=?",org,hash));result.put("idempotent",inserted==0);result.put("status","legacy_unknown".equals(result.get("embedding_profile"))?"needs_reindex":"indexed");
        if(inserted==1&&audits!=null)audits.write("knowledge.create",actor.id(),org,"knowledge",id,null);return result;
    }
    public List<Map<String,Object>> list(AccountPrincipal actor){return db.list("SELECT id::text,organization_id,source,version,embedding_profile,created_at,updated_at FROM rag_documents WHERE organization_id=? OR organization_id IS NULL ORDER BY updated_at DESC,id LIMIT 200",organization(actor));}
    private void uuid(String id){try{if(!UUID.fromString(id).toString().equalsIgnoreCase(id))throw new IllegalArgumentException();}catch(Exception e){throw ApiException.notFound("资料不存在");}}
    public Map<String,Object> get(AccountPrincipal actor,String id){uuid(id);var row=new LinkedHashMap<>(db.one("SELECT id::text,organization_id,source,content,metadata_json::text,version,embedding_profile,created_at,updated_at FROM rag_documents WHERE id=CAST(? AS uuid) AND (organization_id=? OR organization_id IS NULL)",id,organization(actor)).orElseThrow(()->ApiException.notFound("资料不存在")));try{row.put("metadata",mapper.readTree(String.valueOf(row.remove("metadata_json"))));}catch(Exception e){row.put("metadata",Map.of());}return row;}
    @Transactional
    public Map<String,Object> update(AccountPrincipal actor,String id,JsonNode input){
        manager(actor);var old=get(actor,id);String org=organization(actor);if(!org.equals(old.get("organization_id")))throw ApiException.forbidden("公共资料仅可阅读");
        String source=input.path("source").asText(),content=input.path("content").asText();validate(source,content);
        if(!input.path("expected_version").isIntegralNumber())throw ApiException.badRequest("请提供资料版本号");
        if(((Number)old.get("version")).intValue()!=input.path("expected_version").asInt())throw new ApiException(HttpStatus.CONFLICT,"资料已更新，请刷新后重试");
        var embedding=ai.embeddingResult(content.strip());int changed;
        try{changed=db.update("UPDATE rag_documents SET source=?,content=?,metadata_json=CAST(? AS jsonb),embedding=CAST(? AS vector),content_hash=?,embedding_profile=?,version=version+1,updated_at=clock_timestamp() WHERE id=CAST(? AS uuid) AND organization_id=? AND version=?",source.strip(),content.strip(),json(input.path("metadata").isObject()?input.path("metadata"):Map.of()),vector(embedding),hash(source,content),profile(embedding),id,org,input.path("expected_version").asInt());}
        catch(org.springframework.dao.DataIntegrityViolationException e){throw new ApiException(HttpStatus.CONFLICT,"本组织已存在相同来源和内容的资料");}
        if(changed!=1)throw new ApiException(HttpStatus.CONFLICT,"资料已更新，请刷新后重试");
        if(audits!=null)audits.write("knowledge.update",actor.id(),org,"knowledge",id,null);return get(actor,id);
    }
    @Transactional
    public Map<String,Object> delete(AccountPrincipal actor,String id,int version){manager(actor);var row=get(actor,id);String org=organization(actor);if(!org.equals(row.get("organization_id")))throw ApiException.forbidden("公共资料仅可阅读");if(db.update("DELETE FROM rag_documents WHERE id=CAST(? AS uuid) AND organization_id=? AND version=?",id,org,version)!=1)throw new ApiException(HttpStatus.CONFLICT,"资料已更新，请刷新后重试");if(audits!=null)audits.write("knowledge.delete",actor.id(),org,"knowledge",id,null);return Map.of("id",id,"status","deleted");}
    public JsonNode search(AccountPrincipal actor,String query,int topK){return ai.ragSearch(organization(actor),query,Math.min(Math.max(topK,1),20));}
}
