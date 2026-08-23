package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimelineDraftCleanupWorkerPropertiesTest {

    @Test
    void acceptsInitialProductionValues() {
        TimelineDraftCleanupWorkerProperties properties = properties(
                true, 7, 250, 1, 4, Duration.ofSeconds(60));

        assertThat(properties.isWorkerEnabled()).isTrue();
        assertThat(properties.getRetentionDays()).isEqualTo(7);
        assertThat(properties.getBatchSize()).isEqualTo(250);
        assertThat(properties.getConcurrency()).isEqualTo(1);
    }

    @Test
    void rejectsUnsafeOrUnboundedValues() {
        assertThatIllegalStateException().isThrownBy(
                () -> properties(true, 0, 250, 1, 4, Duration.ofSeconds(60)))
                .withMessageContaining("retention-days");
        assertThatIllegalStateException().isThrownBy(
                () -> properties(true, 7, 1_001, 1, 4, Duration.ofSeconds(60)))
                .withMessageContaining("batch-size");
        assertThatIllegalStateException().isThrownBy(
                () -> properties(true, 7, 250, 3, 4, Duration.ofSeconds(60)))
                .withMessageContaining("concurrency");
        assertThatIllegalStateException().isThrownBy(
                () -> properties(true, 7, 250, 1, 0, Duration.ofSeconds(60)))
                .withMessageContaining("max-batches-per-run");
        assertThatIllegalStateException().isThrownBy(
                () -> properties(true, 7, 250, 1, 4, Duration.ZERO))
                .withMessageContaining("max-run-duration");
    }

    private TimelineDraftCleanupWorkerProperties properties(
            boolean enabled,
            long retentionDays,
            int batchSize,
            int concurrency,
            int maxBatches,
            Duration duration) {
        return new TimelineDraftCleanupWorkerProperties(
                enabled, retentionDays, batchSize, concurrency, maxBatches, duration);
    }
}
