package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimelinePhotoDeleteMetricsTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private TimelinePhotoDeleteJobService jobService;

    @Test
    void recordsAttemptEnqueueAndBatchDurationValues() {
        MockClock meterClock = new MockClock();
        SimpleMeterRegistry registry =
                new SimpleMeterRegistry(SimpleConfig.DEFAULT, meterClock);
        TimelinePhotoDeleteMetrics metrics =
                new TimelinePhotoDeleteMetrics(registry, jobService, CLOCK);

        metrics.recordAttemptSuccess(2);
        metrics.recordAttemptFailed(3);
        metrics.recordAttemptSuccess(0);
        metrics.recordAttemptFailed(-1);
        metrics.recordEnqueueScheduled(4);
        metrics.recordEnqueueSharedRetained(5);
        metrics.recordEnqueueInvalidSkipped(6);
        Timer.Sample sample = metrics.startBatch();
        meterClock.add(Duration.ofMillis(1_250));
        metrics.recordBatch(sample);

        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_ATTEMPT)
                        .tag("result", "success")
                        .counter()
                        .count())
                .isEqualTo(2);
        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_ATTEMPT)
                        .tag("result", "failed")
                        .counter()
                        .count())
                .isEqualTo(3);
        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_ENQUEUE)
                        .tag("result", "scheduled")
                        .counter()
                        .count())
                .isEqualTo(4);
        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_ENQUEUE)
                        .tag("result", "shared_retained")
                        .counter()
                        .count())
                .isEqualTo(5);
        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_ENQUEUE)
                        .tag("result", "invalid_skipped")
                        .counter()
                        .count())
                .isEqualTo(6);
        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_BATCH_DURATION)
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_BATCH_DURATION)
                        .timer()
                        .totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(1_250);
    }

    @Test
    void gaugesExposePendingCountAndOldestAge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jobService.countPending()).thenReturn(7L);
        when(jobService.findOldestCreatedAt())
                .thenReturn(Optional.of(LocalDateTime.ofInstant(
                        NOW.minusSeconds(90), ZoneOffset.UTC)));
        TimelinePhotoDeleteMetrics metrics =
                new TimelinePhotoDeleteMetrics(registry, jobService, CLOCK);

        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_PENDING)
                        .gauge()
                        .value())
                .isEqualTo(7);
        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_OLDEST_AGE)
                        .gauge()
                        .value())
                .isEqualTo(90);
        assertThat(metrics).isNotNull();
    }

    @Test
    void emptyAndFutureOldestJobHaveZeroAge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jobService.findOldestCreatedAt())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(LocalDateTime.ofInstant(
                        NOW.plusSeconds(30), ZoneOffset.UTC)));
        TimelinePhotoDeleteMetrics metrics =
                new TimelinePhotoDeleteMetrics(registry, jobService, CLOCK);

        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_OLDEST_AGE)
                        .gauge()
                        .value())
                .isZero();
        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_OLDEST_AGE)
                        .gauge()
                        .value())
                .isZero();
        assertThat(metrics).isNotNull();
    }

    @Test
    void databaseFailuresBecomeNanInsteadOfBreakingGaugeScrape() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jobService.countPending()).thenThrow(new RuntimeException("db unavailable"));
        when(jobService.findOldestCreatedAt()).thenThrow(new RuntimeException("db unavailable"));
        TimelinePhotoDeleteMetrics metrics =
                new TimelinePhotoDeleteMetrics(registry, jobService, CLOCK);

        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_PENDING)
                        .gauge()
                        .value())
                .isNaN();
        assertThat(registry.get(TimelinePhotoDeleteMetrics.DELETE_OLDEST_AGE)
                        .gauge()
                        .value())
                .isNaN();
        assertThat(metrics).isNotNull();
    }
}
