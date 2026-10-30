package com.activityassistant.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReplicaReadsTest {
    private final JdbcTemplate primary = mock(JdbcTemplate.class);
    private final JdbcTemplate replica = mock(JdbcTemplate.class);
    private final ReplicaReads reads = new ReplicaReads(primary, replica);
    @AfterEach void clearTransaction() { TransactionSynchronizationManager.clear(); }

    @Test void disabledUsesPrimaryWithoutProbingWal() {
        var disabled = new ReplicaReads(primary, null);
        when(primary.queryForList("SELECT 1", new Object[0])).thenReturn(List.of(Map.of("n", 1)));
        assertThat(disabled.list("SELECT 1")).containsExactly(Map.of("n",1));
        verify(primary, never()).queryForObject(anyString(), eq(String.class));
    }

    @Test void transactionsNeverLeavePrimaryIncludingReadOnlyTransactions() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        reads.list("SELECT 1");
        verify(primary).queryForList("SELECT 1", new Object[0]); verifyNoInteractions(replica);
    }

    @Test void unavailableReplicaFallsBackAndCountsIt() {
        when(primary.queryForObject(anyString(), eq(String.class))).thenReturn("0/1234");
        when(replica.execute(any(ConnectionCallback.class))).thenThrow(new DataAccessResourceFailureException("offline"));
        reads.list("SELECT * FROM steps WHERE activity_id=?", "a");
        verify(primary).queryForList("SELECT * FROM steps WHERE activity_id=?", "a");
        assertThat(reads.status()).containsEntry("primary_fallbacks",1L).containsEntry("replica_queries",0L);
    }

    @SuppressWarnings("unchecked")
    private void connection(boolean caughtUp) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement fence = mock(PreparedStatement.class);
        ResultSet position = mock(ResultSet.class);
        when(connection.prepareStatement(contains("pg_is_in_recovery"))).thenReturn(fence);
        when(fence.executeQuery()).thenReturn(position); when(position.getBoolean(1)).thenReturn(caughtUp);
        PreparedStatement query = mock(PreparedStatement.class);
        when(connection.prepareStatement("SELECT id FROM steps WHERE activity_id=?")).thenReturn(query);
        ResultSet result = mock(ResultSet.class); when(query.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(false);
        when(primary.queryForObject(anyString(), eq(String.class))).thenReturn("0/1234");
        when(replica.execute(any(ConnectionCallback.class))).thenAnswer(call ->
                ((ConnectionCallback<?>)call.getArgument(0)).doInConnection(connection));
    }

    @Test void lagOrPromotedReplicaFallsBackBeforeBusinessRead() throws Exception {
        connection(false);
        reads.list("SELECT id FROM steps WHERE activity_id=?", "a");
        verify(primary).queryForList("SELECT id FROM steps WHERE activity_id=?", "a");
        assertThat(reads.status()).containsEntry("primary_fallbacks",1L);
    }

    @Test void caughtUpReplicaServesQueryIncludingEmptyResultsWithoutPrimaryRead() throws Exception {
        connection(true);
        assertThat(reads.list("SELECT id FROM steps WHERE activity_id=?", "a")).isEmpty();
        verify(primary, never()).queryForList(anyString(), any(Object[].class));
        assertThat(reads.status()).containsEntry("replica_queries",1L).containsEntry("primary_fallbacks",0L);
    }
}
