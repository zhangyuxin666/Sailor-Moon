package com.activityassistant.service;

import com.activityassistant.integration.QqGatewayClient;
import com.activityassistant.support.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class OutboundDeliveryService {
    private final OutboundStore store;private final QqGatewayClient gateway;
    public OutboundDeliveryService(OutboundStore store,QqGatewayClient gateway){this.store=store;this.gateway=gateway;}
    public Map<String,Object> send(String group,String content,String reply,String key) {
        var intent=store.begin(key,group,content);
        if("sent".equals(intent.get("status")))return sent(intent.get("external_id"));
        if(!Boolean.TRUE.equals(intent.get("send"))) {
            // Querying is safe; absence after a restart does not prove QQ never accepted it.
            var remote=gateway.delivery(key);
            if("sent".equals(remote.get("status")) && intent.get("payload_hash").equals(remote.get("payload_hash"))) {
                store.complete(key,"sent",(String)remote.get("external_id"),null);return sent(remote.get("external_id"));
            }
            if("failed".equals(remote.get("status")) && intent.get("payload_hash").equals(remote.get("payload_hash"))) {
                store.complete(key,"failed",null,"网关确认未发送，请重试");
                throw new ApiException(HttpStatus.BAD_GATEWAY,"QQ Gateway 确认未发送，可重试");
            }
            store.complete(key,"unknown",null,"投递结果未知，请核对后处理");throw unknown();
        }
        try {
            var response=gateway.sendGroup(group,content,reply,key);
            store.complete(key,"sent",(String)response.get("id"),null);return response;
        }catch(QqGatewayClient.NotSent error) {
            store.complete(key,"failed",null,"网关确认未发送，可重试");throw error;
        }catch(RuntimeException error) {
            store.complete(key,"unknown",null,"投递结果未知，请核对后处理");throw unknown();
        }
    }
    private Map<String,Object> sent(Object id){var result=new java.util.LinkedHashMap<String,Object>();result.put("status","sent");result.put("id",id);return result;}
    public static ApiException unknown(){return new ApiException(HttpStatus.CONFLICT,"投递结果未知，已停止自动重发，请核对投递记录");}
}
