package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 작성 콜백 오케스트레이터 — direct-write 계약에서 서버는 finalize를 하지 않는다. AI가 final
 * Event/Item/junction 저장과 accepted source 삭제를 자신의 transaction으로 commit한 <b>뒤에만</b> 콜백을
 * 보내므로, 여기서는 token 검증·소비 + owner 확인 + Redis terminal 전이 + 완료 푸시만
 * 수행한다. 결과 graph를 조립·검증·저장하지 않는다.
 *
 * <p>순서가 load-bearing이다:
 * <ol>
 *   <li>task 로드(없음/만료 → 404)</li>
 *   <li><b>토큰 검증(401 -1002)</b> — terminal task도 해시를 보존하므로 재콜백도 토큰으로 검증된다</li>
 *   <li><b>토큰 원자 소비</b> — Redis SET NX marker를 선점한 요청 하나만 통과한다. 이미 소비됐으면
 *       401 -1012이며 terminal 전이·push에 도달하지 않는다</li>
 *   <li>terminal 안전망 — callback 외부 경로에서 종결되어 marker가 없는 terminal task도 이 요청에서
 *       token을 소비한 뒤 -1012로 거부한다</li>
 *   <li><b>task owner·dailyRecordId 사용</b> — 콜백은 {@code /s/api}라 request principal이 없으므로 이후
 *       terminal 전이는 task에 저장된 값을 쓴다</li>
 *   <li>FAILED → markFailed(allowlist 분류 코드만 기록), SUCCESS → markSuccess. 그 외 status → 400</li>
 * </ol>
 *
 * <p>token은 처리 완료가 아니라 인증 시점에 소비하며, 이후 검증·terminal 저장이 실패해도 marker를
 * 삭제하거나 환불하지 않는다. 따라서 이 경계는 exactly-once 처리가 아니라 at-most-once admission을
 * 보장한다. 같은 token 재시도로 복구할 수 없는 문제는 별도 복구 과제다.
 *
 * <p>terminal 저장 성공 뒤에는 완료 푸시를 비동기 best-effort로 예약한다 — FCM 실패·지연은
 * 콜백 200과 무관하고, terminal 안전망·토큰 거절·terminal 저장 실패 경로에서는 예약하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineCallbackService {

    private final TimelineTaskService timelineTaskService;
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

        // 1. 토큰 검증을 먼저 한다. terminal task도 해시를 보존하므로 재콜백도 토큰으로 검증된다.
        if (!CallbackTokens.matches(callbackToken, task.callbackTokenHash())) {
            throw new BusinessException(ExceptionType.CALLBACK_TOKEN_MISMATCH);
        }

        // 2. 유효한 token은 인증 시점에 원자 소비한다. 소비 후 실패에도 marker를 환불하지 않는다.
        if (!timelineTaskService.consumeCallbackToken(taskId)) {
            log.warn("callback token already consumed: taskId={}", taskId);
            throw new BusinessException(ExceptionType.CALLBACK_TOKEN_ALREADY_CONSUMED);
        }

        // 3. callback 외부 경로에서 종결되어 marker가 없는 task의 재처리를 막는 안전망.
        if (task.status() != TaskStatus.PROCESSING) {
            log.warn("terminal task rejected after callback token consumption: taskId={} status={}",
                    taskId, task.status());
            throw new BusinessException(ExceptionType.CALLBACK_TOKEN_ALREADY_CONSUMED);
        }

        // 4. task owner·결과 record ID 해소 — 콜백엔 request principal이 없으므로 terminal 전이 기준이다.
        long userId = task.userId();
        long dailyRecordId = task.dailyRecordId();
        String callbackTokenHash = task.callbackTokenHash();

        // AI가 자신의 실패를 보고한 경우: 분류 코드로 FAILED 기록(draft source는 cleanup이 보관기간 후 정리).
        // 자유 텍스트(error)는 저장하지 않고 로그로만 — 폴링 body.error 유출 경로 차단.
        if (request.status() == TaskStatus.FAILED) {
            finishFailed(taskId, userId, dailyRecordId, resolveAiFailureCode(taskId, request), callbackTokenHash);
            return;
        }
        if (request.status() != TaskStatus.SUCCESS) {
            throw new IllegalArgumentException("invalid callback status: " + request.status());
        }

        // 5. SUCCESS: AI가 이미 final commit을 마친 상태다 — Redis 전이만 기록한다(결과 검증·조립·저장 없음).
        finishSuccess(taskId, userId, dailyRecordId, callbackTokenHash);
    }

    private void finishSuccess(String taskId, long userId, long dailyRecordId, String callbackTokenHash) {
        timelineTaskService.markSuccess(taskId, userId, dailyRecordId, callbackTokenHash);
        enqueuePushQuietly(taskId, userId, TaskStatus.SUCCESS);
    }

    private void finishFailed(String taskId, long userId, long dailyRecordId, ExceptionType failureType,
                              String callbackTokenHash) {
        timelineTaskService.markFailed(taskId, userId, dailyRecordId, failureType, callbackTokenHash);
        enqueuePushQuietly(taskId, userId, TaskStatus.FAILED);
    }

    /**
     * terminal 확정 뒤 완료 푸시를 비동기 best-effort로 예약한다. 실패해도 콜백 200을 보존한다 —
     * executor 제출 예외까지 삼킨다(FCM은 조회를 유도하는 완료 신호일 뿐, polling이 권위·안전망).
     * finishSuccess/finishFailed 뒤에서만 호출되므로 terminal 저장 실패·token 거절 경로엔 알림이 없다.
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
