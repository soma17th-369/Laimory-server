package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimelinePhotoDeleteWorkerPropertiesTest {

    @Test
    void acceptsPositiveDelayAndInclusiveBatchBounds() {
        TimelinePhotoDeleteWorkerProperties minimum =
                new TimelinePhotoDeleteWorkerProperties(true, Duration.ofMillis(1), 1);
        TimelinePhotoDeleteWorkerProperties maximum =
                new TimelinePhotoDeleteWorkerProperties(false, Duration.ofDays(1), 1_000);

        assertThat(minimum.isWorkerEnabled()).isTrue();
        assertThat(minimum.getFixedDelay()).isEqualTo(Duration.ofMillis(1));
        assertThat(minimum.getBatchSize()).isEqualTo(1);
        assertThat(maximum.isWorkerEnabled()).isFalse();
        assertThat(maximum.getFixedDelay()).isEqualTo(Duration.ofDays(1));
        assertThat(maximum.getBatchSize()).isEqualTo(1_000);
    }

    @Test
    void rejectsNullZeroAndNegativeDelay() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new TimelinePhotoDeleteWorkerProperties(false, null, 1))
                .withMessageContaining("fixed-delay");
        assertThatIllegalStateException()
                .isThrownBy(() -> new TimelinePhotoDeleteWorkerProperties(false, Duration.ZERO, 1))
                .withMessageContaining("fixed-delay");
        assertThatIllegalStateException()
                .isThrownBy(() -> new TimelinePhotoDeleteWorkerProperties(
                        false, Duration.ofNanos(-1), 1))
                .withMessageContaining("fixed-delay");
    }

    @Test
    void rejectsBatchSizeOutsideInclusiveBounds() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new TimelinePhotoDeleteWorkerProperties(
                        false, Duration.ofMinutes(1), 0))
                .withMessageContaining("batch-size");
        assertThatIllegalStateException()
                .isThrownBy(() -> new TimelinePhotoDeleteWorkerProperties(
                        false, Duration.ofMinutes(1), 1_001))
                .withMessageContaining("batch-size");
    }
}
