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

class TimelineOrphanItemSweeperPropertiesTest {

    @Test
    void enablesSweeperByDefault() throws IOException, NoSuchMethodException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            assertThat(input).isNotNull();
            properties.load(input);
        }

        assertThat(properties.getProperty("app.timeline.orphan-sweep.worker-enabled"))
                .isEqualTo("${TIMELINE_ORPHAN_SWEEP_WORKER_ENABLED:true}");

        Constructor<TimelineOrphanItemSweeperProperties> constructor =
                TimelineOrphanItemSweeperProperties.class.getDeclaredConstructor(
                        boolean.class, int.class, int.class, Duration.class);
        assertThat(constructor.getParameters()[0].getAnnotation(Value.class).value())
                .isEqualTo("${app.timeline.orphan-sweep.worker-enabled:true}");
    }

    @Test
    void acceptsInclusiveBounds() {
        TimelineOrphanItemSweeperProperties minimum = properties(true, 1, 1, Duration.ofMillis(1));
        TimelineOrphanItemSweeperProperties maximum = properties(false, 1_000, 1_000, Duration.ofMinutes(10));

        assertThat(minimum.isWorkerEnabled()).isTrue();
        assertThat(minimum.getBatchSize()).isEqualTo(1);
        assertThat(maximum.isWorkerEnabled()).isFalse();
        assertThat(maximum.getMaxBatchesPerRun()).isEqualTo(1_000);
    }

    @Test
    void rejectsValuesOutsideBounds() {
        assertThatIllegalStateException()
                .isThrownBy(() -> properties(false, 0, 4, Duration.ofSeconds(60)))
                .withMessageContaining("batch-size");
        assertThatIllegalStateException()
                .isThrownBy(() -> properties(false, 250, 0, Duration.ofSeconds(60)))
                .withMessageContaining("max-batches-per-run");
        assertThatIllegalStateException()
                .isThrownBy(() -> properties(false, 250, 4, Duration.ZERO))
                .withMessageContaining("max-run-duration");
        assertThatIllegalStateException()
                .isThrownBy(() -> properties(false, 250, 4, Duration.ofMinutes(11)))
                .withMessageContaining("max-run-duration");
    }

    private TimelineOrphanItemSweeperProperties properties(
            boolean enabled, int batchSize, int maxBatchesPerRun, Duration maxRunDuration) {
        return new TimelineOrphanItemSweeperProperties(enabled, batchSize, maxBatchesPerRun, maxRunDuration);
    }
}
