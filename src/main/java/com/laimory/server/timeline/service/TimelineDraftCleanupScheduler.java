package com.laimory.server.timeline.service;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관기간을 초과한 orphan draft source 행을 주기적으로 삭제하는 스케줄러.
 *
 * <p>불변식: 보관기간(retentionDays, 기본 7일)은 PROCESSING_TTL(1시간)보다 훨씬 커야 한다(retention ≫ PROCESSING_TTL 1h).
 * 그래야 처리 중(in-flight)인 task의 draft가 cutoff에 걸려 조기 삭제되는 일이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineDraftCleanupScheduler {

    /**
     * 허용 최소 보관기간(일). 1일(24h)이면 PROCESSING_TTL(1h)보다 충분히 크다.
     * 이보다 작은 값(0·음수 포함)은 active draft를 조기 삭제할 수 있어 기동 시 거부한다.
     */
    private static final long MIN_RETENTION_DAYS = 1;

    private final TimelineDraftSourceItemService timelineDraftSourceItemService;
    private final Clock clock;

    @Value("${app.draft.retention-days}")
    private long retentionDays;

    /**
     * 클래스 불변식(retention ≫ PROCESSING_TTL 1h)을 기동 시점에 fail-fast로 강제한다.
     * 잘못된 설정이 04:00 cleanup 때 조용히 active draft를 지우는 대신, 배포 즉시 부팅을 실패시킨다.
     */
    @PostConstruct
    void validateRetentionDays() {
        if (retentionDays < MIN_RETENTION_DAYS) {
            throw new IllegalStateException(
                    "app.draft.retention-days는 최소 %d 이상이어야 한다(현재 %d). retention은 PROCESSING_TTL(1h)보다 훨씬 커야 active draft가 조기 삭제되지 않는다."
                            .formatted(MIN_RETENTION_DAYS, retentionDays));
        }
    }

    /** 매일 04:00(서버 존)에 보관기간 초과 draft 행을 삭제한다. */
    @Scheduled(cron = "${app.draft.cleanup-cron:0 0 4 * * *}")
    public void cleanupExpiredDrafts() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(retentionDays);
        log.info("draft cleanup 시작: cutoff={}, retentionDays={}", cutoff, retentionDays);
        timelineDraftSourceItemService.deleteCreatedBefore(cutoff);
    }
}
