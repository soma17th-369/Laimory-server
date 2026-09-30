package com.laimory.server.user.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.user.AccountErasureJobStatus;
import com.laimory.server.user.entity.AccountErasureJob;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * 삭제 pass의 처리 창과 finalization 뒤 캐시 정리를 고정한다.
 *
 * <ul>
 *   <li>처리 창은 접수일 D 기준 D+3~D+5다(#397) — 공개 약관 "탈퇴 접수일로부터 5일 이내 파기".</li>
 *   <li>대상 해석({@code resolveTarget})이 subject 캐시에 이 회원을 적재하므로, finalization 뒤 적재한
 *       host 자신이 지워 "erasure 이후 캐시가 비어 있다"를 복원한다(#429).</li>
 * </ul>
 */
class AccountErasureWorkerTest {

    private static final long USER_ID = 4_242L;
    private static final long JOB_ID = 9L;
    /** 2026-09-04 09:00 KST. */
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

    private AccountErasureJobService jobService;
    private AccountErasureService erasureService;
    private SubjectMappingService subjectMappingService;
    private AccountErasureWorker worker;

    @BeforeEach
    void setUp() {
        jobService = mock(AccountErasureJobService.class);
        erasureService = mock(AccountErasureService.class);
        subjectMappingService = mock(SubjectMappingService.class);
        worker = new AccountErasureWorker(jobService, erasureService, subjectMappingService,
                new AccountErasureWorkerProperties(true, 1, 100), new SyncTaskExecutor(),
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    @Test
    void deletePass_claimsOnlyJobsReceivedOnDPlus3ThroughDPlus5() {
        when(jobService.claimForDelete(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        worker.deletePendingJobs();

        // 오늘 T=09-04: 접수일 08-30(D+5)부터 09-01(D+3)까지 — [T-5 00:00, T-2 00:00)
        verify(jobService).claimForDelete(
                eq(LocalDateTime.parse("2026-08-30T00:00")),
                eq(LocalDateTime.parse("2026-09-02T00:00")),
                eq(LocalDateTime.parse("2026-09-04T00:00")),
                any(), anyInt());
        // 08-29(D+6) 이전 접수는 만료로 집계된다.
        verify(jobService).countExpired(LocalDateTime.parse("2026-08-30T00:00"));
    }

    @Test
    void deletePass_finalizesWithClaimedStatus() {
        AccountErasureJob job = job(AccountErasureJobStatus.QUIESCED);
        UUID subjectId = UUID.randomUUID();
        when(jobService.claimForDelete(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(job))
                .thenReturn(List.of());
        when(erasureService.resolveTarget(USER_ID)).thenReturn(subjectId);

        worker.deletePendingJobs();

        // 옛 정지 pass가 남긴 QUIESCED 행은 그 상태 그대로 조건부 삭제해야 0행이 되지 않는다.
        verify(erasureService).finalizeErasure(JOB_ID, AccountErasureJobStatus.QUIESCED, USER_ID, subjectId);
    }

    @Test
    void deletePass_evictsCachedMappingAfterFinalization() {
        AccountErasureJob job = job(AccountErasureJobStatus.PENDING);
        when(jobService.claimForDelete(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(job))
                .thenReturn(List.of());
        when(erasureService.resolveTarget(USER_ID)).thenReturn(UUID.randomUUID());

        worker.deletePendingJobs();

        // finalization commit(정상 반환) 뒤에 evict — 그 전에 지우면 이 호출이 다시 적재한다.
        InOrder inOrder = inOrder(erasureService, subjectMappingService);
        inOrder.verify(erasureService).finalizeErasure(anyLong(), any(), anyLong(), any());
        inOrder.verify(subjectMappingService).evictCachedMapping(USER_ID);
    }

    @Test
    void deletePass_failedFinalization_leavesCacheAlone() {
        AccountErasureJob job = job(AccountErasureJobStatus.PENDING);
        when(jobService.claimForDelete(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(job))
                .thenReturn(List.of());
        when(erasureService.resolveTarget(USER_ID)).thenReturn(UUID.randomUUID());
        Mockito.doThrow(new IllegalStateException("rolled back"))
                .when(erasureService).finalizeErasure(anyLong(), any(), anyLong(), any());

        worker.deletePendingJobs();

        // rollback이면 mapping이 그대로 살아 있다 — 다음 날 재시도가 같은 해석을 쓴다.
        verifyNoInteractions(subjectMappingService);
    }

    private static AccountErasureJob job(AccountErasureJobStatus status) {
        AccountErasureJob job = mock(AccountErasureJob.class);
        when(job.getUserId()).thenReturn(USER_ID);
        when(job.getAccountErasureJobId()).thenReturn(JOB_ID);
        when(job.getStatus()).thenReturn(status);
        return job;
    }
}
