package com.laimory.server.timeline.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** draft retention cleanup worker의 bounded runtime 설정과 기동 시 불변식 검증. */
@Component
public class TimelineDraftCleanupWorkerProperties {

    /**
     * PROCESSING draft task의 TTL은 3분이므로 retention은 그보다 충분히 길어야 한다. 최소 1일을 강제해
     * in-flight source가 cleanup cutoff에 걸리지 않게 한다. AI가 채택한 source는 final 저장 transaction에서
     * 이미 삭제되므로, 이 조건 아래 cleanup 대상은 omitted/failed task의 잔여 staging row뿐이다.
     */
    private static final long MIN_RETENTION_DAYS = 1;
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_CONCURRENCY = 2;
    private static final int MAX_BATCHES_PER_RUN = 1_000;
    private static final Duration MAX_RUN_DURATION = Duration.ofMinutes(10);

    private final boolean workerEnabled;
    private final long retentionDays;
    private final int batchSize;
    private final int concurrency;
    private final int maxBatchesPerRun;
    private final Duration maxRunDuration;

    public TimelineDraftCleanupWorkerProperties(
            @Value("${app.draft.worker-enabled:true}") boolean workerEnabled,
            @Value("${app.draft.retention-days:7}") long retentionDays,
            @Value("${app.draft.batch-size:250}") int batchSize,
            @Value("${app.draft.concurrency:1}") int concurrency,
            @Value("${app.draft.max-batches-per-run:4}") int maxBatchesPerRun,
            @Value("${app.draft.max-run-duration:60s}") Duration maxRunDuration) {
        if (retentionDays < MIN_RETENTION_DAYS) {
            throw new IllegalStateException("app.draft.retention-days must be at least " + MIN_RETENTION_DAYS);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException("app.draft.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalStateException("app.draft.concurrency must be between 1 and " + MAX_CONCURRENCY);
        }
        if (maxBatchesPerRun < 1 || maxBatchesPerRun > MAX_BATCHES_PER_RUN) {
            throw new IllegalStateException("app.draft.max-batches-per-run must be between 1 and "
                    + MAX_BATCHES_PER_RUN);
        }
        if (maxRunDuration.isZero() || maxRunDuration.isNegative()
                || maxRunDuration.compareTo(MAX_RUN_DURATION) > 0) {
            throw new IllegalStateException("app.draft.max-run-duration must be positive and at most "
                    + MAX_RUN_DURATION);
        }
        this.workerEnabled = workerEnabled;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.concurrency = concurrency;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.maxRunDuration = maxRunDuration;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public long getRetentionDays() {
        return retentionDays;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public Duration getMaxRunDuration() {
        return maxRunDuration;
    }
}
