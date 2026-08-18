package com.laimory.server.push.service;

import com.laimory.server.push.PushMessage;
import com.laimory.server.push.PushMessageSender;
import com.laimory.server.push.PushMessageType;
import com.laimory.server.push.PushMetrics;
import com.laimory.server.push.PushSendResult;
import com.laimory.server.push.PushTarget;
import com.laimory.server.timeline.TaskStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * callback terminal 확정 뒤 task owner의 활성 설치 전체에 완료 푸시를 비동기 best-effort로 발송한다.
 * FCM은 결과의 권위 원천이 아니라 조회를 유도하는 완료 신호다 — 실패·유실은 로그만 남기고 기존 polling·
 * 앱 재진입 동기화가 안전망이다.
 *
 * <p>전체 푸시 마스터가 OFF면 FID 조회 전에 끝낸다. 마스터 행이 아직 없는 rollout 창에서는 기존 동작을
 * 보존하려고 ON으로 읽는다 — 이 알림은 사용자가 직접 시작한 작업의 결과인 정보성 통지라 광고 동의
 * gate를 적용하지 않는다. DB 조회 장애는 ON으로 숨기지 않고 아래 격리에서 실패로 처리된다.
 *
 * <p>{@code TimelineCallbackService}의 self-invocation이 아닌 별도 빈이라 {@code @Async} 프록시가 실제로
 * 적용된다(executor는 {@code AsyncConfig}의 Boot 기본 {@code applicationTaskExecutor}). async body의
 * 모든 {@link RuntimeException}(설정 조회·등록 조회·발송·무효 정리)은 여기서 최종 격리한다.
 *
 * <p>무효 등록 정리는 발송 대상을 조회한 snapshot 시각으로 조건부 삭제한다 — snapshot 이후 같은 FID로
 * 갱신된 정상 재등록을 지연 도착한 무효 응답이 지우지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineCompletionPushNotifier {

    private final PushRegistrationService pushRegistrationService;
    private final PushPreferenceService pushPreferenceService;
    private final PushMessageSender pushMessageSender;
    private final PushMetrics pushMetrics;
    private final Clock clock;

    @Async
    public void notifyAsync(UUID subjectId, String taskId, TaskStatus status) {
        try {
            if (!pushPreferenceService.isPushEnabledForLegacyCompatibility(subjectId)) {
                return;
            }
            // snapshot은 조회보다 먼저 캡처한다 — 조회 결과의 어떤 행도 snapshot보다 나중에 재등록됐다면
            // (lastRegisteredAt > snapshotAt) 무효 정리에서 보호된다.
            LocalDateTime snapshotAt = LocalDateTime.now(clock);
            List<String> firebaseInstallationIds = pushRegistrationService.findFirebaseInstallationIds(subjectId);
            if (firebaseInstallationIds.isEmpty()) {
                return;
            }
            List<PushTarget> targets = firebaseInstallationIds.stream().map(PushTarget::informational).toList();
            PushSendResult result = pushMessageSender.send(
                    PushMessage.timelineCompletion(taskId, status.name()), targets);
            // 발송 결과는 invalid registration DB 정리와 독립된 사실이다. 정리 실패 전에 먼저 기록한다.
            pushMetrics.record(PushMessageType.TIMELINE_COMPLETION, result);
            // targets는 sender 결과가 아니라 조회한 FID 수 — noop sender는 0을 보고하므로 여기서 세야 진실이다.
            // accepted는 FCM 접수 성공이며 단말 수신·노출 성공을 뜻하지 않는다.
            log.info("timeline completion push result: taskId={} taskStatus={} targets={} "
                            + "accepted={} failed={} invalidTargets={}",
                    taskId, status, firebaseInstallationIds.size(), result.successCount(), result.failureCount(),
                    result.invalidFirebaseInstallationIds().size());
            if (!result.invalidFirebaseInstallationIds().isEmpty()) {
                pushRegistrationService.removeInvalidRegistrations(
                        result.invalidFirebaseInstallationIds(), snapshotAt);
            }
        } catch (RuntimeException e) {
            log.warn("timeline completion push failed (polling이 안전망): taskId={} status={}", taskId, status, e);
        }
    }
}
