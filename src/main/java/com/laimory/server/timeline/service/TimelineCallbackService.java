package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI callback 오케스트레이터 — 현재 task token과 Redis ProcessStage를 검증한 뒤 terminal 전이와 완료
 * push만 수행한다. 결과를 조립·검증·저장하지 않는다.
 *
 * <p>terminal task의 같은 결과 재전송은 200, 상충 결과는 409다. SUCCESS는 CALLBACK_PENDING,
 * FAILED는 INPUT_PENDING 또는 RESULT_PENDING에서만 허용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineCallbackService {

    private final TimelineTaskService timelineTaskService;
    private final TimelineCompletionPushNotifier timelineCompletionPushNotifier;
    private final TimelineMetrics timelineMetrics;

    public void handleCallback(String applicationVersion, String taskId,
                               String taskToken, DraftTaskCallbackRequest request) {
        Timer.Sample sample = timelineMetrics.startCallback();
        try {
            handleCallbackInternal(applicationVersion, taskId, taskToken, request);
        } finally {
            timelineMetrics.recordCallback(sample);
        }
    }

    private void handleCallbackInternal(String applicationVersion, String taskId,
                                        String taskToken, DraftTaskCallbackRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineDraftTask task = timelineTaskService.find(taskId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));
        if (!task.matchesToken(taskToken)) {
            log.warn("callback token mismatch: taskId={}", taskId);
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }
        if (request.status() != TaskStatus.SUCCESS && request.status() != TaskStatus.FAILED) {
            throw new IllegalArgumentException("invalid callback status: " + request.status());
        }

        if (task.status() != TaskStatus.PROCESSING) {
            if (task.status() == request.status()) {
                log.info("terminal callback replay accepted: taskId={} status={}", taskId, task.status());
                return;
            }
            log.warn("conflicting terminal callback rejected: taskId={} stored={} received={}",
                    taskId, task.status(), request.status());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        if (request.status() == TaskStatus.FAILED) {
            if (task.stage() != ProcessStage.INPUT_PENDING && task.stage() != ProcessStage.RESULT_PENDING) {
                log.warn("failed callback on invalid stage: taskId={} stage={}", taskId, task.stage());
                throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
            }
            if (!timelineTaskService.markFailedIfCurrent(taskId, task, resolveAiFailureCode(taskId, request))) {
                handleCallbackRace(taskId, taskToken, request.status());
                return;
            }
            enqueuePushQuietly(taskId, task.userId(), TaskStatus.FAILED);
            return;
        }

        if (task.stage() != ProcessStage.CALLBACK_PENDING) {
            log.warn("success callback on invalid stage: taskId={} stage={}", taskId, task.stage());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        if (!timelineTaskService.markSuccessIfCurrent(taskId, task)) {
            handleCallbackRace(taskId, taskToken, request.status());
            return;
        }
        enqueuePushQuietly(taskId, task.userId(), TaskStatus.SUCCESS);
    }

    /** callback CAS 경합 뒤 최신 terminal 상태가 같은 결과면 멱등 성공, 나머지는 상충이다. */
    private void handleCallbackRace(String taskId, String taskToken, TaskStatus requestedStatus) {
        TimelineDraftTask latest = timelineTaskService.find(taskId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));
        if (!latest.matchesToken(taskToken)) {
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }
        if (latest.status() == requestedStatus) {
            log.info("terminal callback race resolved as replay: taskId={} status={}", taskId, requestedStatus);
            return;
        }
        log.warn("callback token race rejected: taskId={} stored={} requested={}",
                taskId, latest.status(), requestedStatus);
        throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
    }

    /** terminal 확정 뒤 완료 push를 비동기 best-effort로 예약한다. */
    private void enqueuePushQuietly(String taskId, long userId, TaskStatus status) {
        try {
            timelineCompletionPushNotifier.notifyAsync(userId, taskId, status);
        } catch (RuntimeException e) {
            log.warn("completion push enqueue failed (polling is fallback): taskId={} status={} detail={}",
                    taskId, status, e.getMessage());
        }
    }

    /** 미지 실패 코드는 task를 PROCESSING에 가두지 않고 AI_REPORTED_FAILURE로 수렴한다. */
    private ExceptionType resolveAiFailureCode(String taskId, DraftTaskCallbackRequest request) {
        Integer requested = request.errorCode();
        boolean known = requested != null && requested == ExceptionType.AI_REPORTED_FAILURE.code();
        if (requested != null && !known) {
            log.warn("unknown ai failure code, falling back: taskId={} requested={}", taskId, requested);
        }
        ExceptionType type = ExceptionType.AI_REPORTED_FAILURE;
        log.warn("ai reported failure: taskId={} code={} detail={}", taskId, type.code(), request.error());
        return type;
    }
}
