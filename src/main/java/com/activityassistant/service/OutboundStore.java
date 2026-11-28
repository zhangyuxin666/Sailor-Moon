package com.activityassistant.service;

import com.activityassistant.persistence.mapper.OutboundMapper;
import com.activityassistant.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Map;

/** Intent/results must survive rollback of the caller's business transaction. */
@Service
public class OutboundStore {
    private final OutboundMapper outbound;
    public OutboundStore(OutboundMapper outbound){this.outbound=outbound;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Map<String,Object> begin(String key,String group,String content) {
        String hash=ActivityGate.hash(group+"\n"+content);
        int inserted=outbound.insertIntent(key,hash,group,content);
        Map<String,Object> row=outbound.lockIntent(key);
        if(!hash.equals(row.get("payload_hash")))throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,"投递标识已用于不同内容");
        boolean send=inserted==1;
        if("failed".equals(row.get("status"))) {
            outbound.retryIntent(key);send=true;
        }
        var result=new java.util.LinkedHashMap<>(row);result.put("send",send);return result;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(String key,String status,String externalId,String error) {
        outbound.completeIntent(status,externalId,error,key);
    }
}
