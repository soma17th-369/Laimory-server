package com.laimory.server.push.service;

import com.laimory.server.push.entity.DailyNotificationPreference;
import com.laimory.server.push.service.DailyReminderPushNotifier.BatchOutcome;
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
 * <p>발송 시각이 전원 21:00 고정이라 모든 process가 하루 한 번 그 시각에만 trigger를 돌린다(#385).
 * 짧은 claim transaction이 {@code SKIP LOCKED}로 서로 다른 subject 행을 나눠 잡고 다음 예정 시각을 먼저
 * commit한 뒤, FID 조회와 FCM 호출은 transaction 밖에서 한다 — 같은 occurrence가 두 번 발송되지 않는
 * 것이 우선이고, 그 대가로 claim commit 뒤 process가 죽으면 그 날 알림은 누락된다(자동 재발송 없음,
 * at-most-once best-effort).
 *
 * <p>run 크기는 batch 수({@code max-batches-per-run})로만 제한한다. 하루 1회 trigger는 그날 due를 한
 * run에서 모두 소화해야 한다 — 남긴 초과분을 받아갈 다음 tick이 없어 다음 날 21:00 run으로 밀린다.
 * 그래서 상한을 전체 due를 덮을 만큼 크게 잡는다. 시간 상한은 두지 않는다 — 하루 1회라 run이 길어져도
 * 뒤에 밀리는 trigger가 없다.
 *
 * <p>예정 시각보다 늦었다는 이유로 발송을 건너뛰지 않는다. 발송이 정상 시간대에만 일어난다는 보장은
 * cron이 하루 1회 21:00({@code 0 0 21 * * *})이라는 사실에서 나온다 — 장애로 놓친 occurrence도 다음
 * 21:00 run이 받아 한 번 발송하고 다음 미래 occurrence로 옮긴다. cron을 바꾸면 이 판단이 무효가 된다.
 *
 * <p>Redis 전역 lock은 쓰지 않는다 — schedule 행의 PK와 row lock이 중복 방지 권위다.
 */
@Slf4j
@Component
public class DailyReminderWorker {

    private final DailyNotificationPreferenceService dailyNotificationPreferenceService;
    private final DailyReminderPushNotifier dailyReminderPushNotifier;
    private final DailyReminderWorkerProperties properties;
    private final TaskExecutor workerExecutor;
    private final AtomicBoolean runActive = new AtomicBoolean();

    public DailyReminderWorker(
            DailyNotificationPreferenceService dailyNotificationPreferenceService,
            DailyReminderPushNotifier dailyReminderPushNotifier,
            DailyReminderWorkerProperties properties,
            @Qualifier("dailyReminderWorkerExecutor") TaskExecutor workerExecutor) {
        this.dailyNotificationPreferenceService = dailyNotificationPreferenceService;
        this.dailyReminderPushNotifier = dailyReminderPushNotifier;
        this.properties = properties;
        this.workerExecutor = workerExecutor;
    }

    @Scheduled(
            cron = "${app.push.daily-reminder.cron:0 0 21 * * *}",
            zone = "${app.push.daily-reminder.zone:Asia/Seoul}")
    public void sendDueReminders() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        if (!runActive.compareAndSet(false, true)) {
            log.info("일일 리마인더 worker 이전 run이 아직 실행 중이어서 trigger를 건너뜀");
            return;
        }

        // 여러 slot이 한 상한을 나눠 쓴다.
        AtomicInteger remainingBatches = new AtomicInteger(properties.getMaxBatchesPerRun());
        AtomicInteger remainingSlots = new AtomicInteger(properties.getConcurrency());
        RunSummary summary = new RunSummary();
        for (int slot = 0; slot < properties.getConcurrency(); slot++) {
            try {
                workerExecutor.execute(() -> runWorkerSlot(remainingBatches, remainingSlots, summary));
            } catch (RuntimeException exception) {
                summary.recordWorkerError();
                log.warn("일일 리마인더 worker task 제출 실패: exceptionType={}",
                        exception.getClass().getSimpleName());
                workerSlotFinished(remainingSlots, summary);
            }
        }
    }

    private void runWorkerSlot(AtomicInteger remainingBatches, AtomicInteger remainingSlots, RunSummary summary) {
        try {
            while (remainingBatches.getAndDecrement() > 0) {
                List<DailyNotificationPreference> claimed;
                try {
                    claimed = dailyNotificationPreferenceService.claimDue(properties.getBatchSize());
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

    /** claim한 occurrence를 예정 시각과 무관하게 모두 발송한다 — 정상 시간대 보장은 cron이 진다. */
    private BatchResult processClaimedBatch(List<DailyNotificationPreference> claimed) {
        try {
            BatchOutcome outcome = dailyReminderPushNotifier.notifyAll(claimed);
            return new BatchResult(claimed.size(), outcome.targets(), outcome.accepted(), 0);
        } catch (RuntimeException exception) {
            // occurrence는 이미 전진했으므로 이 batch는 그대로 유실된다(자동 재발송 없음).
            log.warn("일일 리마인더 발송 실패: claimed={} exceptionType={}",
                    claimed.size(), exception.getClass().getSimpleName());
            return new BatchResult(claimed.size(), 0, 0, 1);
        }
    }

    private record BatchResult(int claimed, int targets, int accepted, int sendErrors) {
    }

    private static final class RunSummary {

        private final long startedAtNanos = System.nanoTime();
        private int batches;
        private int claimed;
        private int targets;
        private int accepted;
        private int sendErrors;
        private int claimErrors;
        private int workerErrors;

        private synchronized void recordBatch(BatchResult result) {
            batches++;
            claimed += result.claimed();
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
            // 하루 1회 trigger라 발송 0건이어도 남긴다 — 그날 run이 실제로 돌았는지 확인할 다른 수단이 없다.
            log.info("일일 리마인더 worker run 완료: batches={} claimed={} targets={} "
                            + "accepted={} sendErrors={} claimErrors={} workerErrors={} durationMs={}",
                    batches, claimed, targets, accepted, sendErrors, claimErrors,
                    workerErrors, Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000));
        }
    }
}
