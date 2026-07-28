package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 작성 콜백 오케스트레이터 — 결과 graph는 별도 결과 저장 endpoint가 이미 커밋했고, 여기서는 토큰 검증 +
 * 저장 사실 확인 + Redis terminal 전이 + 완료 푸시만 수행한다. 결과를 조립·검증·저장하지 않는다.
 *
 * <p>순서가 load-bearing이다:
 * <ol>
 *   <li>task 로드(없음/만료 → 404)</li>
 *   <li><b>단계 토큰 검증(401 -1002)</b> — SUCCESS는 콜백 토큰(T3), FAILED는 결과 저장 토큰(T2)도 허용한다.
 *       실패 보고는 결과 저장 단계를 거치지 않아 T3를 받을 수 없기 때문이다</li>
 *   <li>terminal task면 <b>같은 결과의 재전송만</b> 성공으로 흘려보내고(멱등), 상충 결과는 409 -1017</li>
 *   <li><b>SUCCESS는 영수증 존재를 확인</b>한다 — 결과 저장 없이 도착한 SUCCESS를 막는 권위 가드다.
 *       토큰 파생 chain은 순서를 강제하지 못하므로(T1 보유자는 T3를 계산할 수 있다) 이 확인이 필수다</li>
 *   <li><b>task owner·dailyRecordId 사용</b> — 콜백은 {@code /s/api}라 request principal이 없으므로 이후
 *       terminal 전이는 task에 저장된 값을 쓴다</li>
 *   <li>FAILED → markFailed(allowlist 분류 코드만 기록), SUCCESS → markSuccess. 그 외 status → 400</li>
 * </ol>
 *
 * <p>one-time 토큰 소비 marker는 두지 않는다 — 응답 유실 후 재시도를 허용해야 하기 때문이다. 같은 terminal
 * 결과의 재전송은 그대로 성공 응답하며, 중복 graph 저장을 막는 책임은 결과 저장 endpoint의 영수증에 있다.
 *
 * <p>terminal 저장 성공 뒤에는 완료 푸시를 비동기 best-effort로 예약한다 — FCM 실패·지연은
 * 콜백 200과 무관하고, 토큰 거절·상충·terminal 저장 실패 경로에서는 예약하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineCallbackService {

    private final TimelineTaskService timelineTaskService;
    private final TimelineAiResultReceiptService timelineAiResultReceiptService;
    private final TimelineCompletionPushNotifier timelineCompletionPushNotifier;
    private final TimelineMetrics timelineMetrics;

    public void handleCallback(String applicationVersion, String taskId,
                               String callbackToken, DraftTaskCallbackRequest request) {
        Timer.Sample sample = timelineMetrics.startCallback();
        try {
            handleCallbackInternal(applicationVersion, taskId, callbackToken, request);
        } finally {
            timelineMetrics.recordCallback(sample);
        }
    }

    private void handleCallbackInternal(String applicationVersion, String taskId,
                                        String callbackToken, DraftTaskCallbackRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineDraftTask task = timelineTaskService.find(taskId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));

        // 1. 단계 토큰 검증. terminal task도 hash를 보존하므로 재콜백도 토큰으로 검증된다.
        boolean callbackTokenMatched = task.matchesCallbackToken(callbackToken);
        boolean resultTokenMatched = task.matchesResultToken(callbackToken);
        if (!callbackTokenMatched && !resultTokenMatched) {
            log.warn("callback token mismatch: taskId={}", taskId);
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }

        if (request.status() != TaskStatus.SUCCESS && request.status() != TaskStatus.FAILED) {
            throw new IllegalArgumentException("invalid callback status: " + request.status());
        }
        // 결과 저장 토큰(T2)은 실패 보고에만 쓸 수 있다 — 실패는 결과 저장 단계를 거치지 않아 T3를 못 받는다.
        if (request.status() == TaskStatus.SUCCESS && !callbackTokenMatched) {
            log.warn("success callback presented result-stage token: taskId={}", taskId);
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
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

        // 3. task owner·결과 record ID 해소 — 콜백엔 request principal이 없으므로 terminal 전이 기준이다.
        long userId = task.userId();
        long dailyRecordId = task.dailyRecordId();
        TimelineDraftTask.TokenHashes tokenHashes = task.tokenHashes();

        // AI가 자신의 실패를 보고한 경우: 분류 코드로 FAILED 기록(draft source는 cleanup이 보관기간 후 정리).
        // 자유 텍스트(error)는 저장하지 않고 로그로만 — 폴링 body.error 유출 경로 차단.
        if (request.status() == TaskStatus.FAILED) {
            finishFailed(taskId, userId, dailyRecordId, resolveAiFailureCode(taskId, request), tokenHashes);
            return;
        }

        // 4. SUCCESS는 결과가 실제로 저장됐을 때만 받는다(저장 없는 SUCCESS 차단 — DB 사실이 권위).
        if (!timelineAiResultReceiptService.exists(taskId)) {
            log.warn("success callback without stored result rejected: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        finishSuccess(taskId, userId, dailyRecordId, tokenHashes);
    }

    private void finishSuccess(String taskId, long userId, long dailyRecordId,
                               TimelineDraftTask.TokenHashes tokenHashes) {
        timelineTaskService.markSuccess(taskId, userId, dailyRecordId, tokenHashes);
        enqueuePushQuietly(taskId, userId, TaskStatus.SUCCESS);
    }

    private void finishFailed(String taskId, long userId, long dailyRecordId, ExceptionType failureType,
                              TimelineDraftTask.TokenHashes tokenHashes) {
        timelineTaskService.markFailed(taskId, userId, dailyRecordId, failureType, tokenHashes);
        enqueuePushQuietly(taskId, userId, TaskStatus.FAILED);
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
