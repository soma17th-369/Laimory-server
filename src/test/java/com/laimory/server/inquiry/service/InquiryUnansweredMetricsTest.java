package com.laimory.server.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.inquiry.repository.InquiryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InquiryUnansweredMetricsTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-20T04:00:00Z");

    @Mock
    private InquiryRepository inquiryRepository;

    @Test
    void gaugeReportsUnansweredInquiryCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(inquiryRepository.countByAnsweredAtIsNull()).thenReturn(2L);

        new InquiryUnansweredMetrics(registry, inquiryRepository, Clock.fixed(NOW, SEOUL));

        assertThat(registry.get(InquiryUnansweredMetrics.UNANSWERED).gauge().value()).isEqualTo(2);
    }

    @Test
    void reusesLastCountUntilRefreshIntervalPasses() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(NOW);
        when(inquiryRepository.countByAnsweredAtIsNull()).thenReturn(1L, 0L);
        new InquiryUnansweredMetrics(registry, inquiryRepository, clock);

        double first = registry.get(InquiryUnansweredMetrics.UNANSWERED).gauge().value();
        clock.advance(InquiryUnansweredMetrics.REFRESH_INTERVAL.minusSeconds(1));
        double withinInterval = registry.get(InquiryUnansweredMetrics.UNANSWERED).gauge().value();
        clock.advance(Duration.ofSeconds(1));
        double afterInterval = registry.get(InquiryUnansweredMetrics.UNANSWERED).gauge().value();

        assertThat(first).isEqualTo(1);
        assertThat(withinInterval).isEqualTo(1);
        assertThat(afterInterval).isEqualTo(0);
        verify(inquiryRepository, times(2)).countByAnsweredAtIsNull();
    }

    @Test
    void databaseFailureBecomesNanInsteadOfBreakingScrape() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(inquiryRepository.countByAnsweredAtIsNull()).thenThrow(new RuntimeException("db down"));

        new InquiryUnansweredMetrics(registry, inquiryRepository, Clock.fixed(NOW, SEOUL));

        assertThat(registry.get(InquiryUnansweredMetrics.UNANSWERED).gauge().value()).isNaN();
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
