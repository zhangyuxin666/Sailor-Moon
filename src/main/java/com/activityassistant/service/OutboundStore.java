package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Map;

/** Intent/results must survive rollback of the caller's business transaction. */
@Service
public class OutboundStore {
    private final Db db;
    public OutboundStore(Db db){this.db=db;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Map<String,Object> begin(String key,String group,String content) {
        String hash=ActivityGate.hash(group+"\n"+content);
        int inserted=db.update("INSERT INTO outbound_deliveries(logical_key,payload_hash,group_openid,content,status) VALUES (?,?,?,?,'sending') ON CONFLICT DO NOTHING",key,hash,group,content);
        Map<String,Object> row=db.required("SELECT * FROM outbound_deliveries WHERE logical_key=? FOR UPDATE",key);
        if(!hash.equals(row.get("payload_hash")))throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,"投递标识已用于不同内容");
        boolean send=inserted==1;
        if("failed".equals(row.get("status"))) {
            db.update("UPDATE outbound_deliveries SET status='sending',attempts=attempts+1,error=NULL,updated_at=clock_timestamp() WHERE logical_key=?",key);send=true;
        }
        var result=new java.util.LinkedHashMap<>(row);result.put("send",send);return result;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(String key,String status,String externalId,String error) {
        db.update("UPDATE outbound_deliveries SET status=?,external_id=COALESCE(?,external_id),error=?,updated_at=clock_timestamp() WHERE logical_key=? AND status<>'sent'",status,externalId,error,key);
    }
}
