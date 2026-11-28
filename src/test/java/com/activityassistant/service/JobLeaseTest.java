package com.activityassistant.service;
import com.activityassistant.persistence.mapper.BackgroundJobMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
class JobLeaseTest {
 @Test void nonWorkerCallsHaveNoImplicitLease(){assertDoesNotThrow(JobLease::verify);}
 @Test void expiredClaimCannotEnterBusinessStep(){
     var mapper = mock(BackgroundJobMapper.class);
     when(mapper.remainingLeaseMillis("j", "old", 1L)).thenReturn(null);
     try(var scope = new JobLease(Map.of("id", "j", "locked_by", "old", "lease_version", 1L), mapper)){
         assertThrows(JobLease.Lost.class, JobLease::verify);
     }
     verify(mapper).remainingLeaseMillis("j", "old", 1L);
     JobLease.verify();
     verifyNoMoreInteractions(mapper);
 }
 @Test void validClaimAndThreadCleanup(){
     var mapper = mock(BackgroundJobMapper.class);
     when(mapper.remainingLeaseMillis("j", "owner", 2L)).thenReturn(10000L);
     try(var scope = new JobLease(Map.of("id", "j", "locked_by", "owner", "lease_version", 2L), mapper)){
         assertDoesNotThrow(JobLease::verify);
     }
     verify(mapper).remainingLeaseMillis("j", "owner", 2L);
     JobLease.verify();
     verifyNoMoreInteractions(mapper);
 }
}
