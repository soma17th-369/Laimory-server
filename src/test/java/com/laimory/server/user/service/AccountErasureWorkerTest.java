package com.laimory.server.user.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.user.entity.AccountErasureJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * 삭제 pass가 finalization 뒤에 subject 캐시를 걷어내는지 고정한다(#429). 대상 해석
 * ({@code resolveTarget})이 캐시에 이 회원을 적재하므로, 그대로 두면 이미 지운 mapping의 해석이
 * TTL까지 이 host에 남는다 — 적재한 host 자신이 지워 "erasure 이후 캐시가 비어 있다"를 복원한다.
 */
class AccountErasureWorkerTest {

    private static final long USER_ID = 4_242L;
    private static final long JOB_ID = 9L;

    private AccountErasureJobService jobService;
    private AccountErasureService erasureService;
    private SubjectMappingService subjectMappingService;
    private AccountErasureWorker worker;

    @BeforeEach
    void setUp() {
        jobService = mock(AccountErasureJobService.class);
        erasureService = mock(AccountErasureService.class);
        subjectMappingService = mock(SubjectMappingService.class);
        worker = new AccountErasureWorker(jobService, erasureService, subjectMappingService, properties(),
                new SyncTaskExecutor(), new SyncTaskExecutor(),
                Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void deletePass_evictsCachedMappingAfterFinalization() {
        AccountErasureJob job = job();
        when(jobService.claimForDelete(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(job))
                .thenReturn(List.of());
        when(erasureService.resolveTarget(USER_ID)).thenReturn(UUID.randomUUID());

        worker.deleteQuiescedJobs();

        // finalization commit(정상 반환) 뒤에 evict — 그 전에 지우면 이 호출이 다시 적재한다.
        InOrder inOrder = inOrder(erasureService, subjectMappingService);
        inOrder.verify(erasureService).finalizeErasure(anyLong(), anyLong(), any());
        inOrder.verify(subjectMappingService).evictCachedMapping(USER_ID);
    }

    @Test
    void deletePass_failedFinalization_leavesCacheAlone() {
        AccountErasureJob job = job();
        when(jobService.claimForDelete(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(job))
                .thenReturn(List.of());
        when(erasureService.resolveTarget(USER_ID)).thenReturn(UUID.randomUUID());
        Mockito.doThrow(new IllegalStateException("rolled back"))
                .when(erasureService).finalizeErasure(anyLong(), anyLong(), any());

        worker.deleteQuiescedJobs();

        // rollback이면 mapping이 그대로 살아 있다 — 다음 날 재시도가 같은 해석을 쓴다.
        verifyNoInteractions(subjectMappingService);
    }

    private static AccountErasureJob job() {
        AccountErasureJob job = mock(AccountErasureJob.class);
        when(job.getUserId()).thenReturn(USER_ID);
        when(job.getAccountErasureJobId()).thenReturn(JOB_ID);
        return job;
    }

    private static AccountErasureWorkerProperties properties() {
        return new AccountErasureWorkerProperties(
                true, Duration.ofMinutes(20), Duration.ofMinutes(15), 7, 3, 1, 100, Duration.ofMinutes(10));
    }
}
