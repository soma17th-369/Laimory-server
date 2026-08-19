package com.laimory.server.push.service;

import com.laimory.server.push.PushMessage;
import com.laimory.server.push.PushMessageSender;
import com.laimory.server.push.PushMetrics;
import com.laimory.server.push.PushSendResult;
import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * claim된 일일 리마인더 occurrence들을 실제 발송 대상으로 좁히고 FCM에 넘긴다. DB claim transaction 밖에서
 * 실행되며 여기서 실패해도 occurrence는 이미 다음 날로 옮겨져 있다(at-most-once best-effort).
 *
 * <p>발송 대상은 전체 마스터가 ON인 subject의 활성 FID다. 마스터 행이 없는 subject는 추정하지 않고
 * 제외한다 — 이 알림은 rollout 공백을 ON으로 읽는 기존 완료 통지와 다르다.
 *
 * <p>로그에는 FID·subjectId를 남기지 않고 batch 단위 집계만 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReminderPushNotifier {

    private static final ScheduledNotificationType TYPE = ScheduledNotificationType.DAILY_REMINDER;

    private final PushPreferenceService pushPreferenceService;
    private final PushRegistrationService pushRegistrationService;
    private final PushMessageSender pushMessageSender;
    private final PushMetrics pushMetrics;
    private final Clock clock;

    /**
     * 발송 대상 occurrence들을 처리한다. 지연 초과로 건너뛸 occurrence는 호출자가 이미 제외한 뒤 넘긴다.
     *
     * @return batch 집계(로그·run summary용)
     */
    public BatchOutcome notifyAll(List<ScheduledNotificationPreference> deliverable) {
        if (deliverable.isEmpty()) {
            return BatchOutcome.empty();
        }
        List<UUID> subjectIds = ScheduledNotificationPreferenceService.subjectIdsOf(deliverable);
        Map<UUID, Boolean> masters = pushPreferenceService.findPushEnabledBySubjectIds(subjectIds);
        List<UUID> eligible = subjectIds.stream()
                // 마스터 행이 없으면 제외한다 — 예정 발송에 기본값을 추정하지 않는다.
                .filter(subjectId -> Boolean.TRUE.equals(masters.get(subjectId)))
                .toList();
        if (eligible.isEmpty()) {
            return new BatchOutcome(subjectIds.size(), 0, 0, 0);
        }

        // snapshot은 조회보다 먼저 캡처한다 — 이후 같은 FID로 갱신된 재등록이 무효 정리에서 보호된다.
        LocalDateTime snapshotAt = LocalDateTime.now(clock);
        List<String> targets = pushRegistrationService.findFirebaseInstallationIdsBySubjectIds(eligible);
        if (targets.isEmpty()) {
            return new BatchOutcome(subjectIds.size(), eligible.size(), 0, 0);
        }

        PushSendResult result = pushMessageSender.send(PushMessage.dailyReminder(), targets);
        pushMetrics.record(TYPE.pushMessageType(), result);
        // claimed가 아니라 deliverable — worker 로그의 claimed(지연 skip 포함)와 다른 값이라 라벨을 나눈다.
        log.info("daily reminder push result: deliverableSubjects={} eligibleSubjects={} targets={} accepted={} "
                        + "failed={} invalidTargets={}",
                subjectIds.size(), eligible.size(), targets.size(), result.successCount(), result.failureCount(),
                result.invalidFirebaseInstallationIds().size());
        if (!result.invalidFirebaseInstallationIds().isEmpty()) {
            pushRegistrationService.removeInvalidRegistrations(
                    result.invalidFirebaseInstallationIds(), snapshotAt);
        }
        return new BatchOutcome(subjectIds.size(), eligible.size(), targets.size(), result.successCount());
    }

    /** batch 집계 — 개수만 담는다(식별자 없음). */
    public record BatchOutcome(int deliverableSubjects, int eligibleSubjects, int targets, int accepted) {

        static BatchOutcome empty() {
            return new BatchOutcome(0, 0, 0, 0);
        }
    }
}
