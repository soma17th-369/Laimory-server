package com.laimory.server.user.service;

import com.laimory.server.common.ScheduledWorkerRunBudget;
import com.laimory.server.timeline.service.TimelineContentErasureService;
import com.laimory.server.user.AccountErasureJobStatus;
import com.laimory.server.user.entity.AccountErasureJob;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 탈퇴가 접수한 계정 삭제 작업을 처리하는 worker(#302).
 *
 * <p><b>두 pass</b>가 요구 주기가 달라 따로 돈다.
 * <ul>
 *   <li><b>정지</b>(짧은 cron) — 접수 후 {@code quiesce-delay}가 지나면 User Memory 미반영 큐를 비우고
 *       {@code QUIESCED}로 전이한다. 데이터는 지우지 않는다. 주기가 곧 정지 지연 상한이라 짧아야 한다.</li>
 *   <li><b>삭제</b>(일일 cron) — 유예가 지난 job을 실제로 지운다. 처리 창은 PHOTO 삭제 job(#365)과 같은
 *       규칙이며, 창을 벗어난 미완료 job은 재시도하지 않고 보존한 채 건수만 ERROR로 경보한다.</li>
 * </ul>
 *
 * <p>여러 인스턴스가 같은 cron을 돌린다(분산 스케줄러 락 없음 — 저장소 관례). 일을 나누는 것은
 * {@code SKIP LOCKED} claim이고 {@code runActive}는 process-local 중복 실행만 막는다.
 *
 * <p><b>경쟁에서 진 worker는 조용히 끝낸다.</b> 조건부 전이·삭제의 0행은 실패가 아니라 "다른 worker가
 * 이미 처리함"이다. 이걸 ERROR로 올리면 정상 완료된 job에 경보가 뜬다.
 *
 * <p>로그에 userId·subjectId·jobId를 남기지 않는다 — 건수와 error category만 남긴다.
 */
@Slf4j
@Component
public class AccountErasureWorker {

    private final AccountErasureJobService jobService;
    private final AccountErasureService erasureService;
    private final AccountErasureWorkerProperties properties;
    private final TaskExecutor quiesceExecutor;
    private final TaskExecutor deleteExecutor;
    private final Clock clock;
    private final AtomicBoolean quiesceRunActive = new AtomicBoolean();
    private final AtomicBoolean deleteRunActive = new AtomicBoolean();

    public AccountErasureWorker(
            AccountErasureJobService jobService,
            AccountErasureService erasureService,
            AccountErasureWorkerProperties properties,
            @Qualifier("accountErasureQuiesceWorkerExecutor") TaskExecutor quiesceExecutor,
            @Qualifier("accountErasureDeleteWorkerExecutor") TaskExecutor deleteExecutor,
            Clock clock) {
        this.jobService = jobService;
        this.erasureService = erasureService;
        this.properties = properties;
        this.quiesceExecutor = quiesceExecutor;
        this.deleteExecutor = deleteExecutor;
        this.clock = clock;
    }

    /**
     * 정지 pass — 유예를 기다리지 않는다. 이 trigger 주기가 곧 "탈퇴 후 몇 분 안에 AI 발급이 끊기는가"의
     * 상한이라 짧게 잡는다. 대상 행은 평소 0이고 claim query가 index를 타므로 빈 run은 거의 공짜다.
     */
    @Scheduled(
            cron = "${app.account-erasure.quiesce-cron:0 */15 * * * *}",
            zone = "${app.account-erasure.zone:Asia/Seoul}")
    public void quiescePendingJobs() {
        if (!properties.isWorkerEnabled() || !quiesceRunActive.compareAndSet(false, true)) {
            return;
        }
        runPass("정지", quiesceExecutor, quiesceRunActive, this::claimForQuiesce, this::quiesceOne);
    }

    /**
     * 삭제 pass — 유예가 지난 job을 실제로 지운다. run 시작 시 만료·{@code MANUAL_REVIEW} 건수를 먼저
     * 경보한다(#365와 같은 형태).
     */
    @Scheduled(
            cron = "${app.account-erasure.delete-cron:0 30 2 * * *}",
            zone = "${app.account-erasure.zone:Asia/Seoul}")
    public void deleteQuiescedJobs() {
        if (!properties.isWorkerEnabled() || !deleteRunActive.compareAndSet(false, true)) {
            return;
        }
        alertStalledJobs();
        runPass("삭제", deleteExecutor, deleteRunActive, this::claimForDelete, this::deleteOne);
    }

    private void runPass(String passName, TaskExecutor executor, AtomicBoolean runActive,
                         Claimer claimer, JobHandler handler) {
        ScheduledWorkerRunBudget budget =
                new ScheduledWorkerRunBudget(properties.getMaxBatchesPerRun(), properties.getMaxRunDuration());
        AtomicInteger remainingSlots = new AtomicInteger(properties.getConcurrency());
        RunSummary summary = new RunSummary(passName);
        for (int slot = 0; slot < properties.getConcurrency(); slot++) {
            try {
                executor.execute(() -> runSlot(budget, remainingSlots, summary, runActive, claimer, handler));
            } catch (RuntimeException exception) {
                summary.recordWorkerError();
                log.warn("계정 삭제 {} worker task 제출 실패: exceptionType={}",
                        passName, exception.getClass().getSimpleName());
                slotFinished(remainingSlots, summary, runActive);
            }
        }
    }

    private void runSlot(ScheduledWorkerRunBudget budget, AtomicInteger remainingSlots, RunSummary summary,
                         AtomicBoolean runActive, Claimer claimer, JobHandler handler) {
        try {
            while (budget.tryAcquireBatch()) {
                List<AccountErasureJob> jobs;
                try {
                    jobs = claimer.claim();
                } catch (RuntimeException exception) {
                    summary.recordClaimError();
                    log.warn("계정 삭제 {} job claim 실패: exceptionType={}",
                            summary.passName, exception.getClass().getSimpleName());
                    return;
                }
                if (jobs.isEmpty()) {
                    return;
                }
                for (AccountErasureJob job : jobs) {
                    if (!budget.hasTimeRemaining()) {
                        // claim은 했지만 시작도 못 한 행이다. claim이 updated_at을 오늘로 찍어 두므로
                        // 그날 다시 잡히지 않는다 — 그래서 claim 크기를 작게 두는 것이 기본값이다.
                        summary.recordDeferred();
                        continue;
                    }
                    handler.handle(job, summary, budget);
                }
            }
        } finally {
            slotFinished(remainingSlots, summary, runActive);
        }
    }

    private void slotFinished(AtomicInteger remainingSlots, RunSummary summary, AtomicBoolean runActive) {
        if (remainingSlots.decrementAndGet() == 0) {
            runActive.set(false);
            summary.logCompleted();
        }
    }

    private List<AccountErasureJob> claimForQuiesce() {
        LocalDateTime now = LocalDateTime.now(clock);
        return jobService.claimForQuiesce(
                now.minus(properties.getQuiesceDelay()), now.minus(properties.getStaleAfter()),
                now, properties.getBatchSize());
    }

    private List<AccountErasureJob> claimForDelete() {
        LocalDateTime todayStart = LocalDate.now(clock).atStartOfDay();
        return jobService.claimForDelete(
                windowStart(todayStart),
                todayStart.minusDays(properties.getGracePeriodDays()),
                todayStart,
                LocalDateTime.now(clock),
                properties.getBatchSize());
    }

    private LocalDateTime windowStart(LocalDateTime todayStart) {
        return todayStart.minusDays((long) properties.getGracePeriodDays() + properties.getWindowDays());
    }

    /** 정지 — 큐만 비우고 전이한다. 데이터는 지우지 않으므로 실패해도 되돌릴 것이 없다. */
    private void quiesceOne(AccountErasureJob job, RunSummary summary, ScheduledWorkerRunBudget budget) {
        UUID subjectId;
        try {
            subjectId = erasureService.resolveTarget(job.getUserId());
        } catch (RuntimeException exception) {
            recordUnresolvable(job, AccountErasureJobStatus.PENDING, summary, exception);
            return;
        }
        try {
            if (!erasureService.quiesce(subjectId, budget::hasTimeRemaining)) {
                // 큐를 다 비우지 못했으면 전이하지 않는다 — QUIESCED는 "새 AI 작업이 더 발급되지
                // 않는다"는 뜻이라, 절반만 비운 상태로 넘기면 그 보장이 거짓이 된다.
                summary.recordDeferred();
                return;
            }
            if (jobService.transition(job.getAccountErasureJobId(),
                    AccountErasureJobStatus.PENDING, AccountErasureJobStatus.QUIESCED)) {
                summary.recordProcessed();
            } else {
                summary.recordAlreadyHandled();
            }
        } catch (RuntimeException exception) {
            summary.recordDeferred();
            log.warn("계정 삭제 정지 실패(다음 실행에서 재시도): exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    /** 삭제 — owner 행을 지우고 finalization으로 마무리한다. 실패는 job을 남겨 다음 날 재시도한다. */
    private void deleteOne(AccountErasureJob job, RunSummary summary, ScheduledWorkerRunBudget budget) {
        UUID subjectId;
        try {
            subjectId = erasureService.resolveTarget(job.getUserId());
        } catch (RuntimeException exception) {
            recordUnresolvable(job, AccountErasureJobStatus.QUIESCED, summary, exception);
            return;
        }
        try {
            // 실행 예산이 끝나면 남은 일을 다음 실행에 넘긴다 — 데이터가 큰 계정 하나가 slot을
            // 실행 시간 상한 너머로 점유하지 않게 한다. 지운 것은 이미 commit돼 있고 모든 단계가
            // 멱등이라 다음 실행은 남은 것만 처리한다.
            if (!erasureService.deleteContentGraph(subjectId, budget::hasTimeRemaining)) {
                summary.recordDeferred();
                return;
            }
            erasureService.deleteOwnerRows(job.getUserId(), subjectId);
            if (!erasureService.deletePhotoObjects(subjectId, budget::hasTimeRemaining)) {
                summary.recordDeferred();
                return;
            }
            erasureService.finalizeErasure(job.getAccountErasureJobId(), job.getUserId(), subjectId);
            summary.recordProcessed();
        } catch (TimelineContentErasureService.CrossSubjectItemException exception) {
            // 다른 subject가 소유한 Item이 섞여 있다 — 손상 상태이므로 재시도로 풀리지 않는다.
            // 남의 데이터를 지우는 것보다 멈추는 편이 낫다.
            recordUnresolvable(job, AccountErasureJobStatus.QUIESCED, summary, exception);
        } catch (RuntimeException exception) {
            // 두 가지가 여기로 온다. ① 콘텐츠가 남아 있어 mapping 삭제가 subject FK RESTRICT에 막힌 경우
            // (PR1의 정상 경로) ② finalization 중 예상 밖 0행(AccountErasureConflictException — 다른
            // worker가 이미 완료). 둘 다 finalization transaction 전체가 rollback돼 반쪽 상태가 없고,
            // job이 그대로 남아 다음 날 재시도한다. 처리 창을 넘기면 만료 경보가 대신 알린다.
            summary.recordDeferred();
            log.warn("계정 삭제 실패(job 보존, 다음 실행에서 재시도): exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    /**
     * subject 해석·상태 확인 실패. 경쟁에서 진 worker가 정상 완료된 job을 두고 ERROR를 올리지 않도록
     * <b>전이가 실제로 행에 걸렸을 때만</b> 경보한다 — 0행이면 다른 worker가 이미 처리한 것이다.
     */
    private void recordUnresolvable(AccountErasureJob job, AccountErasureJobStatus expected,
                                    RunSummary summary, RuntimeException exception) {
        if (jobService.markManualReview(job.getAccountErasureJobId(), expected)) {
            summary.recordManualReview();
            log.error("계정 삭제 대상 확인 실패로 수동 확인 필요: exceptionType={}",
                    exception.getClass().getSimpleName());
        } else {
            summary.recordAlreadyHandled();
        }
    }

    /**
     * 처리 창을 벗어나 재시도에서 제외된 job과 수동 확인 대기 job의 건수를 ERROR로 남겨 기존
     * application ERROR 경보를 발화시킨다. 조회 실패는 이번 run의 처리를 막지 않는다.
     */
    private void alertStalledJobs() {
        try {
            long expired = jobService.countExpired(windowStart(LocalDate.now(clock).atStartOfDay()));
            if (expired > 0) {
                log.error("계정 삭제 처리 창 만료: expiredCount={} — 데이터와 job은 보존됨(재시도 없음)", expired);
            }
            long manualReview = jobService.countManualReview();
            if (manualReview > 0) {
                log.error("계정 삭제 수동 확인 대기: manualReviewCount={}", manualReview);
            }
        } catch (RuntimeException exception) {
            log.warn("계정 삭제 적체 count 조회 실패(처리는 계속): exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface Claimer {
        List<AccountErasureJob> claim();
    }

    @FunctionalInterface
    private interface JobHandler {
        void handle(AccountErasureJob job, RunSummary summary, ScheduledWorkerRunBudget budget);
    }

    private static final class RunSummary {

        private final String passName;
        private final long startedAtNanos = System.nanoTime();
        private int processed;
        private int alreadyHandled;
        private int deferred;
        private int manualReview;
        private int claimErrors;
        private int workerErrors;

        private RunSummary(String passName) {
            this.passName = passName;
        }

        private synchronized void recordProcessed() {
            processed++;
        }

        private synchronized void recordAlreadyHandled() {
            alreadyHandled++;
        }

        private synchronized void recordDeferred() {
            deferred++;
        }

        private synchronized void recordManualReview() {
            manualReview++;
        }

        private synchronized void recordClaimError() {
            claimErrors++;
        }

        private synchronized void recordWorkerError() {
            workerErrors++;
        }

        private synchronized void logCompleted() {
            if (processed == 0 && alreadyHandled == 0 && deferred == 0 && manualReview == 0
                    && claimErrors == 0 && workerErrors == 0) {
                return; // 빈 run은 로그를 남기지 않는다 — 정지 pass가 짧은 주기로 돈다.
            }
            log.info("계정 삭제 {} worker run 완료: processed={} alreadyHandled={} deferred={} "
                            + "manualReview={} claimErrors={} workerErrors={} durationMs={}",
                    passName, processed, alreadyHandled, deferred, manualReview, claimErrors, workerErrors,
                    Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000));
        }
    }
}
