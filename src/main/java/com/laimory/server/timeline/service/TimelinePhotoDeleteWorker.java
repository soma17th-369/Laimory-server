package com.laimory.server.timeline.service;

import com.laimory.server.common.ScheduledWorkerRunBudget;
import com.laimory.server.common.logging.LogSanitizer;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import com.laimory.server.timeline.photo.S3PhotoStorageService.BatchDeleteResult;
import com.laimory.server.timeline.service.TimelinePhotoDeleteJobService.ValidationResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MySQL PHOTO delete-job을 여러 process/thread에서 batch claim해 처리하는 worker.
 *
 * <p>짧은 claim transaction이 {@code SKIP LOCKED}로 KST 생성일 기준 D+1~D+3 처리 창의 작업을 분리하고
 * {@code updated_at}을 갱신해 같은 날 재선택을 막은 뒤 commit한다. S3 호출은 DB transaction 밖이며
 * 성공이 확인된 job과 원문 PHOTO Item만 짧은 별도 transaction으로 최종 삭제한다. 실패·응답 누락·SDK
 * 예외는 두 행을 남긴다. 처리 창을 벗어난 미완료 job은 재시도하지 않고 건수만 ERROR로 경보한다.
 */
@Slf4j
@Component
public class TimelinePhotoDeleteWorker {

    private static final int MAX_LOGGED_ERROR_CODE_LENGTH = 64;

    private final TimelinePhotoDeleteJobService jobService;
    private final S3PhotoStorageService s3PhotoStorageService;
    private final TimelinePhotoDeleteWorkerProperties properties;
    private final TaskExecutor workerExecutor;
    private final AtomicBoolean runActive = new AtomicBoolean();

    public TimelinePhotoDeleteWorker(
            TimelinePhotoDeleteJobService jobService,
            S3PhotoStorageService s3PhotoStorageService,
            TimelinePhotoDeleteWorkerProperties properties,
            @Qualifier("timelinePhotoDeleteWorkerExecutor") TaskExecutor workerExecutor) {
        this.jobService = jobService;
        this.s3PhotoStorageService = s3PhotoStorageService;
        this.properties = properties;
        this.workerExecutor = workerExecutor;
    }

    @Scheduled(
            cron = "${app.timeline.photo-delete.cron:0 0 3 * * *}",
            zone = "${app.timeline.photo-delete.zone:Asia/Seoul}")
    public void deletePendingPhotoObjects() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        if (!runActive.compareAndSet(false, true)) {
            log.info("PHOTO 삭제 worker 이전 run이 아직 실행 중이어서 trigger를 건너뜀");
            return;
        }
        alertExpiredJobs();

        ScheduledWorkerRunBudget budget = new ScheduledWorkerRunBudget(
                properties.getMaxBatchesPerRun(), properties.getMaxRunDuration());
        AtomicInteger remainingSlots = new AtomicInteger(properties.getConcurrency());
        RunSummary summary = new RunSummary();
        log.info("PHOTO 삭제 worker run 시작: batchSize={} concurrency={} maxBatches={} maxRunDurationMs={}",
                properties.getBatchSize(), properties.getConcurrency(), properties.getMaxBatchesPerRun(),
                properties.getMaxRunDuration().toMillis());
        for (int slot = 0; slot < properties.getConcurrency(); slot++) {
            try {
                workerExecutor.execute(() -> runWorkerSlot(budget, remainingSlots, summary));
            } catch (RuntimeException exception) {
                summary.recordWorkerError();
                log.warn("PHOTO 삭제 worker task 제출 실패: exceptionType={}",
                        exception.getClass().getSimpleName());
                workerSlotFinished(remainingSlots, summary);
            }
        }
    }

    private void runWorkerSlot(
            ScheduledWorkerRunBudget budget,
            AtomicInteger remainingSlots,
            RunSummary summary) {
        try {
            while (budget.tryAcquireBatch()) {
                List<TimelinePhotoDeleteJob> jobs;
                try {
                    jobs = jobService.claimEligible(properties.getBatchSize());
                } catch (RuntimeException exception) {
                    summary.recordClaimError();
                    log.warn("PHOTO 삭제 job claim 실패: exceptionType={}",
                            exception.getClass().getSimpleName());
                    return;
                }
                if (jobs.isEmpty()) {
                    return;
                }
                summary.record(processClaimedBatch(jobs));
            }
        } finally {
            workerSlotFinished(remainingSlots, summary);
        }
    }

    private void workerSlotFinished(AtomicInteger remainingSlots, RunSummary summary) {
        if (remainingSlots.decrementAndGet() == 0) {
            runActive.set(false);
            summary.logCompleted();
        }
    }

    private BatchResult processClaimedBatch(List<TimelinePhotoDeleteJob> jobs) {
        long startedAtNanos = System.nanoTime();
        ValidationResult validation;
        try {
            validation = jobService.retainOrphanJobs(jobs);
        } catch (RuntimeException exception) {
            markPendingForRetry(jobs);
            BatchResult result = BatchResult.validationFailed(jobs.size(), elapsedMillis(startedAtNanos));
            log.warn("PHOTO 삭제 batch orphan 재검증 실패(job 유지): claimed={} deferred={} "
                            + "durationMs={} exceptionType={}",
                    result.claimed(), result.deferred(), result.durationMs(),
                    exception.getClass().getSimpleName());
            return result;
        }
        List<TimelinePhotoDeleteJob> orphanJobs = validation.orphanJobs();
        if (validation.cancelledJobs() > 0) {
            log.info("PHOTO 삭제 job 재연결 취소: claimed={} cancelled={}",
                    jobs.size(), validation.cancelledJobs());
        }
        if (orphanJobs.isEmpty()) {
            BatchResult result = BatchResult.completedWithoutS3(
                    jobs.size(), validation.cancelledJobs(), elapsedMillis(startedAtNanos));
            logBatchCompleted(result, Map.of());
            return result;
        }

        List<String> objectKeys = orphanJobs.stream()
                .map(TimelinePhotoDeleteJob::getObjectKey)
                .toList();
        BatchDeleteResult result;
        try {
            result = s3PhotoStorageService.deleteAll(objectKeys);
        } catch (RuntimeException exception) {
            markPendingForRetry(orphanJobs);
            BatchResult batchResult = BatchResult.s3Failed(
                    jobs.size(), validation.cancelledJobs(), orphanJobs.size(),
                    elapsedMillis(startedAtNanos));
            log.warn("PHOTO 삭제 batch S3 호출 실패(job 유지): claimed={} relinkedCancelled={} "
                            + "requested={} s3Failed={} deferred={} durationMs={} exceptionType={}",
                    batchResult.claimed(), batchResult.relinkedCancelled(), batchResult.requested(),
                    batchResult.s3Failed(), batchResult.deferred(), batchResult.durationMs(),
                    exception.getClass().getSimpleName());
            return batchResult;
        }

        Set<String> deletedObjectKeys = result.deletedObjectKeys();
        List<TimelinePhotoDeleteJob> succeededJobs = orphanJobs.stream()
                .filter(job -> deletedObjectKeys.contains(job.getObjectKey()))
                .toList();
        List<TimelinePhotoDeleteJob> retryJobs = orphanJobs.stream()
                .filter(job -> !deletedObjectKeys.contains(job.getObjectKey()))
                .toList();
        int failed = orphanJobs.size() - succeededJobs.size();

        int completed = 0;
        try {
            if (!succeededJobs.isEmpty()) {
                completed = jobService.completeSucceeded(succeededJobs);
            }
        } catch (RuntimeException exception) {
            markPendingForRetry(orphanJobs);
            BatchResult batchResult = BatchResult.completionFailed(
                    jobs.size(), validation.cancelledJobs(), orphanJobs.size(),
                    succeededJobs.size(), failed, result.unreportedObjectKeys().size(),
                    elapsedMillis(startedAtNanos));
            log.warn("PHOTO 삭제 batch DB 완료 실패(Item/job 유지): claimed={} relinkedCancelled={} "
                            + "requested={} s3Succeeded={} s3Failed={} unreported={} deferred={} "
                            + "durationMs={} exceptionType={}",
                    batchResult.claimed(), batchResult.relinkedCancelled(), batchResult.requested(),
                    batchResult.s3Succeeded(), batchResult.s3Failed(), batchResult.unreported(),
                    batchResult.deferred(), batchResult.durationMs(), exception.getClass().getSimpleName());
            return batchResult;
        }
        markPendingForRetry(retryJobs);
        // completion이 0인 성공 job은 다른 at-least-once worker가 이미 완료한 정상 경쟁일 수 있다.
        // 다음 실행에 실제로 남겨 둔 것은 S3가 실패/누락으로 분류한 job뿐이다.

        Map<String, Long> errorCodeCounts = result.errorCodeByObjectKey().values().stream()
                .map(code -> LogSanitizer.sanitize(code, MAX_LOGGED_ERROR_CODE_LENGTH))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        BatchResult batchResult = BatchResult.completed(
                jobs.size(), validation.cancelledJobs(), orphanJobs.size(), succeededJobs.size(), failed,
                result.unreportedObjectKeys().size(), completed, elapsedMillis(startedAtNanos));
        logBatchCompleted(batchResult, errorCodeCounts);
        return batchResult;
    }

    /**
     * 처리 창을 벗어나 재시도에서 제외된 미완료 job이 있으면 건수만 ERROR로 남겨 기존 application
     * ERROR 경보를 발화시킨다. job ID·Item ID·object key는 로그에 싣지 않고, count 조회 실패는 이번
     * run의 claim 처리를 막지 않는다.
     */
    private void alertExpiredJobs() {
        long expiredCount;
        try {
            expiredCount = jobService.countExpired();
        } catch (RuntimeException exception) {
            log.warn("PHOTO 삭제 만료 job count 조회 실패(claim 처리는 계속): exceptionType={}",
                    exception.getClass().getSimpleName());
            return;
        }
        if (expiredCount > 0) {
            log.error("PHOTO 삭제 job 처리 창(D+1~D+3) 만료: expiredCount={} — job과 PHOTO Item은 보존됨",
                    expiredCount);
        }
    }

    /** retry 상태 전환 실패 시에도 job은 PROCESSING으로 남고, 다음 일일 claim이 stale로 재선점해 복구한다. */
    private void markPendingForRetry(List<TimelinePhotoDeleteJob> jobs) {
        if (jobs.isEmpty()) {
            return;
        }
        try {
            jobService.markPendingForRetry(jobs);
        } catch (RuntimeException exception) {
            log.warn("PHOTO 삭제 job PENDING 전환 실패(다음 일일 실행에서 stale 재claim): count={} exceptionType={}",
                    jobs.size(), exception.getClass().getSimpleName());
        }
    }

    private void logBatchCompleted(BatchResult result, Map<String, Long> errorCodeCounts) {
        log.info("PHOTO 삭제 batch 완료: claimed={} relinkedCancelled={} requested={} s3Succeeded={} "
                        + "s3Failed={} unreported={} dbCompleted={} deferred={} durationMs={} errorCodes={}",
                result.claimed(), result.relinkedCancelled(), result.requested(), result.s3Succeeded(),
                result.s3Failed(), result.unreported(), result.dbCompleted(), result.deferred(),
                result.durationMs(), errorCodeCounts);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    private record BatchResult(
            int claimed,
            int relinkedCancelled,
            int requested,
            int s3Succeeded,
            int s3Failed,
            int unreported,
            int dbCompleted,
            int deferred,
            int validationErrors,
            int s3Errors,
            int databaseErrors,
            long durationMs) {

        private static BatchResult validationFailed(int claimed, long durationMs) {
            return new BatchResult(claimed, 0, 0, 0, 0, 0, 0, claimed, 1, 0, 0, durationMs);
        }

        private static BatchResult completedWithoutS3(int claimed, int cancelled, long durationMs) {
            return new BatchResult(claimed, cancelled, 0, 0, 0, 0, 0, 0, 0, 0, 0, durationMs);
        }

        private static BatchResult s3Failed(int claimed, int cancelled, int requested, long durationMs) {
            return new BatchResult(
                    claimed, cancelled, requested, 0, requested, 0, 0, requested, 0, 1, 0, durationMs);
        }

        private static BatchResult completionFailed(
                int claimed,
                int cancelled,
                int requested,
                int s3Succeeded,
                int s3Failed,
                int unreported,
                long durationMs) {
            return new BatchResult(
                    claimed, cancelled, requested, s3Succeeded, s3Failed, unreported,
                    0, requested, 0, 0, 1, durationMs);
        }

        private static BatchResult completed(
                int claimed,
                int cancelled,
                int requested,
                int s3Succeeded,
                int s3Failed,
                int unreported,
                int dbCompleted,
                long durationMs) {
            return new BatchResult(
                    claimed, cancelled, requested, s3Succeeded, s3Failed, unreported,
                    dbCompleted, s3Failed, 0, 0, 0, durationMs);
        }
    }

    private static final class RunSummary {

        private final long startedAtNanos = System.nanoTime();
        private int batches;
        private int claimed;
        private int relinkedCancelled;
        private int requested;
        private int s3Succeeded;
        private int s3Failed;
        private int unreported;
        private int dbCompleted;
        private int deferred;
        private int validationErrors;
        private int claimErrors;
        private int s3Errors;
        private int databaseErrors;
        private int workerErrors;

        private synchronized void record(BatchResult result) {
            batches++;
            claimed += result.claimed();
            relinkedCancelled += result.relinkedCancelled();
            requested += result.requested();
            s3Succeeded += result.s3Succeeded();
            s3Failed += result.s3Failed();
            unreported += result.unreported();
            dbCompleted += result.dbCompleted();
            deferred += result.deferred();
            validationErrors += result.validationErrors();
            s3Errors += result.s3Errors();
            databaseErrors += result.databaseErrors();
        }

        private synchronized void recordWorkerError() {
            workerErrors++;
        }

        private synchronized void recordClaimError() {
            claimErrors++;
        }

        private synchronized void logCompleted() {
            log.info("PHOTO 삭제 worker run 완료: batches={} claimed={} relinkedCancelled={} requested={} "
                            + "s3Succeeded={} s3Failed={} unreported={} dbCompleted={} deferred={} "
                            + "claimErrors={} validationErrors={} s3Errors={} databaseErrors={} "
                            + "workerErrors={} durationMs={}",
                    batches, claimed, relinkedCancelled, requested, s3Succeeded, s3Failed, unreported,
                    dbCompleted, deferred, claimErrors, validationErrors, s3Errors, databaseErrors, workerErrors,
                    elapsedMillis(startedAtNanos));
        }
    }
}
