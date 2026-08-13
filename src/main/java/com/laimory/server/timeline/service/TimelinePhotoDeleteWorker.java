package com.laimory.server.timeline.service;

import com.laimory.server.common.logging.LogSanitizer;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import com.laimory.server.timeline.photo.S3PhotoStorageService.BatchDeleteResult;
import io.micrometer.core.instrument.Timer;
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
 * <p>짧은 claim transaction이 {@code SKIP LOCKED}로 작업을 분리하고 다음 날까지 eligibility를 미룬 뒤
 * commit한다. S3 호출은 DB transaction 밖이며 성공이 확인된 job과 원문 PHOTO Item만 짧은 별도
 * transaction으로 최종 삭제한다. 실패·응답 누락·SDK 예외는 두 행을 남긴다.
 */
@Slf4j
@Component
public class TimelinePhotoDeleteWorker {

    private static final int MAX_LOGGED_ERROR_CODE_LENGTH = 64;

    private final TimelinePhotoDeleteJobService jobService;
    private final TimelinePhotoDeleteCompletionService completionService;
    private final S3PhotoStorageService s3PhotoStorageService;
    private final TimelinePhotoDeleteWorkerProperties properties;
    private final TimelinePhotoDeleteMetrics metrics;
    private final TaskExecutor workerExecutor;
    private final AtomicBoolean runActive = new AtomicBoolean();

    public TimelinePhotoDeleteWorker(
            TimelinePhotoDeleteJobService jobService,
            TimelinePhotoDeleteCompletionService completionService,
            S3PhotoStorageService s3PhotoStorageService,
            TimelinePhotoDeleteWorkerProperties properties,
            TimelinePhotoDeleteMetrics metrics,
            @Qualifier("timelinePhotoDeleteWorkerExecutor") TaskExecutor workerExecutor) {
        this.jobService = jobService;
        this.completionService = completionService;
        this.s3PhotoStorageService = s3PhotoStorageService;
        this.properties = properties;
        this.metrics = metrics;
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

        ScheduledWorkerRunBudget budget = new ScheduledWorkerRunBudget(
                properties.getMaxBatchesPerRun(), properties.getMaxRunDuration());
        AtomicInteger remainingSlots = new AtomicInteger(properties.getConcurrency());
        for (int slot = 0; slot < properties.getConcurrency(); slot++) {
            try {
                workerExecutor.execute(() -> runWorkerSlot(budget, remainingSlots));
            } catch (RuntimeException exception) {
                log.warn("PHOTO 삭제 worker task 제출 실패: exceptionType={}",
                        exception.getClass().getSimpleName());
                workerSlotFinished(remainingSlots);
            }
        }
    }

    private void runWorkerSlot(ScheduledWorkerRunBudget budget, AtomicInteger remainingSlots) {
        try {
            while (budget.tryAcquireBatch()) {
                List<TimelinePhotoDeleteJob> jobs;
                try {
                    jobs = jobService.claimEligible(properties.getBatchSize());
                } catch (RuntimeException exception) {
                    log.warn("PHOTO 삭제 job claim 실패: exceptionType={}",
                            exception.getClass().getSimpleName());
                    return;
                }
                if (jobs.isEmpty()) {
                    return;
                }
                metrics.recordClaimed(jobs.size());
                processClaimedBatch(jobs);
            }
        } finally {
            workerSlotFinished(remainingSlots);
        }
    }

    private void workerSlotFinished(AtomicInteger remainingSlots) {
        if (remainingSlots.decrementAndGet() == 0) {
            runActive.set(false);
        }
    }

    private void processClaimedBatch(List<TimelinePhotoDeleteJob> jobs) {
        List<String> objectKeys = jobs.stream()
                .map(TimelinePhotoDeleteJob::getObjectKey)
                .toList();
        BatchDeleteResult result;
        Timer.Sample sample = metrics.startBatch();
        try {
            result = s3PhotoStorageService.deleteAll(objectKeys);
        } catch (RuntimeException exception) {
            metrics.recordAttemptFailed(jobs.size());
            metrics.recordDeferred(jobs.size());
            log.warn("PHOTO 삭제 batch 호출 실패(job 유지): requested={} exceptionType={}",
                    jobs.size(), exception.getClass().getSimpleName());
            return;
        } finally {
            metrics.recordBatch(sample);
        }

        Set<String> deletedObjectKeys = result.deletedObjectKeys();
        List<TimelinePhotoDeleteJob> succeededJobs = jobs.stream()
                .filter(job -> deletedObjectKeys.contains(job.getObjectKey()))
                .toList();
        int failed = jobs.size() - succeededJobs.size();
        metrics.recordAttemptSuccess(succeededJobs.size());
        metrics.recordAttemptFailed(failed);

        int completed = 0;
        try {
            if (!succeededJobs.isEmpty()) {
                completed = completionService.completeSucceeded(succeededJobs);
            }
        } catch (RuntimeException exception) {
            metrics.recordDeferred(jobs.size());
            log.warn("PHOTO 삭제 성공 Item/job 정리 실패(둘 다 유지): succeeded={} exceptionType={}",
                    succeededJobs.size(), exception.getClass().getSimpleName());
            return;
        }
        metrics.recordCompleted(completed);
        // completion이 0인 성공 job은 다른 at-least-once worker가 이미 완료한 정상 경쟁일 수 있다.
        // 다음 실행에 실제로 남겨 둔 것은 S3가 실패/누락으로 분류한 job뿐이다.
        metrics.recordDeferred(failed);

        Map<String, Long> errorCodeCounts = result.errorCodeByObjectKey().values().stream()
                .map(code -> LogSanitizer.sanitize(code, MAX_LOGGED_ERROR_CODE_LENGTH))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        log.info("PHOTO 삭제 batch 완료: requested={} succeeded={} failed={} unreported={} completed={} errorCodes={}",
                jobs.size(), succeededJobs.size(), failed, result.unreportedObjectKeys().size(), completed,
                errorCodeCounts);
    }
}
