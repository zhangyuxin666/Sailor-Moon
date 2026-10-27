package com.activityassistant.service;
import com.activityassistant.integration.AiClient;
import com.activityassistant.security.AccountPrincipal;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class RecapService {
    private final RecapStore store;private final AiClient ai;private final ObjectMapper mapper;
    public RecapService(RecapStore store,AiClient ai,ObjectMapper mapper){this.store=store;this.ai=ai;this.mapper=mapper;}
    public Map<String,Object> generate(String id,AccountPrincipal actor,String request){
        var row=store.begin(id,actor,request);
        if(!Boolean.TRUE.equals(row.remove("fresh")))return row;
        JsonNode snapshot=(JsonNode)row.get("snapshot"),stats=snapshot.path("stats");
        // Only bounded aggregates leave the JVM. Never send title, names, contacts, answers or task descriptions.
        Map<String,Object> aggregates=new LinkedHashMap<>();for(String key:List.of("registration_active","registration_cancelled","tasks_total","tasks_done","tasks_unassigned","reminders_total","steps","deliveries","attendance_configured","attendance_eligible","attendance_checked_in","attendance_absent","feedback_count","feedback_rating_sum","feedback_rating_counts"))aggregates.put(key,mapper.convertValue(stats.path(key),Object.class));
        String body;JsonNode generation;
        try{var result=ai.recapAggregates(aggregates);body=result.path("recap").asText();if(body.isBlank()||body.length()>20000)throw new IllegalStateException();generation=result.path("generation");if(!generation.isObject())throw new IllegalStateException();}
        catch(Exception error){body=RecapStore.fallback(stats);generation=mapper.createObjectNode().put("mode","offline").put("fallback_reason","ai_service_unavailable").put("retrieval_status","empty");}
        store.complete(String.valueOf(row.get("id")),body,generation);
        return store.get(id,String.valueOf(row.get("id")),actor);
    }
}
