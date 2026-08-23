package com.laimory.server.common;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 한 process의 여러 worker slot이 공유하는 batch 수와 실행 시간 상한.
 * 여러 feature의 bounded worker(타임라인 정리·PHOTO 삭제·일일 리마인더)가 같은 규칙을 쓴다.
 */
public final class ScheduledWorkerRunBudget {

    private final AtomicInteger remainingBatches;
    private final long deadlineNanos;

    public ScheduledWorkerRunBudget(int maxBatches, Duration maxDuration) {
        this.remainingBatches = new AtomicInteger(maxBatches);
        this.deadlineNanos = System.nanoTime() + maxDuration.toNanos();
    }

    public boolean tryAcquireBatch() {
        if (System.nanoTime() >= deadlineNanos) {
            return false;
        }
        int remaining = remainingBatches.get();
        while (remaining > 0) {
            if (remainingBatches.compareAndSet(remaining, remaining - 1)) {
                return true;
            }
            remaining = remainingBatches.get();
        }
        return false;
    }
}
