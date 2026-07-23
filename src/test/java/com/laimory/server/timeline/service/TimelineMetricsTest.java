package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TimelineMetricsTest {

    @Test
    void recordsOnlyBoundedTimelineMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TimelineMetrics metrics = new TimelineMetrics(registry);

        metrics.recordDraftCreated();
        metrics.recordTerminalSuccess();
        metrics.recordTerminalFailed();
        Timer.Sample callback = metrics.startCallback();
        metrics.recordCallback(callback);

        assertThat(counter(registry, TimelineMetrics.DRAFT_CREATED).count()).isEqualTo(1);
        assertThat(registry.get(TimelineMetrics.TERMINAL_TRANSITION)
                .tag("result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get(TimelineMetrics.TERMINAL_TRANSITION)
                .tag("result", "failed").counter().count()).isEqualTo(1);
        assertThat(registry.get(TimelineMetrics.CALLBACK_DURATION).timer().count()).isEqualTo(1);

        Set<String> tagKeys = registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        assertThat(tagKeys).containsOnly("result");
    }

    private static Counter counter(SimpleMeterRegistry registry, String name) {
        return registry.get(name).counter();
    }
}
