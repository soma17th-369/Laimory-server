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
                new PushSendResult(5, 3, 2, List.of("not-a-metric-label")));

        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("type", "DAILY_REMINDER").tag("result", "success").counter().count()).isEqualTo(3);
        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("type", "DAILY_REMINDER").tag("result", "failed").counter().count()).isEqualTo(2);
        Set<String> tagKeys = registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        // 차원은 고정 알림 계열과 결과뿐이다 — FID·subject·오류 원문은 label이 되지 않는다.
        assertThat(tagKeys).containsOnly("type", "result");
    }

    @Test
    void separatesCountersPerNotificationGroup() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PushMetrics metrics = new PushMetrics(registry);

        metrics.record(PushMessageType.TIMELINE_COMPLETION_SUCCESS, new PushSendResult(1, 1, 0, List.of()));
        metrics.record(PushMessageType.DAILY_REMINDER, new PushSendResult(2, 2, 0, List.of()));

        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("type", "TIMELINE_COMPLETION").tag("result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("type", "DAILY_REMINDER").tag("result", "success").counter().count()).isEqualTo(2);
    }

    @Test
    void completionCopyVariantsShareOneCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PushMetrics metrics = new PushMetrics(registry);

        // 문구 때문에 나뉜 종류라 발송량은 한 계열로 합산돼야 한다 — 대시보드가 반으로 쪼개져 보이면 안 된다.
        metrics.record(PushMessageType.TIMELINE_COMPLETION_SUCCESS, new PushSendResult(1, 1, 0, List.of()));
        metrics.record(PushMessageType.TIMELINE_COMPLETION_FAILED, new PushSendResult(3, 3, 0, List.of()));

        assertThat(registry.get(PushMetrics.DELIVERY)
                .tag("type", "TIMELINE_COMPLETION").tag("result", "success").counter().count()).isEqualTo(4);
        // 종류는 3개지만 계열은 2개 — counter는 계열×결과 4개로 유지된다.
        assertThat(registry.find(PushMetrics.DELIVERY).meters()).hasSize(4);
    }
}
