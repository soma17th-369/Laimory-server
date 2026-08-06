package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class TimelinePhotoDeleteWorkerPropertiesTest {

    @Test
    void acceptsInclusiveBatchBounds() {
        TimelinePhotoDeleteWorkerProperties minimum =
                new TimelinePhotoDeleteWorkerProperties(true, 1);
        TimelinePhotoDeleteWorkerProperties maximum =
                new TimelinePhotoDeleteWorkerProperties(false, 1_000);

        assertThat(minimum.isWorkerEnabled()).isTrue();
        assertThat(minimum.getBatchSize()).isEqualTo(1);
        assertThat(maximum.isWorkerEnabled()).isFalse();
        assertThat(maximum.getBatchSize()).isEqualTo(1_000);
    }

    @Test
    void rejectsBatchSizeOutsideInclusiveBounds() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new TimelinePhotoDeleteWorkerProperties(false, 0))
                .withMessageContaining("batch-size");
        assertThatIllegalStateException()
                .isThrownBy(() -> new TimelinePhotoDeleteWorkerProperties(false, 1_001))
                .withMessageContaining("batch-size");
    }
}
