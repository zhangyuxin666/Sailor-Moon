package com.activityassistant.service;

import com.activityassistant.orchestration.ActivityOrchestrator;
import com.activityassistant.persistence.Db;
import com.activityassistant.support.PublicErrors;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ChangeDeliveryService {
    private final Db db;private final ActivityGate gate;private final ActivityOrchestrator orchestrator;
    public ChangeDeliveryService(Db db,ActivityGate gate,ActivityOrchestrator orchestrator){this.db=db;this.gate=gate;this.orchestrator=orchestrator;}
    public void deliver(String id) {
        var event=db.one("SELECT * FROM activity_changes WHERE id=?",id).orElseThrow();
        String activityId=String.valueOf(event.get("activity_id"));
        gate.during(activityId,true,()->{
            var fresh=db.required("SELECT * FROM activity_changes WHERE id=?",id);
            if(Set.of("sent","local","cancelled").contains(String.valueOf(fresh.get("notification_status"))))return null;
            var activity=db.required("SELECT status FROM activities WHERE id=?",activityId);
            if("cancelled".equals(activity.get("status")) && !"cancel".equals(event.get("kind"))) {
                db.update("UPDATE activity_changes SET notification_status='cancelled',completed_at=? WHERE id=?",LocalDateTime.now().toString(),id);return null;
            }
            String group=db.one("SELECT group_openid FROM activity_channels WHERE activity_id=?",activityId).map(r->String.valueOf(r.get("group_openid"))).orElse(null);
            // Registration policy changes are visible on the form and do not spam the QQ group.
            boolean local=group==null || String.valueOf(event.get("kind")).startsWith("registration_");
            if(!local)orchestrator.sendGroupMessage(activityId,group,String.valueOf(event.get("content")),"cancel".equals(event.get("kind"))?"activity_cancelled":"activity_change","change:"+id);
            db.update("UPDATE activity_changes SET notification_status=?,error=NULL,completed_at=? WHERE id=?",local?"local":"sent",LocalDateTime.now().toString(),id);return null;
        });
    }
    public void failed(String id,Exception error,boolean retrying){db.update("UPDATE activity_changes SET notification_status=?,error=? WHERE id=?",error instanceof com.activityassistant.support.ApiException e&&e.status().value()==409?"unknown":retrying?"pending":"failed",PublicErrors.failure(error),id);}
}
