package com.activityassistant.service;
import com.activityassistant.persistence.Db;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
class JobLeaseTest {
 @Test void nonWorkerCallsHaveNoImplicitLease(){Db db=mock(Db.class);JobLease.verify(db);verifyNoInteractions(db);}
 @Test void expiredClaimCannotEnterBusinessStep(){Db db=mock(Db.class);when(db.one(anyString(),any(),any(),any())).thenReturn(Optional.empty());try(JobLease scope=new JobLease(Map.of("id","j","locked_by","old","lease_version",1L))){assertThrows(JobLease.Lost.class,()->JobLease.verify(db));}reset(db);JobLease.verify(db);verifyNoInteractions(db);}
 @Test void validClaimAndThreadCleanup(){Db db=mock(Db.class);when(db.one(anyString(),eq("j"),eq("owner"),eq(2L))).thenReturn(Optional.of(Map.of("remaining",10000L)));try(JobLease scope=new JobLease(Map.of("id","j","locked_by","owner","lease_version",2L))){assertDoesNotThrow(()->JobLease.verify(db));}reset(db);JobLease.verify(db);verifyNoInteractions(db);}
}
