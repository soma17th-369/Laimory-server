package com.laimory.server.timeline.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** 한 process의 여러 worker slot이 공유하는 batch 수와 실행 시간 상한. */
final class ScheduledWorkerRunBudget {

    private final AtomicInteger remainingBatches;
    private final long deadlineNanos;

    ScheduledWorkerRunBudget(int maxBatches, Duration maxDuration) {
        this.remainingBatches = new AtomicInteger(maxBatches);
        this.deadlineNanos = System.nanoTime() + maxDuration.toNanos();
    }

    boolean tryAcquireBatch() {
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
