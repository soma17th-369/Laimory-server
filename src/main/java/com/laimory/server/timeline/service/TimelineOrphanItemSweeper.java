package com.laimory.server.timeline.service;

import com.laimory.server.common.ScheduledWorkerRunBudget;
import com.laimory.server.timeline.service.TimelineOrphanItemSweepService.SweepBatchResult;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * junction 0 Item을 수렴시키는 일일 스케줄 trigger.
 *
 * <p>PHOTO 삭제 worker(03:00) 뒤, draft cleanup(04:00) 앞에 돈다. 여기서 만든 delete job은 생성 당일
 * claim 대상이 아니므로 실제 S3 삭제는 다음 날 03:00 실행부터다.
 *
 * <p>process 간 분배는 batch transaction의 {@code FOR UPDATE SKIP LOCKED} claim이 담당하므로 별도 worker
 * executor를 두지 않고 스케줄 스레드에서 순차 실행한다(batch에 외부 I/O가 없다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineOrphanItemSweeper {

    private final TimelineOrphanItemSweepService sweepService;
    private final TimelineOrphanItemSweeperProperties properties;
    private final AtomicBoolean runActive = new AtomicBoolean();

    @Scheduled(
            cron = "${app.timeline.orphan-sweep.cron:0 30 3 * * *}",
            zone = "${app.timeline.orphan-sweep.zone:Asia/Seoul}")
    public void sweepOrphanItems() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        if (!runActive.compareAndSet(false, true)) {
            log.info("orphan 스위퍼 이전 run이 아직 실행 중이어서 trigger를 건너뜀");
            return;
        }
        try {
            runSweep();
        } finally {
            runActive.set(false);
        }
    }

    private void runSweep() {
        ScheduledWorkerRunBudget budget = new ScheduledWorkerRunBudget(
                properties.getMaxBatchesPerRun(), properties.getMaxRunDuration());
        RunSummary summary = new RunSummary();
        log.info("orphan 스위퍼 run 시작: batchSize={} maxBatches={} maxRunDurationMs={}",
                properties.getBatchSize(), properties.getMaxBatchesPerRun(),
                properties.getMaxRunDuration().toMillis());

        long cursor = 0L;
        while (budget.tryAcquireBatch()) {
            SweepBatchResult result;
            try {
                result = sweepService.sweepBatch(cursor, properties.getBatchSize());
            } catch (RuntimeException exception) {
                // batch가 실패하면 rollback돼 마지막으로 훑은 id를 알 수 없다. 커서를 임의로 밀면 그 사이
                // 구간을 조용히 건너뛰므로, 이번 run은 여기서 끝내고 다음 날 같은 커서에서 다시 시작한다.
                summary.recordBatchError();
                log.warn("orphan 스위퍼 batch 실패(run 중단, 다음 실행에서 재시도): cursor={} exceptionType={}",
                        cursor, exception.getClass().getSimpleName());
                break;
            }
            // 종료 조건은 오직 탐색이다. claim이 0이어도(다른 host 선점·재검증 탈락) 커서만 올려 계속한다.
            if (result.exhausted()) {
                break;
            }
            summary.record(result);
            logBatchCompleted(result);
            cursor = result.nextCursor();
        }
        summary.logCompleted();
    }

    private void logBatchCompleted(SweepBatchResult result) {
        log.info("orphan 스위퍼 batch 완료: scanned={} claimed={} skippedLocked={} revalidationDropped={} "
                        + "photoScheduled={} photoAlreadyJob={} keyShared={} invalidDeleted={} nonPhotoDeleted={} "
                        + "nextCursor={}",
                result.scanned(), result.claimed(), result.skippedLocked(), result.revalidationDropped(),
                result.photoScheduled(), result.photoAlreadyJob(), result.keyShared(), result.invalidDeleted(),
                result.nonPhotoDeleted(), result.nextCursor());
    }

    private static final class RunSummary {

        private final long startedAtNanos = System.nanoTime();
        private int batches;
        private int scanned;
        private int claimed;
        private int skippedLocked;
        private int revalidationDropped;
        private int photoScheduled;
        private int photoAlreadyJob;
        private int keyShared;
        private int invalidDeleted;
        private int nonPhotoDeleted;
        private int batchErrors;

        private void record(SweepBatchResult result) {
            batches++;
            scanned += result.scanned();
            claimed += result.claimed();
            skippedLocked += result.skippedLocked();
            revalidationDropped += result.revalidationDropped();
            photoScheduled += result.photoScheduled();
            photoAlreadyJob += result.photoAlreadyJob();
            keyShared += result.keyShared();
            invalidDeleted += result.invalidDeleted();
            nonPhotoDeleted += result.nonPhotoDeleted();
        }

        private void recordBatchError() {
            batchErrors++;
        }

        private void logCompleted() {
            log.info("orphan 스위퍼 run 완료: batches={} scanned={} claimed={} skippedLocked={} "
                            + "revalidationDropped={} photoScheduled={} photoAlreadyJob={} keyShared={} "
                            + "invalidDeleted={} nonPhotoDeleted={} batchErrors={} durationMs={}",
                    batches, scanned, claimed, skippedLocked, revalidationDropped, photoScheduled,
                    photoAlreadyJob, keyShared, invalidDeleted, nonPhotoDeleted, batchErrors,
                    Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000));
        }
    }
}
