package com.laimory.server.timeline.service;

import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.redis.RedisKeyExpirationListener;
import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.CreditRefundReason;
import com.laimory.server.credit.service.CreditService;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * draft task 유실 처리(#565) — 유실 타이머 만료 알림의 수신자. 결과 저장·환불 경로는 타이머를 지우므로, 만료는
 * 응답 없이 처리 기한(3분)이 지난 task에서만 온다.
 *
 * <p>환경 안의 모든 서버가 같은 알림을 받는다 — 유실 처리 잠금({@code SET NX})을 잡은 서버만 처리한다. 잠금은
 * TTL로만 풀리고 재처리는 없다: 잡은 서버의 DB 작업이 실패하면 크레딧 이력이 {@code CHARGED}로 남아 수동 환불한다.
 *
 * <p>환불이 실제로 일어났을 때만(이력이 {@code CHARGED}였을 때) task를 읽어 다음을 진행한다 — 이미 소비·환불됐으면 모두 건너뛰어
 * 결과 저장 뒤 타이머 삭제가 실패해 온 알림이 성공한 생성을 실패로 덮거나 실패 push를 보내지 않게 한다.
 * task key는 처리 기한보다 오래 살아({@link TimelineTaskService#PROCESSING_TTL}) 이 시점에 아직 읽힌다(#593):
 * FAILED callback과 같은 순서로 record가 활성 Event 없는 DRAFT면 {@code ai_failed}로 soft delete하고, task를 FAILED로
 * 종결한 뒤 실패 push를 보낸다. task가 이미 없으면(배포 중 옛 서버가 만든 task·알림 지연) 환불과 로그만 남긴다.
 * 결과 저장이 처리 기한 경계에서 진행 중인 task를 가려내지 않는다 — AI 처리 실측 2분 이내라 그 경합을 수용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineTaskLossHandler implements RedisKeyExpirationListener {

    private final TimelineTaskLossTimerService timelineTaskLossTimerService;
    private final CreditService creditService;
    private final TimelineTaskService timelineTaskService;
    private final DailyRecordService dailyRecordService;
    private final TimelineCompletionPushNotifier timelineCompletionPushNotifier;

    @Override
    public String logicalKeyPrefix() {
        return timelineTaskLossTimerService.timerKeyPrefix();
    }

    @Override
    public void onExpired(String logicalKey) {
        Optional<String> taskId = timelineTaskLossTimerService.taskIdOf(logicalKey);
        if (taskId.isEmpty() || !timelineTaskLossTimerService.tryLock(taskId.get())) {
            return;
        }
        try {
            Optional<UUID> refunded = creditService.refund(
                    CreditCostType.TIMELINE_CREATION, taskId.get(), CreditRefundReason.TIMEOUT);
            if (refunded.isEmpty()) {
                // 정상 종결(소비·환불)한 경로는 타이머를 지운다 — 만료가 왔다면 그 삭제가 빠진 것이다.
                log.warn("loss timer expired but credit charge already settled (timer cancel missed): taskId={}",
                        taskId.get());
                return;
            }
            // task 조회는 환불 뒤에 한다 — Redis read·역직렬화가 실패해도 환불은 지켜지고 정리·FAILED만 빠진다.
            Optional<TimelineDraftTask> task = timelineTaskService.find(taskId.get());
            if (task.isEmpty()) {
                log.warn("lost timeline task refunded but task already gone (빈 DRAFT 잔존 가능): taskId={}",
                        taskId.get());
                return;
            }
            failLostTask(taskId.get(), task.get());
        } catch (RuntimeException e) {
            log.error("lost timeline task handling failed (환불 전 실패면 이력 CHARGED 잔존 — 수동 환불): taskId={}",
                    taskId.get(), e);
        }
    }

    private void failLostTask(String taskId, TimelineDraftTask task) {
        log.warn("lost timeline task refunded: taskId={}", taskId);
        softDeleteFailedDraftQuietly(taskId, task.dailyRecordId());
        // 이미 FAILED인 task(dispatch 거절 뒤 환불만 실패해 타이머가 남은 경우)는 원래 실패 코드를 유지한다.
        if (task.status() == TaskStatus.PROCESSING
                && !timelineTaskService.markFailedIfPresent(taskId, task, ExceptionType.AI_REPORTED_FAILURE)) {
            log.warn("lost timeline task expired before FAILED write: taskId={}", taskId);
            return;
        }
        timelineCompletionPushNotifier.notifyAsync(task.subjectId(), taskId, TaskStatus.FAILED);
    }

    /** 삭제 실패는 FAILED 종결·push를 막지 않도록 로그만 남긴다(빈 DRAFT 잔존). */
    private void softDeleteFailedDraftQuietly(String taskId, long dailyRecordId) {
        try {
            dailyRecordService.softDeleteFailedDraft(dailyRecordId);
        } catch (RuntimeException e) {
            log.warn("lost task draft soft delete failed (빈 DRAFT 잔존): taskId={} dailyRecordId={}",
                    taskId, dailyRecordId, e);
        }
    }
}
