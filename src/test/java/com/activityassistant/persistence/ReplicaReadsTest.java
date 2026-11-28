package com.activityassistant.persistence;

import com.activityassistant.persistence.mapper.ActivityMapper;
import com.activityassistant.persistence.mapper.ReplicationMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReplicaReadsTest {
    private final ActivityMapper primary = mock(ActivityMapper.class);
    private final ActivityMapper replica = mock(ActivityMapper.class);
    private final ReplicationMapper primaryWal = mock(ReplicationMapper.class);
    private final ReplicationMapper replicaWal = mock(ReplicationMapper.class);
    private final SqlSessionTemplate session = mock(SqlSessionTemplate.class);
    private final ReplicaReads reads = new ReplicaReads(primaryWal, session, TransactionOperations.withoutTransaction());

    @BeforeEach
    void mapReplicaQueries() {
        when(session.getMapper(ReplicationMapper.class)).thenReturn(replicaWal);
        when(session.getMapper(ActivityMapper.class)).thenReturn(replica);
    }

    private List<Map<String, Object>> list(ReplicaReads route) {
        return route.readMapper(ActivityMapper.class, primary, mapper -> mapper.listSteps("a"));
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void disabledUsesPrimaryWithoutProbingWal() {
        when(primary.listSteps("a")).thenReturn(List.of(Map.of("id", 1)));
        assertThat(list(new ReplicaReads(primaryWal, null, null)))
                .containsExactly(Map.of("id", 1));
        verifyNoMoreInteractionsAfterPrimaryRead();
    }

    @Test
    void transactionsNeverLeavePrimaryIncludingReadOnlyTransactions() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        list(reads);
        verifyNoMoreInteractionsAfterPrimaryRead();
    }

    @Test
    void unavailableReplicaFallsBackAndCountsIt() {
        stubPrimaryFence();
        when(replicaWal.hasReplayed("0/1234"))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        assertFallback();
    }

    @Test
    void lagOrPromotedReplicaFallsBackBeforeBusinessRead() {
        stubReplay(false);
        assertFallback();
        verify(replica, never()).listSteps("a");
    }

    @Test
    void caughtUpReplicaServesQueryIncludingEmptyResultsWithoutPrimaryRead() {
        stubReplay(true);
        when(replica.listSteps("a")).thenReturn(List.of());
        assertThat(list(reads)).isEmpty();
        verify(replica).listSteps("a");
        verify(primary, never()).listSteps("a");
        assertThat(reads.status()).containsEntry("primary_fallbacks", 0L).containsEntry("replica_queries", 1L);
    }

    private void assertFallback() {
        list(reads);
        verify(primary).listSteps("a");
        assertThat(reads.status()).containsEntry("primary_fallbacks", 1L).containsEntry("replica_queries", 0L);
    }

    private void verifyNoMoreInteractionsAfterPrimaryRead() {
        verify(primary).listSteps("a");
        verifyNoMoreInteractions(primary);
        verifyNoInteractions(primaryWal, replicaWal, replica);
    }

    private void stubReplay(boolean caughtUp) {
        stubPrimaryFence();
        when(replicaWal.hasReplayed("0/1234"))
                .thenReturn(caughtUp);
    }

    private void stubPrimaryFence() {
        when(primaryWal.currentPosition()).thenReturn("0/1234");
    }
}
