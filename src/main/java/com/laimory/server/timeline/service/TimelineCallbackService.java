package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import com.laimory.server.timeline.TaskStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 작성 콜백 오케스트레이터 — 단일 task token과 Redis stage를 검증한 뒤 terminal 전이와 완료 푸시만
 * 수행한다. 결과를 조립·검증·저장하지 않는다.
 *
 * <p>순서가 load-bearing이다:
 * <ol>
 *   <li>task 로드(없음/만료 → 404)</li>
 *   <li><b>단일 task token 검증(401 -1002)</b></li>
 *   <li>terminal task면 <b>같은 결과의 재전송만</b> 성공으로 흘려보내고(멱등), 상충 결과는 409 -1017</li>
 *   <li>SUCCESS는 {@code CALLBACK_PENDING}, FAILED는 결과 저장 전 stage에서만 허용한다</li>
 *   <li><b>task owner·dailyRecordId 사용</b> — 콜백은 {@code /s/api}라 request principal이 없으므로 이후
 *       terminal 전이는 task에 저장된 값을 쓴다</li>
 *   <li>FAILED → markFailed(allowlist 분류 코드만 기록), SUCCESS → markSuccess. 그 외 status → 400</li>
 * </ol>
 *
 * <p>같은 terminal 결과의 재전송은 그대로 성공 응답한다.
 *
 * <p>terminal 저장 성공 뒤에는 완료 푸시를 비동기 best-effort로 예약한다 — FCM 실패·지연은
 * 콜백 200과 무관하고, 토큰 거절·상충·terminal 저장 실패 경로에서는 예약하지 않는다.
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

        // 1. 단일 task token 검증. terminal task도 hash를 보존하므로 재콜백도 검증된다.
        if (!task.matchesToken(taskToken)) {
            log.warn("callback token mismatch: taskId={}", taskId);
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }

        if (request.status() != TaskStatus.SUCCESS && request.status() != TaskStatus.FAILED) {
            throw new IllegalArgumentException("invalid callback status: " + request.status());
        }
        // 2. 이미 종결된 task: 같은 결과의 재전송은 멱등 성공, 상충 결과는 거절한다.
        if (task.status() != TaskStatus.PROCESSING) {
            if (task.status() == request.status()) {
                log.info("terminal callback replay accepted: taskId={} status={}", taskId, task.status());
                return;
            }
            log.warn("conflicting terminal callback rejected: taskId={} stored={} received={}",
                    taskId, task.status(), request.status());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        // 3. AI가 자신의 실패를 보고한 경우: 결과 저장 전 stage에서만 FAILED로 종결한다.
        // 자유 텍스트(error)는 저장하지 않고 로그로만 — 폴링 body.error 유출 경로 차단.
        if (request.status() == TaskStatus.FAILED) {
            if (task.stage() != TaskStage.INPUT_PENDING && task.stage() != TaskStage.RESULT_PENDING) {
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

        // 4. SUCCESS는 결과 transaction 뒤 Redis가 CALLBACK_PENDING까지 전이한 경우에만 받는다.
        if (task.stage() != TaskStage.CALLBACK_PENDING) {
            log.warn("success callback on invalid stage: taskId={} stage={}", taskId, task.stage());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        if (!timelineTaskService.markSuccessIfCurrent(taskId, task)) {
            handleCallbackRace(taskId, taskToken, request.status());
            return;
        }
        enqueuePushQuietly(taskId, task.userId(), TaskStatus.SUCCESS);
    }

    /** callback 사이의 CAS 경합 뒤 최신 terminal 상태가 같은 결과면 멱등 성공, 나머지는 상충이다. */
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
        log.warn("callback stage race rejected: taskId={} stored={} requested={} stage={}",
                taskId, latest.status(), requestedStatus, latest.stage());
        throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
    }

    /**
     * terminal 확정 뒤 완료 푸시를 비동기 best-effort로 예약한다. 실패해도 콜백 200을 보존한다 —
     * executor 제출 예외까지 삼킨다(FCM은 조회를 유도하는 완료 신호일 뿐, polling이 권위·안전망).
     * finishSuccess/finishFailed 뒤에서만 호출되므로 terminal 저장 실패·토큰 거절 경로엔 알림이 없다.
     */
    private void enqueuePushQuietly(String taskId, long userId, TaskStatus status) {
        try {
            timelineCompletionPushNotifier.notifyAsync(userId, taskId, status);
        } catch (RuntimeException e) {
            log.warn("completion push enqueue failed (polling이 안전망): taskId={} status={} detail={}",
                    taskId, status, e.getMessage());
        }
    }

    /**
     * AI가 보고한 실패 코드를 해석한다. 허용 목록 밖(null 포함)이면
     * {@link ExceptionType#AI_REPORTED_FAILURE}로 폴백 —
     * 코드 불일치로 콜백을 400으로 튕기면 task가 PROCESSING에 갇히므로 관대하게 받는다.
     * 진단용 자유 텍스트({@code error})는 저장하지 않고 로그로만 남긴다(자유 텍스트는 우리 AI 서버가 계약대로 채운다).
     */
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
