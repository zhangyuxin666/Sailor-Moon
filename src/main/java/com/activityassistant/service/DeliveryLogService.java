package com.activityassistant.service;
import com.activityassistant.persistence.mapper.DeliveryLogMapper;
import com.activityassistant.support.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
@Service
public class DeliveryLogService {
    private final DeliveryLogMapper mapper;

    public DeliveryLogService(DeliveryLogMapper mapper) {
        this.mapper = mapper;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(String activity,String key,String kind,String group,String content,String status,String external,String error) {
        String now=LocalDateTime.now().toString();
        mapper.recordActivity(Ids.shortId(), activity, kind, group, content, status, external,
                error, now, "sending".equals(status) ? null : now, key);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void todo(String todo,String key,String kind,String content,String status,String external,String error) {
        mapper.recordTodo(Ids.shortId(), todo, kind, content, status, external, error,
                LocalDateTime.now().toString(), key);
    }
}
