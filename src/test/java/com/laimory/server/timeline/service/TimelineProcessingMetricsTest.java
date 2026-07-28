package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimelineProcessingMetricsTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private TimelineTaskService timelineTaskService;

    @Test
    void gaugeCountsStuckProcessingAtFixedTime() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Duration threshold = Duration.ofSeconds(90);
        when(timelineTaskService.countStuckProcessing(NOW, threshold)).thenReturn(2L);

        new TimelineProcessingMetrics(registry, timelineTaskService, CLOCK, threshold);

        assertThat(registry.get(TimelineProcessingMetrics.STUCK_PROCESSING).gauge().value())
                .isEqualTo(2);
        verify(timelineTaskService).countStuckProcessing(NOW, threshold);
    }

    @Test
    void redisFailureBecomesNanInsteadOfBreakingScrape() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Duration threshold = Duration.ofSeconds(90);
        when(timelineTaskService.countStuckProcessing(NOW, threshold))
                .thenThrow(new RuntimeException("redis down"));

        new TimelineProcessingMetrics(registry, timelineTaskService, CLOCK, threshold);

        assertThat(registry.get(TimelineProcessingMetrics.STUCK_PROCESSING).gauge().value())
                .isNaN();
    }

    @Test
    void invalidThresholdFailsFast() {
        // 0·음수·PROCESSING TTL(2m) 이상은 기동 시 거부한다(90s는 위 테스트에서 허용 확인).
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> new TimelineProcessingMetrics(
                registry, timelineTaskService, CLOCK, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimelineProcessingMetrics(
                registry, timelineTaskService, CLOCK, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimelineProcessingMetrics(
                registry, timelineTaskService, CLOCK, Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
