package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class TimelineDraftCleanupMetricsTest {

    @Test
    void recordsClaimedDeferredAndCompletedRows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimelineDraftCleanupMetrics metrics = new TimelineDraftCleanupMetrics(registry);

        metrics.recordClaimed(3);
        metrics.recordDeferred(2);
        metrics.recordCompleted(1);

        assertThat(registry.get(TimelineDraftCleanupMetrics.CLEANUP_ROW)
                        .tag("state", "claimed").counter().count())
                .isEqualTo(3);
        assertThat(registry.get(TimelineDraftCleanupMetrics.CLEANUP_ROW)
                        .tag("state", "deferred").counter().count())
                .isEqualTo(2);
        assertThat(registry.get(TimelineDraftCleanupMetrics.CLEANUP_ROW)
                        .tag("state", "completed").counter().count())
                .isEqualTo(1);
    }
}
