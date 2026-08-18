package com.laimory.server.push.service;

import com.laimory.server.push.PushComplianceClass;
import com.laimory.server.push.PushSenderProperties;
import com.laimory.server.push.ScheduledNotificationType;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 일일 리마인더 worker의 runtime 설정과 기동 시 불변식 검증.
 *
 * <p>기본은 OFF다 — 광고성으로 확정된 알림을 켜는 것은 법무 확정 문구 seed와 전송자 정보가 준비된 뒤의
 * 운영 결정이지 배포의 부작용이 아니다. 광고성 알림의 worker를 켜면서 전송자 법인명·연락처를 비워 두면
 * 기동을 실패시킨다 — 광고 표기 없는 광고성 발송을 막는 fail-fast다.
 */
@Component
public class DailyReminderWorkerProperties {

    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_CONCURRENCY = 2;
    private static final int MAX_BATCHES_PER_RUN = 1_000;
    private static final Duration MAX_RUN_DURATION = Duration.ofMinutes(5);
    private static final Duration MAX_LATENESS_LIMIT = Duration.ofHours(1);

    private final boolean workerEnabled;
    private final Duration maxLateness;
    private final int batchSize;
    private final int concurrency;
    private final int maxBatchesPerRun;
    private final Duration maxRunDuration;

    public DailyReminderWorkerProperties(
            @Value("${app.push.daily-reminder.worker-enabled:false}") boolean workerEnabled,
            @Value("${app.push.daily-reminder.max-lateness:30m}") Duration maxLateness,
            @Value("${app.push.daily-reminder.batch-size:250}") int batchSize,
            @Value("${app.push.daily-reminder.concurrency:1}") int concurrency,
            @Value("${app.push.daily-reminder.max-batches-per-run:4}") int maxBatchesPerRun,
            @Value("${app.push.daily-reminder.max-run-duration:30s}") Duration maxRunDuration,
            PushSenderProperties pushSenderProperties) {
        if (maxLateness.isZero() || maxLateness.isNegative() || maxLateness.compareTo(MAX_LATENESS_LIMIT) > 0) {
            throw new IllegalStateException("app.push.daily-reminder.max-lateness must be positive and at most "
                    + MAX_LATENESS_LIMIT);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException(
                    "app.push.daily-reminder.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalStateException(
                    "app.push.daily-reminder.concurrency must be between 1 and " + MAX_CONCURRENCY);
        }
        if (maxBatchesPerRun < 1 || maxBatchesPerRun > MAX_BATCHES_PER_RUN) {
            throw new IllegalStateException("app.push.daily-reminder.max-batches-per-run must be between 1 and "
                    + MAX_BATCHES_PER_RUN);
        }
        if (maxRunDuration.isZero() || maxRunDuration.isNegative()
                || maxRunDuration.compareTo(MAX_RUN_DURATION) > 0) {
            throw new IllegalStateException("app.push.daily-reminder.max-run-duration must be positive and at most "
                    + MAX_RUN_DURATION);
        }
        if (workerEnabled
                && ScheduledNotificationType.DAILY_REMINDER.complianceClass() == PushComplianceClass.ADVERTISING
                && !pushSenderProperties.isConfigured()) {
            throw new IllegalStateException("app.push.sender-name and app.push.sender-contact are required "
                    + "to enable an advertising notification worker");
        }
        this.workerEnabled = workerEnabled;
        this.maxLateness = maxLateness;
        this.batchSize = batchSize;
        this.concurrency = concurrency;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.maxRunDuration = maxRunDuration;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    /** 예정 시각을 이만큼 넘긴 occurrence는 발송하지 않고 다음 occurrence로 넘긴다. */
    public Duration getMaxLateness() {
        return maxLateness;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public Duration getMaxRunDuration() {
        return maxRunDuration;
    }
}
