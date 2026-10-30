package com.activityassistant.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Explicit opt-in reads only. All ordinary Db operations and transactions stay on primary. */
@Component
public class ReplicaReads {
    private final JdbcTemplate primary;
    private final JdbcTemplate replica;
    private final HikariDataSource pool;
    private final LongAdder replicaQueries = new LongAdder();
    private final LongAdder fallbacks = new LongAdder();

    @org.springframework.beans.factory.annotation.Autowired
    public ReplicaReads(JdbcTemplate primary,
                        @Value("${app.database.read-enabled:false}") boolean enabled,
                        @Value("${app.database.read-url:}") String url,
                        @Value("${app.database.read-username:activity_reader}") String username,
                        @Value("${app.database.read-password:}") String password,
                        @Value("${app.database.read-pool-size:5}") int poolSize) {
        this.primary = primary;
        if (enabled) {
            if (url.isBlank() || password.isBlank()) throw new IllegalArgumentException("Read database URL and password are required");
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url); config.setUsername(username); config.setPassword(password);
            config.setPoolName("replica-reads"); config.setMaximumPoolSize(poolSize); config.setMinimumIdle(0);
            config.setReadOnly(true); config.setConnectionTimeout(1500); config.setValidationTimeout(1000);
            config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
            config.setInitializationFailTimeout(-1);
            pool = new HikariDataSource(config);
            replica = template(pool);
        } else { pool = null; replica = null; }
    }

    // Separate from Boot's DataSource beans so Flyway, transactions, sessions and workers cannot use the replica.
    ReplicaReads(JdbcTemplate primary, JdbcTemplate replica) {
        this.primary = primary; this.replica = replica; this.pool = null;
    }

    private static JdbcTemplate template(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(3);
        return jdbc;
    }

    public List<Map<String, Object>> list(String sql, Object... args) {
        // A transaction can contain uncommitted writes or a repeatable-read snapshot. Never split it.
        if (replica == null || TransactionSynchronizationManager.isActualTransactionActive())
            return primary.queryForList(sql, args);
        // Sample AFTER authorization. Check replay and read on one connection at READ COMMITTED;
        // its business query snapshot cannot predate this check. Lag falls back immediately.
        String fence = primary.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
        try {
            return readSnapshot(sql, args, fence);
        } catch (org.springframework.dao.DataAccessException error) {
            fallbacks.increment();
            return primary.queryForList(sql, args);
        }
    }

    private List<Map<String, Object>> readSnapshot(String sql, Object[] args, String fence) {
        return replica.execute((org.springframework.jdbc.core.ConnectionCallback<List<Map<String,Object>>>) connection -> {
            connection.setAutoCommit(false);
            try {
                boolean ready;
                try (var statement = connection.prepareStatement("SELECT pg_is_in_recovery() AND pg_last_wal_replay_lsn() >= CAST(? AS pg_lsn)")) {
                    statement.setQueryTimeout(3); statement.setString(1, fence);
                    try (var result = statement.executeQuery()) { result.next(); ready = result.getBoolean(1); }
                }
                if (!ready) {
                    connection.rollback(); fallbacks.increment();
                    return primary.queryForList(sql, args);
                }
                List<Map<String,Object>> rows;
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(3);
                    for (int index=0; index<args.length; index++) statement.setObject(index+1, args[index]);
                    try (var result = statement.executeQuery()) {
                        rows = new java.util.ArrayList<>();
                        var mapper = new org.springframework.jdbc.core.ColumnMapRowMapper();
                        while (result.next()) rows.add(mapper.mapRow(result, rows.size()));
                    }
                }
                connection.commit(); replicaQueries.increment(); return rows;
            } finally {
                connection.rollback(); connection.setAutoCommit(true);
            }
        });
    }

    public Map<String,Object> status() {
        return Map.of("enabled", replica != null, "replica_queries", replicaQueries.sum(),
                "primary_fallbacks", fallbacks.sum(), "policy", "explicit_reads_with_wal_fence; transactions_on_primary");
    }

    @PreDestroy public void close() { if (pool != null) pool.close(); }
}
