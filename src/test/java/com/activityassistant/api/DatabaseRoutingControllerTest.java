package com.activityassistant.api;

import com.activityassistant.persistence.ReplicaReads;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;

class DatabaseRoutingControllerTest {
    @Test void membersCannotReadRoutingState() {
        var reads = mock(ReplicaReads.class);
        var member = new AccountPrincipal("a","member","Member","","member","active","",0);
        var controller = new DatabaseRoutingController(reads);
        assertThatThrownBy(() -> controller.database(new UsernamePasswordAuthenticationToken(member,"")))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(reads);
    }
    @Test void managerCanReadSafeCounters() {
        var reads = mock(ReplicaReads.class);
        when(reads.status()).thenReturn(Map.of("enabled",true,"replica_queries",2L));
        var manager = new AccountPrincipal("a","manager","Manager","","manager","active","",0);
        assertThat(new DatabaseRoutingController(reads).database(new UsernamePasswordAuthenticationToken(manager,"")))
                .containsEntry("enabled",true).containsEntry("replica_queries",2L);
    }
}
