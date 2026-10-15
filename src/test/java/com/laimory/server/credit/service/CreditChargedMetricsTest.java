package com.laimory.server.credit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.credit.CreditHistoryStatus;
import com.laimory.server.credit.repository.CreditHistoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreditChargedMetricsTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");
    private static final Duration THRESHOLD = Duration.ofHours(1);

    @Mock
    private CreditHistoryRepository creditHistoryRepository;

    @Test
    void gaugeCountsChargedOlderThanThresholdInSeoulWallClock() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // created_at은 앱 Clock의 Seoul 벽시계로 저장된다 — 12:00(KST) 기준 1시간 전.
        when(creditHistoryRepository.countByStatusAndCreatedAtBefore(
                CreditHistoryStatus.CHARGED, LocalDateTime.parse("2026-10-15T11:00:00"))).thenReturn(3L);

        new CreditChargedMetrics(registry, creditHistoryRepository, Clock.fixed(NOW, SEOUL), THRESHOLD);

        assertThat(registry.get(CreditChargedMetrics.STALE_CHARGED).gauge().value()).isEqualTo(3);
    }

    @Test
    void reusesLastCountUntilRefreshIntervalPasses() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(NOW);
        when(creditHistoryRepository.countByStatusAndCreatedAtBefore(any(), any())).thenReturn(1L, 4L);
        new CreditChargedMetrics(registry, creditHistoryRepository, clock, THRESHOLD);

        double first = registry.get(CreditChargedMetrics.STALE_CHARGED).gauge().value();
        clock.advance(CreditChargedMetrics.REFRESH_INTERVAL.minusSeconds(1));
        double withinInterval = registry.get(CreditChargedMetrics.STALE_CHARGED).gauge().value();
        clock.advance(Duration.ofSeconds(1));
        double afterInterval = registry.get(CreditChargedMetrics.STALE_CHARGED).gauge().value();

        assertThat(first).isEqualTo(1);
        assertThat(withinInterval).isEqualTo(1);
        assertThat(afterInterval).isEqualTo(4);
        verify(creditHistoryRepository, times(2)).countByStatusAndCreatedAtBefore(any(), any());
    }

    @Test
    void databaseFailureBecomesNanInsteadOfBreakingScrape() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(creditHistoryRepository.countByStatusAndCreatedAtBefore(any(), any()))
                .thenThrow(new RuntimeException("db down"));

        new CreditChargedMetrics(registry, creditHistoryRepository, Clock.fixed(NOW, SEOUL), THRESHOLD);

        assertThat(registry.get(CreditChargedMetrics.STALE_CHARGED).gauge().value()).isNaN();
    }

    @Test
    void nonPositiveThresholdFailsFast() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(NOW, SEOUL);

        assertThatThrownBy(() -> new CreditChargedMetrics(registry, creditHistoryRepository, clock, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreditChargedMetrics(
                registry, creditHistoryRepository, clock, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
