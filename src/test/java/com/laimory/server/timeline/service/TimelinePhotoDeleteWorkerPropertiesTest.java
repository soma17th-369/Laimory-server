package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

class TimelinePhotoDeleteWorkerPropertiesTest {

    @Test
    void enablesWorkerByDefault() throws IOException, NoSuchMethodException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            assertThat(input).isNotNull();
            properties.load(input);
        }

        assertThat(properties.getProperty("app.timeline.photo-delete.worker-enabled"))
                .isEqualTo("${TIMELINE_PHOTO_DELETE_WORKER_ENABLED:true}");

        Constructor<TimelinePhotoDeleteWorkerProperties> constructor =
                TimelinePhotoDeleteWorkerProperties.class.getDeclaredConstructor(
                        boolean.class, int.class, int.class, int.class, Duration.class);
        assertThat(constructor.getParameters()[0].getAnnotation(Value.class).value())
                .isEqualTo("${app.timeline.photo-delete.worker-enabled:true}");
    }

    @Test
    void acceptsInclusiveBounds() {
        TimelinePhotoDeleteWorkerProperties minimum = properties(true, 1, 1, 1, Duration.ofMillis(1));
        TimelinePhotoDeleteWorkerProperties maximum = properties(
                false, 1_000, 2, 1_000, Duration.ofMinutes(10));

        assertThat(minimum.isWorkerEnabled()).isTrue();
        assertThat(minimum.getBatchSize()).isEqualTo(1);
        assertThat(maximum.isWorkerEnabled()).isFalse();
        assertThat(maximum.getConcurrency()).isEqualTo(2);
    }

    @Test
    void rejectsValuesOutsideBounds() {
        assertThatIllegalStateException().isThrownBy(
                () -> properties(false, 0, 1, 4, Duration.ofSeconds(60)))
                .withMessageContaining("batch-size");
        assertThatIllegalStateException().isThrownBy(
                () -> properties(false, 250, 3, 4, Duration.ofSeconds(60)))
                .withMessageContaining("concurrency");
        assertThatIllegalStateException().isThrownBy(
                () -> properties(false, 250, 1, 0, Duration.ofSeconds(60)))
                .withMessageContaining("max-batches-per-run");
        assertThatIllegalStateException().isThrownBy(
                () -> properties(false, 250, 1, 4, Duration.ZERO))
                .withMessageContaining("max-run-duration");
    }

    private TimelinePhotoDeleteWorkerProperties properties(
            boolean enabled, int batchSize, int concurrency, int maxBatches, Duration duration) {
        return new TimelinePhotoDeleteWorkerProperties(
                enabled, batchSize, concurrency, maxBatches, duration);
    }
}
