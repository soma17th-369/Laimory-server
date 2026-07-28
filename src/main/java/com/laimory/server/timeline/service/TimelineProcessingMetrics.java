package com.laimory.server.timeline.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PROCESSING TTL 안에서 관측 threshold를 넘긴 timeline task 수.
 *
 * <p>값은 Redis 보조 인덱스에서 scrape 시점에 계산한다. Redis 장애가 actuator scrape 전체를 실패시키지
 * 않도록 NaN을 반환하며, 별도의 Redis target/backend alert가 원인을 알린다.
 */
@Slf4j
@Component
public class TimelineProcessingMetrics {

    static final String STUCK_PROCESSING = "laimory.timeline.task.processing.stuck";

    private final TimelineTaskService timelineTaskService;
    private final Clock clock;
    private final Duration stuckAfter;

    public TimelineProcessingMetrics(MeterRegistry meterRegistry,
                                     TimelineTaskService timelineTaskService,
                                     Clock clock,
                                     @Value("${app.metrics.timeline.stuck-after:90s}")
                                     Duration stuckAfter) {
        if (stuckAfter.isZero() || stuckAfter.isNegative()
                || stuckAfter.compareTo(TimelineTaskService.PROCESSING_TTL) >= 0) {
            throw new IllegalArgumentException(
                    "app.metrics.timeline.stuck-after는 0보다 크고 PROCESSING TTL(3분)보다 짧아야 합니다: " + stuckAfter);
        }
        this.timelineTaskService = timelineTaskService;
        this.clock = clock;
        this.stuckAfter = stuckAfter;
        Gauge.builder(STUCK_PROCESSING, this, TimelineProcessingMetrics::count)
                .description("Timeline tasks still processing beyond the configured threshold")
                .register(meterRegistry);
    }

    double count() {
        try {
            return timelineTaskService.countStuckProcessing(Instant.now(clock), stuckAfter);
        } catch (RuntimeException e) {
            log.debug("stuck PROCESSING metric 조회 실패", e);
            return Double.NaN;
        }
    }
}
