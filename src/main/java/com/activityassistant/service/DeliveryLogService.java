package com.activityassistant.service;
import com.activityassistant.persistence.Db;
import com.activityassistant.support.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
@Service
public class DeliveryLogService {
    private final Db db;
    public DeliveryLogService(Db db){this.db=db;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(String activity,String key,String kind,String group,String content,String status,String external,String error) {
        String now=LocalDateTime.now().toString();
        db.update("INSERT INTO delivery_events(id,activity_id,kind,channel,target,content,status,external_id,error,created_at,completed_at,logical_key) VALUES (?,?,?,'qq_group',?,?,?,?,?,?,?,?) ON CONFLICT (logical_key) WHERE logical_key IS NOT NULL DO UPDATE SET status=EXCLUDED.status,external_id=COALESCE(EXCLUDED.external_id,delivery_events.external_id),error=EXCLUDED.error,completed_at=EXCLUDED.completed_at WHERE delivery_events.status<>'sent'",
                Ids.shortId(),activity,kind,group,content,status,external,error,now,"sending".equals(status)?null:now,key);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void todo(String todo,String key,String kind,String content,String status,String external,String error) {
        db.update("INSERT INTO classroom_deliveries(id,todo_id,kind,content,status,external_id,error,created_at,logical_key) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (logical_key) WHERE logical_key IS NOT NULL DO UPDATE SET status=EXCLUDED.status,external_id=COALESCE(EXCLUDED.external_id,classroom_deliveries.external_id),error=EXCLUDED.error WHERE classroom_deliveries.status<>'sent'",
                Ids.shortId(),todo,kind,content,status,external,error,LocalDateTime.now().toString(),key);
    }
}
