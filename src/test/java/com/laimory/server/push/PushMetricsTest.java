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

        metrics.record(PushMessageType.DAILY_REMINDER,
                new PushSendResult(6, 3, 2, 1, List.of("not-a-metric-label")));

        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("type", "DAILY_REMINDER").tag("result", "success").counter().count()).isEqualTo(3);
        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("type", "DAILY_REMINDER").tag("result", "failed").counter().count()).isEqualTo(2);
        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("type", "DAILY_REMINDER").tag("result", "skipped").counter().count()).isEqualTo(1);

        Set<String> tagKeys = registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        // 차원은 고정 알림 종류와 결과뿐이다 — FID·subject·오류 원문은 label이 되지 않는다.
        assertThat(tagKeys).containsOnly("type", "result");
    }

    @Test
    void separatesCountersPerNotificationType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PushMetrics metrics = new PushMetrics(registry);

        metrics.record(PushMessageType.TIMELINE_COMPLETION, new PushSendResult(1, 1, 0, 0, List.of()));
        metrics.record(PushMessageType.DAILY_REMINDER, new PushSendResult(2, 2, 0, 0, List.of()));

        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("type", "TIMELINE_COMPLETION").tag("result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("type", "DAILY_REMINDER").tag("result", "success").counter().count()).isEqualTo(2);
    }
}
