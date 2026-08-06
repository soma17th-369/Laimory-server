package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
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
                TimelinePhotoDeleteWorkerProperties.class.getDeclaredConstructor(boolean.class, int.class);
        assertThat(constructor.getParameters()[0].getAnnotation(Value.class).value())
                .isEqualTo("${app.timeline.photo-delete.worker-enabled:true}");
    }

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
