package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI callback 오케스트레이터 — 현재 task token과 Redis ProcessStage를 검증한 뒤 terminal 전이와 완료
 * push만 수행한다. 결과를 조립·검증·저장하지 않는다.
 *
 * <p>terminal task의 같은 결과 재전송은 200, 상충 결과는 409다. SUCCESS는 CALLBACK_PENDING,
 * FAILED는 INPUT_PENDING 또는 선점되지 않은 RESULT_PENDING에서만 허용한다 — result 선점
 * ({@code retryReceipt.claimed()}) 중 FAILED는 409다. 선점은 token/stage를 바꾸지 않아 기존 검증만으로는
 * transaction 진행 중을 구분하지 못하는데, 여기서 terminal을 먼저 확정하면 commit 회전의 native
 * {@code SET XX}가 terminal 위에 PROCESSING을 되쓴다.
 *
 * <p>terminal 저장은 native {@code SET XX PX 24시간}이고 성공 뒤에만 index 제거·push enqueue를 실행한다.
 * write 실패는 검증 뒤 task 만료뿐이라 404로 수렴하며 만료 task를 부활시키지 않는다.
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
            if (task.stage() == ProcessStage.RESULT_PENDING
                    && task.retryReceipt() != null && task.retryReceipt().claimed()) {
                // result 선점 중 — 아직 살아 있는 transaction의 commit 회전·선점 해제보다 terminal을
                // 먼저 확정하면 후속 SET XX가 terminal 위에 PROCESSING을 되쓴다. 선점 뒤 crash한 task는
                // 기존 계약대로 TTL 만료(404)로 끝난다.
                log.warn("failed callback while result claimed: taskId={}", taskId);
                throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
            }
            if (!timelineTaskService.markFailedIfPresent(taskId, task, resolveAiFailureCode(taskId, request))) {
                log.warn("failed callback task expired before terminal write: taskId={}", taskId);
                throw new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND);
            }
            enqueuePushQuietly(taskId, task.subjectId(), TaskStatus.FAILED);
            return;
        }

        if (task.stage() != ProcessStage.CALLBACK_PENDING) {
            log.warn("success callback on invalid stage: taskId={} stage={}", taskId, task.stage());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        if (!timelineTaskService.markSuccessIfPresent(taskId, task)) {
            log.warn("success callback task expired before terminal write: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND);
        }
        enqueuePushQuietly(taskId, task.subjectId(), TaskStatus.SUCCESS);
    }

    /** terminal 확정 뒤 완료 push를 비동기 best-effort로 예약한다. */
    private void enqueuePushQuietly(String taskId, UUID subjectId, TaskStatus status) {
        try {
            timelineCompletionPushNotifier.notifyAsync(subjectId, taskId, status);
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
        // 자유 text error는 사용자 원문이 섞일 수 있어 로그에 남기지 않는다 — bounded numeric code만 관측한다.
        log.warn("ai reported failure: taskId={} code={}", taskId, type.code());
        return type;
    }
}
