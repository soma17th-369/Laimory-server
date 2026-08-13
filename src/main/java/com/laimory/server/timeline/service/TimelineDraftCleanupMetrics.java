package com.laimory.server.timeline.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** draft cleanup의 claim/defer/DB 완료 처리량 메트릭. */
@Component
public class TimelineDraftCleanupMetrics {

    static final String CLEANUP_ROW = "laimory.timeline.draft.cleanup.row";

    private final Counter claimed;
    private final Counter deferred;
    private final Counter completed;

    public TimelineDraftCleanupMetrics(MeterRegistry meterRegistry) {
        this.claimed = counter(meterRegistry, "claimed");
        this.deferred = counter(meterRegistry, "deferred");
        this.completed = counter(meterRegistry, "completed");
    }

    private static Counter counter(MeterRegistry registry, String state) {
        return Counter.builder(CLEANUP_ROW)
                .description("Draft cleanup row lifecycle transitions")
                .tag("state", state)
                .register(registry);
    }

    public void recordClaimed(int count) {
        increment(claimed, count);
    }

    public void recordDeferred(int count) {
        increment(deferred, count);
    }

    public void recordCompleted(int count) {
        increment(completed, count);
    }

    private static void increment(Counter counter, int count) {
        if (count > 0) {
            counter.increment(count);
        }
    }
}
