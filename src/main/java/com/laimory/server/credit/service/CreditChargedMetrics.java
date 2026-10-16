package com.laimory.server.credit.service;

import com.laimory.server.credit.CreditHistoryStatus;
import com.laimory.server.credit.repository.CreditHistoryRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * threshold를 넘겨서도 {@code CHARGED}로 남은 크레딧 기록 수(#582).
 *
 * <p>정상 흐름의 {@code CHARGED}는 결과 저장({@code CONSUMED})이나 실패·유실 환불({@code REFUNDED})로 수 분 안에
 * 전이한다. threshold(기본 1시간)를 넘긴 기록은 유실 환불(Redis 만료 알림)이 놓친 것이다 — 구독 끊김·Redis
 * 재시작·eviction은 알림을 남기지 않는다. 자동 sweeper는 두지 않고 이 값의 경보로 운영자가 확인·수동 환불한다.
 *
 * <p>값은 scrape마다 세지 않고 {@link #REFRESH_INTERVAL}에 한 번만 DB에서 다시 센다. 경보 threshold가 1시간이라
 * 수 분 지연은 무해하고, 상태 인덱스 없는 count를 scrape 주기 × 서버 수만큼 반복하지 않기 위해서다.
 * DB 오류는 actuator scrape 전체를 실패시키지 않도록 NaN으로 낸다.
 */
@Slf4j
@Component
public class CreditChargedMetrics {

    static final String STALE_CHARGED = "laimory.credit.history.charged.stale";
    static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);

    private final CreditHistoryRepository creditHistoryRepository;
    private final Clock clock;
    private final Duration staleAfter;
    private Instant refreshedAt;
    private double lastValue;

    public CreditChargedMetrics(MeterRegistry meterRegistry,
                                CreditHistoryRepository creditHistoryRepository,
                                Clock clock,
                                @Value("${app.metrics.credit.charged-stale-after:1h}") Duration staleAfter) {
        if (staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException(
                    "app.metrics.credit.charged-stale-after는 0보다 커야 합니다: " + staleAfter);
        }
        this.creditHistoryRepository = creditHistoryRepository;
        this.clock = clock;
        this.staleAfter = staleAfter;
        Gauge.builder(STALE_CHARGED, this, CreditChargedMetrics::count)
                .description("Credit histories still CHARGED beyond the configured threshold")
                .register(meterRegistry);
    }

    synchronized double count() {
        Instant now = clock.instant();
        if (refreshedAt != null && now.isBefore(refreshedAt.plus(REFRESH_INTERVAL))) {
            return lastValue;
        }
        try {
            lastValue = creditHistoryRepository.countByStatusAndCreatedAtBefore(
                    CreditHistoryStatus.CHARGED, LocalDateTime.now(clock).minus(staleAfter));
        } catch (RuntimeException e) {
            log.debug("stale CHARGED metric 조회 실패", e);
            lastValue = Double.NaN;
        }
        refreshedAt = now;
        return lastValue;
    }
}
