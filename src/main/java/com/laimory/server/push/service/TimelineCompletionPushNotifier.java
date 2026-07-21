package com.laimory.server.push.service;

import com.laimory.server.push.PushMessageSender;
import com.laimory.server.push.PushSendResult;
import com.laimory.server.timeline.TaskStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * callback terminal 확정 뒤 task owner의 활성 설치 전체에 완료 푸시를 비동기 best-effort로 발송한다.
 * FCM은 결과의 권위 원천이 아니라 조회를 유도하는 완료 신호다 — 실패·유실은 로그만 남기고 기존 polling·
 * 앱 재진입 동기화가 안전망이다.
 *
 * <p>{@code TimelineCallbackService}의 self-invocation이 아닌 별도 빈이라 {@code @Async} 프록시가 실제로
 * 적용된다(executor는 {@code AsyncConfig}의 Boot 기본 {@code applicationTaskExecutor}). async body의
 * 모든 {@link RuntimeException}(등록 조회·발송·무효 정리)은 여기서 최종 격리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineCompletionPushNotifier {

    private final PushRegistrationService pushRegistrationService;
    private final PushMessageSender pushMessageSender;

    @Async
    public void notifyAsync(long userId, String taskId, TaskStatus status) {
        try {
            List<String> firebaseInstallationIds = pushRegistrationService.findFirebaseInstallationIds(userId);
            if (firebaseInstallationIds.isEmpty()) {
                return;
            }
            PushSendResult result = pushMessageSender.send(taskId, status, firebaseInstallationIds);
            if (!result.invalidFirebaseInstallationIds().isEmpty()) {
                pushRegistrationService.removeInvalidRegistrations(result.invalidFirebaseInstallationIds());
            }
            log.info("timeline completion push: taskId={} status={} targets={} success={} failure={} invalidRemoved={}",
                    taskId, status, result.targetCount(), result.successCount(), result.failureCount(),
                    result.invalidFirebaseInstallationIds().size());
        } catch (RuntimeException e) {
            log.warn("timeline completion push failed (polling이 안전망): taskId={} status={}", taskId, status, e);
        }
    }
}
