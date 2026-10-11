package com.laimory.server.timeline.service;

import com.laimory.server.common.redis.RedisKeyExpirationListener;
import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.CreditRefundReason;
import com.laimory.server.credit.service.CreditService;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import com.laimory.server.timeline.TaskStatus;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 타임라인 생성 차감 타이머 만료 = task 유실 처리(#565). 결과 저장·실패 보고 경로는 타이머를 지우므로, 만료는
 * 응답 없이 PROCESSING TTL이 지난 task에서만 온다.
 *
 * <p>환경 안의 모든 서버가 같은 알림을 받는다 — 환불 잠금({@code SET NX})을 잡은 서버만 처리한다. 잠금은 TTL로만
 * 풀리고 재처리는 없다: 잡은 서버의 DB 작업이 실패하면 기록이 {@code CHARGED}로 남아 수동 환불한다.
 *
 * <p>환불이 실제로 일어났을 때만(기록이 {@code CHARGED}였을 때) task를 FAILED로 기록하고 실패 push를 보낸다.
 * 이미 소비·환불됐으면 셋 다 건너뛴다 — 결과 저장 뒤 타이머 삭제가 실패해 온 알림이 성공한 생성을 실패로 덮거나
 * 실패 push를 보내지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditChargeExpirationHandler implements RedisKeyExpirationListener {

    private final CreditService creditService;
    private final TimelineTaskService timelineTaskService;
    private final TimelineCompletionPushNotifier timelineCompletionPushNotifier;

    @Override
    public String logicalKeyPrefix() {
        return CreditService.chargeTimerKeyPrefix();
    }

    @Override
    public void onExpired(String logicalKey) {
        Optional<String> taskId = CreditService.chargeTimerReferenceIdOf(logicalKey);
        if (taskId.isEmpty() || !creditService.tryLockTimeoutRefund(taskId.get())) {
            return;
        }
        try {
            creditService.refund(CreditCostType.TIMELINE_CREATION, taskId.get(), CreditRefundReason.TIMEOUT)
                    .ifPresent(subjectId -> failLostTask(taskId.get(), subjectId));
        } catch (RuntimeException e) {
            log.error("lost timeline task handling failed (환불 전 실패면 기록 CHARGED 잔존 — 수동 환불): taskId={}",
                    taskId.get(), e);
        }
    }

    private void failLostTask(String taskId, UUID subjectId) {
        log.warn("lost timeline task refunded: taskId={}", taskId);
        if (!timelineTaskService.markLostFailedIfAbsent(taskId, subjectId)) {
            log.warn("lost timeline task already has a value, FAILED not written: taskId={}", taskId);
        }
        timelineCompletionPushNotifier.notifyAsync(subjectId, taskId, TaskStatus.FAILED);
    }
}
