package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import java.util.Map;

/** A claim token is local to one execution, never merely to one process. */
public final class JobLease implements AutoCloseable {
    private static final ThreadLocal<Map<String,Object>> CURRENT = new ThreadLocal<>();
    public JobLease(Map<String,Object> job) { CURRENT.set(job); }
    public static void verify(Db db) {
        Map<String,Object> job=CURRENT.get();
        if(job==null)return;
        var row=db.one("SELECT GREATEST(1,floor(extract(epoch FROM (deadline_at-clock_timestamp()))*1000)) AS remaining FROM background_jobs WHERE id=? AND status='running' AND locked_by=? AND lease_version=? AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()",
                job.get("id"),job.get("locked_by"),job.get("lease_version")).orElseThrow(Lost::new);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            long millis=((Number)row.get("remaining")).longValue();
            db.one("SELECT set_config('statement_timeout',?,true)",String.valueOf(millis));
            db.one("SELECT set_config('lock_timeout',?,true)",String.valueOf(millis));
        }

    }
    public static final class Lost extends RuntimeException {
        public Lost(){super("执行租约已失效或任务已超时");}
    }
    @Override public void close(){CURRENT.remove();}
}
