package com.laimory.server.push.service;

import com.laimory.server.common.ScheduledWorkerRunBudget;
import com.laimory.server.push.PushTimes;
import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import com.laimory.server.push.service.DailyReminderPushNotifier.BatchOutcome;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일일 리마인더 occurrence를 여러 process/thread에서 batch claim해 발송하는 worker.
 *
 * <p>모든 process가 매분 trigger를 돌린다. 짧은 claim transaction이 {@code SKIP LOCKED}로 서로 다른
 * subject 행을 나눠 잡고 처리한 occurrence 날짜와 다음 예정 시각을 먼저 commit한 뒤, FID 조회와 FCM
 * 호출은 transaction 밖에서 한다 — 같은 사용자·같은 날짜가 두 번 발송되지 않는 것이 우선이고, 그 대가로
 * claim commit 뒤 process가 죽으면 그 날 알림은 누락된다(자동 재발송 없음, at-most-once best-effort).
 *
 * <p>허용 지연을 넘긴 occurrence는 발송 없이 건너뛴다. 오래 내려가 있던 서버가 복구되면서 새벽에
 * 밀린 알림을 쏟아내지 않게 하는 장치이며, 그래도 claim은 해서 다음 미래 occurrence로 옮긴다.
 *
 * <p>Redis 전역 lock은 쓰지 않는다 — schedule 행의 PK와 row lock이 중복 방지 권위다.
 */
@Slf4j
@Component
public class DailyReminderWorker {

    private static final ScheduledNotificationType TYPE = ScheduledNotificationType.DAILY_REMINDER;

    private final ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;
    private final DailyReminderPushNotifier dailyReminderPushNotifier;
    private final DailyReminderWorkerProperties properties;
    private final TaskExecutor workerExecutor;
    private final Clock clock;
    private final AtomicBoolean runActive = new AtomicBoolean();

    public DailyReminderWorker(
            ScheduledNotificationPreferenceService scheduledNotificationPreferenceService,
            DailyReminderPushNotifier dailyReminderPushNotifier,
            DailyReminderWorkerProperties properties,
            @Qualifier("dailyReminderWorkerExecutor") TaskExecutor workerExecutor,
            Clock clock) {
        this.scheduledNotificationPreferenceService = scheduledNotificationPreferenceService;
        this.dailyReminderPushNotifier = dailyReminderPushNotifier;
        this.properties = properties;
        this.workerExecutor = workerExecutor;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${app.push.daily-reminder.cron:0 * * * * *}",
            zone = "${app.push.daily-reminder.zone:Asia/Seoul}")
    public void sendDueReminders() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        if (!runActive.compareAndSet(false, true)) {
            log.info("일일 리마인더 worker 이전 run이 아직 실행 중이어서 trigger를 건너뜀");
            return;
        }

        ScheduledWorkerRunBudget budget = new ScheduledWorkerRunBudget(
                properties.getMaxBatchesPerRun(), properties.getMaxRunDuration());
        AtomicInteger remainingSlots = new AtomicInteger(properties.getConcurrency());
        RunSummary summary = new RunSummary();
        for (int slot = 0; slot < properties.getConcurrency(); slot++) {
            try {
                workerExecutor.execute(() -> runWorkerSlot(budget, remainingSlots, summary));
            } catch (RuntimeException exception) {
                summary.recordWorkerError();
                log.warn("일일 리마인더 worker task 제출 실패: exceptionType={}",
                        exception.getClass().getSimpleName());
                workerSlotFinished(remainingSlots, summary);
            }
        }
    }

    private void runWorkerSlot(ScheduledWorkerRunBudget budget, AtomicInteger remainingSlots, RunSummary summary) {
        try {
            while (budget.tryAcquireBatch()) {
                List<ScheduledNotificationPreference> claimed;
                try {
                    claimed = scheduledNotificationPreferenceService.claimDue(TYPE, properties.getBatchSize());
                } catch (RuntimeException exception) {
                    summary.recordClaimError();
                    log.warn("일일 리마인더 claim 실패: exceptionType={}", exception.getClass().getSimpleName());
                    return;
                }
                if (claimed.isEmpty()) {
                    return;
                }
                summary.recordBatch(processClaimedBatch(claimed));
            }
        } finally {
            workerSlotFinished(remainingSlots, summary);
        }
    }

    private void workerSlotFinished(AtomicInteger remainingSlots, RunSummary summary) {
        if (remainingSlots.decrementAndGet() == 0) {
            runActive.set(false);
            summary.logCompleted();
        }
    }

    /**
     * claim한 occurrence를 허용 지연 안/밖으로 가르고 안쪽만 발송한다. 지연 초과분은 이미 다음 미래
     * occurrence로 옮겨져 있으므로 여기서 더 할 일이 없다.
     */
    private BatchResult processClaimedBatch(List<ScheduledNotificationPreference> claimed) {
        LocalDateTime nowKst = PushTimes.kstWallClock(clock.instant());
        List<ScheduledNotificationPreference> deliverable = claimed.stream()
                .filter(preference -> !preference.getNextDueAt().plus(properties.getMaxLateness()).isBefore(nowKst))
                .toList();
        int lateSkipped = claimed.size() - deliverable.size();
        if (lateSkipped > 0) {
            log.info("일일 리마인더 지연 초과 occurrence 건너뜀: claimed={} lateSkipped={} maxLatenessMs={}",
                    claimed.size(), lateSkipped, properties.getMaxLateness().toMillis());
        }
        try {
            BatchOutcome outcome = dailyReminderPushNotifier.notifyAll(deliverable);
            return new BatchResult(claimed.size(), lateSkipped, outcome.targets(), outcome.accepted(), 0);
        } catch (RuntimeException exception) {
            // occurrence는 이미 전진했으므로 이 batch는 그대로 유실된다(자동 재발송 없음).
            log.warn("일일 리마인더 발송 실패: claimed={} exceptionType={}",
                    claimed.size(), exception.getClass().getSimpleName());
            return new BatchResult(claimed.size(), lateSkipped, 0, 0, 1);
        }
    }

    private record BatchResult(int claimed, int lateSkipped, int targets, int accepted, int sendErrors) {
    }

    private static final class RunSummary {

        private final long startedAtNanos = System.nanoTime();
        private int batches;
        private int claimed;
        private int lateSkipped;
        private int targets;
        private int accepted;
        private int sendErrors;
        private int claimErrors;
        private int workerErrors;

        private synchronized void recordBatch(BatchResult result) {
            batches++;
            claimed += result.claimed();
            lateSkipped += result.lateSkipped();
            targets += result.targets();
            accepted += result.accepted();
            sendErrors += result.sendErrors();
        }

        private synchronized void recordClaimError() {
            claimErrors++;
        }

        private synchronized void recordWorkerError() {
            workerErrors++;
        }

        private synchronized void logCompleted() {
            if (batches == 0 && claimErrors == 0 && workerErrors == 0) {
                // 매분 도는 trigger라 due가 없는 대다수 run은 로그를 남기지 않는다.
                return;
            }
            log.info("일일 리마인더 worker run 완료: batches={} claimed={} lateSkipped={} targets={} "
                            + "accepted={} sendErrors={} claimErrors={} workerErrors={} durationMs={}",
                    batches, claimed, lateSkipped, targets, accepted, sendErrors, claimErrors,
                    workerErrors, Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000));
        }
    }
}
