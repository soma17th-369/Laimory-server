package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PushMetricsTest {

    @Test
    void recordsOnlyBoundedDeliveryResultMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PushMetrics metrics = new PushMetrics(registry);

        metrics.record(new PushSendResult(5, 3, 2, List.of("not-a-metric-label")));

        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("result", "success").counter().count()).isEqualTo(3);
        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("result", "failed").counter().count()).isEqualTo(2);

        Set<String> tagKeys = registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        assertThat(tagKeys).containsOnly("result");
    }
}
