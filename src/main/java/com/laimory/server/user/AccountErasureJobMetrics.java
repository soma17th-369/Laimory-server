package com.laimory.server.user;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PENDING 계정 삭제 작업 backlog 관측(#305) — count와 최고령 접수 경과 시간(초).
 *
 * <p>#302 worker 가동 전에는 backlog가 단조 증가하는 것이 정상이며, 이 두 값은 HMAC secret 갱신 전
 * runbook gate(PENDING 1건 이상이면 previous key retire·두 번째 rotation 금지)의 판단 근거다.
 * 값은 scrape 시점에 MySQL에서 계산하고, DB 장애가 actuator scrape 전체를 실패시키지 않도록 NaN을
 * 반환한다({@code TimelineProcessingMetrics} 선례) — 별도 DB target alert가 원인을 알린다.
 */
@Slf4j
@Component
public class AccountErasureJobMetrics {

    static final String PENDING_COUNT = "laimory.account.erasure.job.pending";
    static final String PENDING_OLDEST_AGE = "laimory.account.erasure.job.pending.oldest.age";

    private final AccountErasureJobService accountErasureJobService;
    private final Clock clock;

    public AccountErasureJobMetrics(MeterRegistry meterRegistry,
                                    AccountErasureJobService accountErasureJobService,
                                    Clock clock) {
        this.accountErasureJobService = accountErasureJobService;
        this.clock = clock;
        Gauge.builder(PENDING_COUNT, this, AccountErasureJobMetrics::pendingCount)
                .description("Account erasure jobs waiting for the future #302 worker")
                .register(meterRegistry);
        Gauge.builder(PENDING_OLDEST_AGE, this, AccountErasureJobMetrics::oldestPendingAgeSeconds)
                .baseUnit("seconds")
                .description("Age in seconds of the oldest PENDING account erasure job (0 when none)")
                .register(meterRegistry);
    }

    double pendingCount() {
        try {
            return accountErasureJobService.countPending();
        } catch (RuntimeException e) {
            log.debug("PENDING erasure job count metric 조회 실패", e);
            return Double.NaN;
        }
    }

    double oldestPendingAgeSeconds() {
        try {
            return accountErasureJobService.findOldestPendingCreatedAt()
                    .map(createdAt -> (double) Math.max(0L,
                            Duration.between(createdAt, LocalDateTime.now(clock)).getSeconds()))
                    .orElse(0.0d);
        } catch (RuntimeException e) {
            log.debug("PENDING erasure job oldest-age metric 조회 실패", e);
            return Double.NaN;
        }
    }
}
