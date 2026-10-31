package com.laimory.server.credit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.CreditHistoryStatus;
import com.laimory.server.credit.CreditRefundReason;
import com.laimory.server.credit.repository.CreditHistoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

@ExtendWith(MockitoExtension.class)
class CreditAiFailureMetricsTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00Z");

    @Mock
    private CreditHistoryRepository creditHistoryRepository;

    @Test
    void gaugeCountsAiFailureRefundsAmongLastFiveSettledTimelineCreations() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(creditHistoryRepository.findSettledStatuses(CreditCostType.TIMELINE_CREATION,
                CreditHistoryStatus.CONSUMED, CreditHistoryStatus.REFUNDED,
                EnumSet.of(CreditRefundReason.AI_FAILED, CreditRefundReason.TIMEOUT,
                        CreditRefundReason.DISPATCH_REJECTED),
                Limit.of(5)))
                .thenReturn(List.of(CreditHistoryStatus.REFUNDED, CreditHistoryStatus.CONSUMED,
                        CreditHistoryStatus.REFUNDED, CreditHistoryStatus.REFUNDED, CreditHistoryStatus.CONSUMED));

        new CreditAiFailureMetrics(registry, creditHistoryRepository, Clock.fixed(NOW, SEOUL));

        assertThat(registry.get(CreditAiFailureMetrics.RECENT_AI_FAILED).gauge().value()).isEqualTo(3);
    }

    @Test
    void reusesLastCountUntilRefreshIntervalPasses() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(NOW);
        when(creditHistoryRepository.findSettledStatuses(any(), any(), any(), any(), any()))
                .thenReturn(List.of(CreditHistoryStatus.REFUNDED),
                        List.of(CreditHistoryStatus.REFUNDED, CreditHistoryStatus.REFUNDED));
        new CreditAiFailureMetrics(registry, creditHistoryRepository, clock);

        double first = registry.get(CreditAiFailureMetrics.RECENT_AI_FAILED).gauge().value();
        clock.advance(CreditAiFailureMetrics.REFRESH_INTERVAL.minusSeconds(1));
        double withinInterval = registry.get(CreditAiFailureMetrics.RECENT_AI_FAILED).gauge().value();
        clock.advance(Duration.ofSeconds(1));
        double afterInterval = registry.get(CreditAiFailureMetrics.RECENT_AI_FAILED).gauge().value();

        assertThat(first).isEqualTo(1);
        assertThat(withinInterval).isEqualTo(1);
        assertThat(afterInterval).isEqualTo(2);
        verify(creditHistoryRepository, times(2)).findSettledStatuses(any(), any(), any(), any(), any());
    }

    @Test
    void databaseFailureBecomesNanInsteadOfBreakingScrape() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(creditHistoryRepository.findSettledStatuses(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        new CreditAiFailureMetrics(registry, creditHistoryRepository, Clock.fixed(NOW, SEOUL));

        assertThat(registry.get(CreditAiFailureMetrics.RECENT_AI_FAILED).gauge().value()).isNaN();
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
