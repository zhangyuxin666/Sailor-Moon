package com.activityassistant.service;

import com.activityassistant.orchestration.ActivityOrchestrator;
import com.activityassistant.persistence.mapper.ChangeDeliveryMapper;
import com.activityassistant.support.PublicErrors;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ChangeDeliveryService {
    private final ChangeDeliveryMapper changes;
    private final ActivityGate gate;
    private final ActivityOrchestrator orchestrator;

    public ChangeDeliveryService(ChangeDeliveryMapper changes, ActivityGate gate, ActivityOrchestrator orchestrator) {
        this.changes = changes;
        this.gate = gate;
        this.orchestrator = orchestrator;
    }
    public void deliver(String id) {
        var event=changes.findChange(id).orElseThrow();
        String activityId=String.valueOf(event.get("activity_id"));
        gate.during(activityId,true,()->{
            var fresh=changes.findChange(id).orElseThrow();
            if(Set.of("sent","local","cancelled").contains(String.valueOf(fresh.get("notification_status"))))return null;
            var activity=changes.findActivityStatus(activityId).orElseThrow();
            if("cancelled".equals(activity.get("status")) && !"cancel".equals(event.get("kind"))) {
                changes.cancel(id, LocalDateTime.now().toString());
                return null;
            }
            String group=changes.findChannel(activityId).map(r->String.valueOf(r.get("group_openid"))).orElse(null);
            // Registration policy changes are visible on the form and do not spam the QQ group.
            boolean local=group==null || String.valueOf(event.get("kind")).startsWith("registration_");
            if(!local)orchestrator.sendGroupMessage(activityId,group,String.valueOf(event.get("content")),"cancel".equals(event.get("kind"))?"activity_cancelled":"activity_change","change:"+id);
            changes.complete(id, local ? "local" : "sent", LocalDateTime.now().toString());
            return null;
        });
    }
    public void failed(String id, Exception error, boolean retrying) {
        String status = retrying ? "pending" : "failed";
        if (error instanceof com.activityassistant.support.ApiException e && e.status().value() == 409) {
            status = "unknown";
        }
        changes.markFailure(id, status, PublicErrors.failure(error));
    }
}
