package com.laimory.server.credit.service;

import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.CreditHistoryStatus;
import com.laimory.server.credit.CreditRefundReason;
import com.laimory.server.credit.repository.CreditHistoryRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * 결말 난 최근 타임라인 생성 {@link #WINDOW}건 중 AI 실패 수(#617).
 *
 * <p>생성 1회는 크레딧 기록 1행이라 결과 저장({@code CONSUMED})이 성공이고, {@link #AI_FAILURE_REASONS} 사유의
 * 환불이 실패다. 진행 중({@code CHARGED})과 서버 저장 실패({@code TASK_SAVE_FAILED} — AI 품질 신호가 아니다)는
 * 창에서 빼 그다음 결말 난 기록으로 채운다. 처리 기한 초과는 요청 3분 뒤 환불되므로 그때 창에 들어온다.
 *
 * <p>값은 {@link #REFRESH_INTERVAL}에 한 번만 DB에서 다시 센다. 조회가 PK 역순으로 결말 난 행 {@link #WINDOW}개를
 * 찾으면 멈춰 싸므로 {@code CreditChargedMetrics}의 5분보다 짧게 둬 경보 지연을 줄인다. DB 오류는 actuator scrape
 * 전체를 실패시키지 않도록 NaN으로 낸다.
 */
@Slf4j
@Component
public class CreditAiFailureMetrics {

    static final String RECENT_AI_FAILED = "laimory.credit.history.recent.ai.failed";
    static final int WINDOW = 5;
    static final Duration REFRESH_INTERVAL = Duration.ofMinutes(1);
    static final Set<CreditRefundReason> AI_FAILURE_REASONS = EnumSet.of(
            CreditRefundReason.AI_FAILED, CreditRefundReason.TIMEOUT, CreditRefundReason.DISPATCH_REJECTED);

    private final CreditHistoryRepository creditHistoryRepository;
    private final Clock clock;
    private Instant refreshedAt;
    private double lastValue;

    public CreditAiFailureMetrics(MeterRegistry meterRegistry,
                                  CreditHistoryRepository creditHistoryRepository,
                                  Clock clock) {
        this.creditHistoryRepository = creditHistoryRepository;
        this.clock = clock;
        Gauge.builder(RECENT_AI_FAILED, this, CreditAiFailureMetrics::count)
                .description("AI failures among the most recent settled timeline creations")
                .register(meterRegistry);
    }

    synchronized double count() {
        Instant now = clock.instant();
        if (refreshedAt != null && now.isBefore(refreshedAt.plus(REFRESH_INTERVAL))) {
            return lastValue;
        }
        try {
            lastValue = creditHistoryRepository.findSettledStatuses(CreditCostType.TIMELINE_CREATION,
                            CreditHistoryStatus.CONSUMED, CreditHistoryStatus.REFUNDED, AI_FAILURE_REASONS,
                            Limit.of(WINDOW)).stream()
                    .filter(status -> status == CreditHistoryStatus.REFUNDED)
                    .count();
        } catch (RuntimeException e) {
            log.debug("recent AI failure metric 조회 실패", e);
            lastValue = Double.NaN;
        }
        refreshedAt = now;
        return lastValue;
    }
}
