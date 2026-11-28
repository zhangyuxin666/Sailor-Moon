package com.activityassistant.service;

import com.activityassistant.persistence.ReplicaReads;
import com.activityassistant.persistence.mapper.ManagementMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ManagementQueries {
    private final ReplicaReads replicaReads;
    private final ManagementMapper mapper;

    public ManagementQueries(ReplicaReads replicaReads, ManagementMapper mapper) {
        this.replicaReads = replicaReads;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> notifications(String accountId) {
        return replicaReads.readMapper(ManagementMapper.class, mapper, reader -> reader.listNotifications(accountId));
    }

    public Optional<Map<String, Object>> findRun(String runId) {
        return mapper.findRun(runId);
    }

    public Optional<Map<String, Object>> findActivityOwner(String activityId) {
        return mapper.findActivityOwner(activityId);
    }
}
