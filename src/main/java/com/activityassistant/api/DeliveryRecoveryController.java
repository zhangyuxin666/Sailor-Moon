package com.activityassistant.api;

import com.activityassistant.integration.QqGatewayClient;
import com.activityassistant.persistence.mapper.DeliveryRecoveryMapper;
import com.activityassistant.service.*;
import com.activityassistant.support.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.time.LocalDateTime;

@RestController
public class DeliveryRecoveryController {
    private final DeliveryRecoveryMapper recovery;private final ActivityGate gate;private final QqGatewayClient gateway;private final OutboundStore store;private final QqService qq;
    public DeliveryRecoveryController(DeliveryRecoveryMapper recovery,ActivityGate gate,QqGatewayClient gateway,OutboundStore store,QqService qq){this.recovery=recovery;this.gate=gate;this.gateway=gateway;this.store=store;this.qq=qq;}
    @PostMapping("/activities/{id}/deliveries/{deliveryId}/check")
    public Map<String,Object> check(Authentication auth,@PathVariable String id,@PathVariable String deliveryId) {
        var actor=AuthController.required(auth);
        return gate.during(id,true,()->{
            var activity=gate.owner(id,actor);
            var event=recovery.findEvent(deliveryId,id).orElseThrow(()->ApiException.notFound("投递记录不存在"));
            String org=recovery.findActivityOrganization(id).map(r->String.valueOf(r.get("organization_id"))).orElse(null);
            if(!qq.isGroupAuthorized(org,String.valueOf(event.get("target"))))throw ApiException.forbidden("通知渠道授权已失效");
            String key=(String)event.get("logical_key");
            if(key==null)throw ApiException.badRequest("历史投递缺少可核对标识，请人工检查群记录");
            var saved=recovery.findOutbound(key).orElseThrow(()->ApiException.badRequest("投递结果未知，请人工检查群记录"));
            var remote="sent".equals(saved.get("status"))?saved:gateway.delivery(key);
            if(!"sent".equals(remote.get("status"))||!saved.get("payload_hash").equals(remote.get("payload_hash")))return Map.of("status","unknown","message","投递结果仍未知，未重新发送，请人工核对群记录");
            String external=(String)remote.get("external_id");store.complete(key,"sent",external,null);
            String now=LocalDateTime.now().toString();
            recovery.markDeliverySent(external,now,deliveryId);
            if(key.startsWith("change:"))recovery.markChangeSent(now,key.substring(7),id);
            if(key.startsWith("reminder:"))recovery.markReminderSent(key.substring(9),id);
            if("failed".equals(activity.get("status"))) {
                int changed=recovery.requeueActivityJob(id);
                if(changed==1){recovery.queueActivity(id);recovery.resetActivityRun(id);}
            }
            if(key.startsWith("reminder:")||key.startsWith("change:")) {
                String kind=key.startsWith("reminder:")?"reminder":"activity_change";
                String ref=key.substring(key.indexOf(':')+1);
                recovery.completeFailedJob(now,kind,ref);
            }
            gate.audit(id,actor.id(),"delivery.reconcile","delivery",deliveryId,Map.of("status","sent"));
            return Map.of("status","sent","message","已核对网关持久记录，无需重新发送；未完成流程可继续执行");
        });
    }
    @PostMapping("/activities/{id}/reminders/{reminderId}/retry")
    public Map<String,Object> retryReminder(Authentication auth,@PathVariable String id,@PathVariable String reminderId) {
        var actor=AuthController.required(auth);
        return gate.during(id,true,()->{
            gate.active(gate.owner(id,actor));
            var reminder=recovery.findReminder(reminderId,id).orElseThrow(()->ApiException.notFound("提醒不存在"));
            String status=String.valueOf(reminder.get("status"));
            if("sent".equals(status)||"scheduled".equals(status))return Map.of("status",status,"idempotent",true);
            if(!"failed".equals(status))throw ApiException.badRequest("请先核对未知投递；已取消的提醒不能重发");
            int changed=recovery.requeueReminderJob(reminderId);
            if(changed!=1)throw ApiException.badRequest("执行状态已变化，请刷新后重试");
            recovery.scheduleReminder(reminderId);
            gate.audit(id,actor.id(),"reminder.retry","reminder",reminderId,Map.of());
            return Map.of("status","scheduled");
        });
    }
}
