package com.laimory.server.timeline.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** orphan Item 스위퍼의 runtime 설정과 기동 시 불변식 검증. */
@Component
public class TimelineOrphanItemSweeperProperties {

    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_BATCHES_PER_RUN = 1_000;
    private static final Duration MAX_RUN_DURATION = Duration.ofMinutes(10);

    private final boolean workerEnabled;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final Duration maxRunDuration;

    public TimelineOrphanItemSweeperProperties(
            @Value("${app.timeline.orphan-sweep.worker-enabled:true}") boolean workerEnabled,
            @Value("${app.timeline.orphan-sweep.batch-size:250}") int batchSize,
            @Value("${app.timeline.orphan-sweep.max-batches-per-run:4}") int maxBatchesPerRun,
            @Value("${app.timeline.orphan-sweep.max-run-duration:60s}") Duration maxRunDuration) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "app.timeline.orphan-sweep.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (maxBatchesPerRun < 1 || maxBatchesPerRun > MAX_BATCHES_PER_RUN) {
            throw new IllegalStateException("app.timeline.orphan-sweep.max-batches-per-run must be between 1 and "
                    + MAX_BATCHES_PER_RUN);
        }
        if (maxRunDuration.isZero() || maxRunDuration.isNegative()
                || maxRunDuration.compareTo(MAX_RUN_DURATION) > 0) {
            throw new IllegalStateException("app.timeline.orphan-sweep.max-run-duration must be positive and at most "
                    + MAX_RUN_DURATION);
        }
        this.workerEnabled = workerEnabled;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.maxRunDuration = maxRunDuration;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public Duration getMaxRunDuration() {
        return maxRunDuration;
    }
}
