package com.laimory.server.inquiry.service;

import com.laimory.server.inquiry.repository.InquiryRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 처리됨 표시({@code answered_at})가 없는 문의 수(#603).
 *
 * <p>경보 규칙이 매일 13:00~13:05(KST)에만 이 값을 보고 0보다 크면 한 번 알린다 — 접수 시각은 보지 않는다.
 * 값은 scrape마다 세지 않고 {@link #REFRESH_INTERVAL}에 한 번만 DB에서 다시 센다. DB 오류는 actuator scrape 전체를
 * 실패시키지 않도록 NaN으로 낸다.
 */
@Slf4j
@Component
public class InquiryUnansweredMetrics {

    static final String UNANSWERED = "laimory.inquiry.unanswered";
    static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);

    private final InquiryRepository inquiryRepository;
    private final Clock clock;
    private Instant refreshedAt;
    private double lastValue;

    public InquiryUnansweredMetrics(MeterRegistry meterRegistry, InquiryRepository inquiryRepository, Clock clock) {
        this.inquiryRepository = inquiryRepository;
        this.clock = clock;
        Gauge.builder(UNANSWERED, this, InquiryUnansweredMetrics::count)
                .description("Inquiries not yet marked as answered")
                .register(meterRegistry);
    }

    synchronized double count() {
        Instant now = clock.instant();
        if (refreshedAt != null && now.isBefore(refreshedAt.plus(REFRESH_INTERVAL))) {
            return lastValue;
        }
        try {
            lastValue = inquiryRepository.countByAnsweredAtIsNull();
        } catch (RuntimeException e) {
            log.debug("unanswered inquiry metric 조회 실패", e);
            lastValue = Double.NaN;
        }
        refreshedAt = now;
        return lastValue;
    }
}
