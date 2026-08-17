package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PENDING 계정 삭제 작업 gauge(#305): scrape 시점 count·최고령 접수 경과 초(없으면 0), DB 장애는
 * scrape 전체를 깨지 않는 NaN({@code TimelineProcessingMetrics} 선례). 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class AccountErasureJobMetricsTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private AccountErasureJobService accountErasureJobService;

    @Test
    void gaugesReportPendingCountAndOldestAgeSeconds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(accountErasureJobService.countPending()).thenReturn(3L);
        when(accountErasureJobService.findOldestPendingCreatedAt())
                .thenReturn(Optional.of(LocalDateTime.now(CLOCK).minusSeconds(120)));

        new AccountErasureJobMetrics(registry, accountErasureJobService, CLOCK);

        assertThat(registry.get(AccountErasureJobMetrics.PENDING_COUNT).gauge().value()).isEqualTo(3);
        assertThat(registry.get(AccountErasureJobMetrics.PENDING_OLDEST_AGE).gauge().value()).isEqualTo(120);
    }

    @Test
    void emptyBacklogReportsZeroAge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(accountErasureJobService.findOldestPendingCreatedAt()).thenReturn(Optional.empty());

        new AccountErasureJobMetrics(registry, accountErasureJobService, CLOCK);

        assertThat(registry.get(AccountErasureJobMetrics.PENDING_OLDEST_AGE).gauge().value()).isZero();
    }

    @Test
    void databaseFailureBecomesNanInsteadOfBreakingScrape() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(accountErasureJobService.countPending()).thenThrow(new RuntimeException("db down"));
        when(accountErasureJobService.findOldestPendingCreatedAt()).thenThrow(new RuntimeException("db down"));

        new AccountErasureJobMetrics(registry, accountErasureJobService, CLOCK);

        assertThat(registry.get(AccountErasureJobMetrics.PENDING_COUNT).gauge().value()).isNaN();
        assertThat(registry.get(AccountErasureJobMetrics.PENDING_OLDEST_AGE).gauge().value()).isNaN();
    }

    @Test
    void clockSkewNeverReportsNegativeAge() {
        // 감사 시각이 scrape 시계보다 미래인 skew에서도 음수 나이를 내보내지 않는다(0 clamp).
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(accountErasureJobService.findOldestPendingCreatedAt())
                .thenReturn(Optional.of(LocalDateTime.now(CLOCK).plusSeconds(30)));

        new AccountErasureJobMetrics(registry, accountErasureJobService, CLOCK);

        assertThat(registry.get(AccountErasureJobMetrics.PENDING_OLDEST_AGE).gauge().value()).isZero();
    }
}
