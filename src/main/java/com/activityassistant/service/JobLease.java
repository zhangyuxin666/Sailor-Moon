package com.activityassistant.service;

import com.activityassistant.persistence.mapper.BackgroundJobMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;

/** A claim token is local to one execution, never merely to one process. */
public final class JobLease implements AutoCloseable {
    private static final ThreadLocal<JobLease> CURRENT = new ThreadLocal<>();
    private final Map<String, Object> job;
    private final BackgroundJobMapper mapper;

    public JobLease(Map<String, Object> job, BackgroundJobMapper mapper) {
        this.job = job;
        this.mapper = mapper;
        CURRENT.set(this);
    }

    public static void verify() {
        JobLease lease = CURRENT.get();
        if (lease == null) return;
        Long millis = lease.mapper.remainingLeaseMillis((String) lease.job.get("id"),
                (String) lease.job.get("locked_by"), ((Number) lease.job.get("lease_version")).longValue());
        if (millis == null) throw new Lost();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            lease.mapper.setStatementTimeout(millis.toString());
            lease.mapper.setLockTimeout(millis.toString());
        }
    }

    public static final class Lost extends RuntimeException {
        public Lost(){super("执行租约已失效或任务已超时");}
    }
    @Override public void close(){CURRENT.remove();}
}
