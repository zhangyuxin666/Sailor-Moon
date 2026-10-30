package com.activityassistant.api;

import com.activityassistant.integration.QqGatewayClient;
import com.activityassistant.persistence.Db;
import com.activityassistant.service.*;
import com.activityassistant.support.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.time.LocalDateTime;

@RestController
public class DeliveryRecoveryController {
    private final Db db;private final ActivityGate gate;private final QqGatewayClient gateway;private final OutboundStore store;private final QqService qq;
    public DeliveryRecoveryController(Db db,ActivityGate gate,QqGatewayClient gateway,OutboundStore store,QqService qq){this.db=db;this.gate=gate;this.gateway=gateway;this.store=store;this.qq=qq;}
    @PostMapping("/activities/{id}/deliveries/{deliveryId}/check")
    public Map<String,Object> check(Authentication auth,@PathVariable String id,@PathVariable String deliveryId) {
        var actor=AuthController.required(auth);
        return gate.during(id,true,()->{
            var activity=gate.owner(id,actor);
            var event=db.one("SELECT * FROM delivery_events WHERE id=? AND activity_id=?",deliveryId,id).orElseThrow(()->ApiException.notFound("投递记录不存在"));
            String org=db.one("SELECT organization_id FROM activity_classes WHERE activity_id=?",id).map(r->String.valueOf(r.get("organization_id"))).orElse(null);
            if(!qq.isGroupAuthorized(org,String.valueOf(event.get("target"))))throw ApiException.forbidden("通知渠道授权已失效");
            String key=(String)event.get("logical_key");
            if(key==null)throw ApiException.badRequest("历史投递缺少可核对标识，请人工检查群记录");
            var saved=db.one("SELECT * FROM outbound_deliveries WHERE logical_key=?",key).orElseThrow(()->ApiException.badRequest("投递结果未知，请人工检查群记录"));
            var remote="sent".equals(saved.get("status"))?saved:gateway.delivery(key);
            if(!"sent".equals(remote.get("status"))||!saved.get("payload_hash").equals(remote.get("payload_hash")))return Map.of("status","unknown","message","投递结果仍未知，未重新发送，请人工核对群记录");
            String external=(String)remote.get("external_id");store.complete(key,"sent",external,null);
            String now=LocalDateTime.now().toString();
            db.update("UPDATE delivery_events SET status='sent',external_id=?,error=NULL,completed_at=? WHERE id=?",external,now,deliveryId);
            if(key.startsWith("change:"))db.update("UPDATE activity_changes SET notification_status='sent',error=NULL,completed_at=? WHERE id=? AND activity_id=?",now,key.substring(7),id);
            if(key.startsWith("reminder:"))db.update("UPDATE reminders SET status='sent',error=NULL WHERE id=? AND activity_id=?",key.substring(9),id);
            if("failed".equals(activity.get("status"))) {
                int changed=db.update("UPDATE background_jobs SET status='queued',attempts=0,next_attempt_at=NULL,error=NULL,finished_at=NULL,locked_by=NULL,lease_until=NULL,lease_version=lease_version+1 WHERE kind='activity' AND ref_id=? AND status='failed'",id);
                if(changed==1){db.update("UPDATE activities SET status='queued' WHERE id=?",id);db.update("UPDATE agent_runs SET status='pending',error=NULL WHERE kind='activity' AND ref_id=? AND status='failed'",id);}
            }
            if(key.startsWith("reminder:")||key.startsWith("change:")) {
                String kind=key.startsWith("reminder:")?"reminder":"activity_change";
                String ref=key.substring(key.indexOf(':')+1);
                db.update("UPDATE background_jobs SET status='succeeded',error=NULL,finished_at=?,locked_by=NULL,lease_until=NULL WHERE kind=? AND ref_id=? AND status='failed'",now,kind,ref);
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
            var reminder=db.one("SELECT * FROM reminders WHERE id=? AND activity_id=?",reminderId,id).orElseThrow(()->ApiException.notFound("提醒不存在"));
            String status=String.valueOf(reminder.get("status"));
            if("sent".equals(status)||"scheduled".equals(status))return Map.of("status",status,"idempotent",true);
            if(!"failed".equals(status))throw ApiException.badRequest("请先核对未知投递；已取消的提醒不能重发");
            int changed=db.update("UPDATE background_jobs SET status='queued',attempts=0,error=NULL,finished_at=NULL,next_attempt_at=NULL,locked_by=NULL,lease_until=NULL,lease_version=lease_version+1 WHERE kind='reminder' AND ref_id=? AND status='failed'",reminderId);
            if(changed!=1)throw ApiException.badRequest("执行状态已变化，请刷新后重试");
            db.update("UPDATE reminders SET status='scheduled',error=NULL WHERE id=?",reminderId);
            gate.audit(id,actor.id(),"reminder.retry","reminder",reminderId,Map.of());
            return Map.of("status","scheduled");
        });
    }
}
