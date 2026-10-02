package com.laimory.server.push.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 일일 리마인더 worker의 runtime 설정과 기동 시 불변식 검증.
 *
 * <p>기본은 ON이다. 리마인더가 사용자별 기본 ON이 된 뒤로(#318) worker를 켜는 것은 곧 전체 사용자
 * 발송을 뜻하므로, env는 문제 시 발송을 멈추는 kill switch다.
 */
@Component
public class DailyReminderWorkerProperties {

    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_CONCURRENCY = 2;
    private static final int MAX_BATCHES_PER_RUN = 1_000;

    private final boolean workerEnabled;
    private final int batchSize;
    private final int concurrency;
    private final int maxBatchesPerRun;

    public DailyReminderWorkerProperties(
            @Value("${app.push.daily-reminder.worker-enabled:true}") boolean workerEnabled,
            @Value("${app.push.daily-reminder.batch-size:250}") int batchSize,
            @Value("${app.push.daily-reminder.concurrency:1}") int concurrency,
            @Value("${app.push.daily-reminder.max-batches-per-run:40}") int maxBatchesPerRun) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "app.push.daily-reminder.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalStateException(
                    "app.push.daily-reminder.concurrency must be between 1 and " + MAX_CONCURRENCY);
        }
        if (maxBatchesPerRun < 1 || maxBatchesPerRun > MAX_BATCHES_PER_RUN) {
            throw new IllegalStateException("app.push.daily-reminder.max-batches-per-run must be between 1 and "
                    + MAX_BATCHES_PER_RUN);
        }
        this.workerEnabled = workerEnabled;
        this.batchSize = batchSize;
        this.concurrency = concurrency;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
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
}
