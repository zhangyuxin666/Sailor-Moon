package com.activityassistant.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.activityassistant.persistence.mapper.ReplicationMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.SqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import com.activityassistant.persistence.mapper.ManagementMapper;
import com.activityassistant.persistence.mapper.ActivityManagementMapper;
import com.activityassistant.persistence.mapper.ActivityMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Explicit opt-in reads only. All ordinary mapper operations and transactions stay on primary. */
@Component
public class ReplicaReads {
    private final ReplicationMapper primary;
    private final SqlSessionTemplate replica;
    private final TransactionOperations replicaTransaction;
    private final HikariDataSource pool;
    private final LongAdder replicaQueries = new LongAdder();
    private final LongAdder fallbacks = new LongAdder();

    @org.springframework.beans.factory.annotation.Autowired
    public ReplicaReads(ReplicationMapper primary, @Value("${app.database.read-enabled:false}") boolean enabled,
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
            var factory = replicaFactory(pool);
            factory.getConfiguration().setDefaultStatementTimeout(3);
            factory.getConfiguration().setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            factory.getConfiguration().addMapper(ManagementMapper.class);
            factory.getConfiguration().addMapper(ActivityManagementMapper.class);
            factory.getConfiguration().addMapper(ActivityMapper.class);
            factory.getConfiguration().addMapper(ReplicationMapper.class);
            factory.getConfiguration().setCallSettersOnNulls(true);
            factory.getConfiguration().setReturnInstanceForEmptyRow(true);
            replica = new SqlSessionTemplate(factory);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            transaction.setReadOnly(true);
            replicaTransaction = transaction;
        } else { pool = null; replica = null; replicaTransaction = null; }
    }

    private static org.apache.ibatis.session.SqlSessionFactory replicaFactory(HikariDataSource source) {
        var bean = new SqlSessionFactoryBean();
        bean.setDataSource(source);
        try {
            return java.util.Objects.requireNonNull(bean.getObject());
        } catch (Exception failure) {
            source.close();
            throw new IllegalStateException("Cannot create replica MyBatis session factory", failure);
        }
    }

    // Separate from Boot's DataSource beans so Flyway, transactions, sessions and workers cannot use the replica.
    ReplicaReads(ReplicationMapper primary, SqlSessionTemplate replica, TransactionOperations replicaTransaction) {
        this.primary = primary;
        this.replica = replica;
        this.replicaTransaction = replicaTransaction;
        this.pool = null;
    }

    public <T> List<Map<String, Object>> readMapper(Class<T> type, T mapper,
            java.util.function.Function<T, List<Map<String, Object>>> query) {
        return route(() -> query.apply(mapper), () -> query.apply(replica.getMapper(type)));
    }

    private List<Map<String, Object>> route(Supplier<List<Map<String, Object>>> primaryRead,
                                          Supplier<List<Map<String, Object>>> replicaRead) {
        // A transaction can contain uncommitted writes or a repeatable-read snapshot. Never split it.
        if (replica == null || TransactionSynchronizationManager.isActualTransactionActive())
            return primaryRead.get();
        // Sample AFTER authorization. Check replay and read on one connection at READ COMMITTED;
        // its business query snapshot cannot predate this check. Lag falls back immediately.
        String fence = primary.currentPosition();
        try {
            List<Map<String, Object>> rows = replicaTransaction.execute(status -> {
                return Boolean.TRUE.equals(replica.getMapper(ReplicationMapper.class).hasReplayed(fence)) ? replicaRead.get() : null;
            });
            if (rows != null) {
                replicaQueries.increment();
                return rows;
            }
        } catch (DataAccessException | org.springframework.transaction.TransactionException unavailable) {
            // A replica connection or statement failure falls back after its transaction closes.
        }
        fallbacks.increment();
        return primaryRead.get();
    }

    public Map<String,Object> status() {
        return Map.of("enabled", replica != null, "replica_queries", replicaQueries.sum(),
                "primary_fallbacks", fallbacks.sum(), "policy", "explicit_reads_with_wal_fence; transactions_on_primary");
    }

    @PreDestroy public void close() { if (pool != null) pool.close(); }
}
