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

    /**
     * 남은 실행 시간이 있는지 <b>소모하지 않고</b> 확인한다.
     *
     * <p>{@link #tryAcquireBatch()}는 batch 예산을 하나 쓰므로 "한 작업 안의 내부 반복"에는 쓸 수 없다 —
     * 내부 루프가 batch 예산을 갉아먹으면 정작 다음 작업을 못 가져온다. 긴 내부 정리를 가진 worker는
     * 이 메서드로 deadline만 보고 끊고, 남은 일은 다음 실행에 넘긴다.
     */
    public boolean hasTimeRemaining() {
        return System.nanoTime() < deadlineNanos;
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
