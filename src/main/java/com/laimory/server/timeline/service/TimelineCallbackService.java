package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 작성 콜백 오케스트레이터 — direct-write 계약에서 서버는 finalize를 하지 않는다. AI가 final
 * Event/Item/junction 저장과 accepted source 삭제를 자신의 transaction으로 commit한 <b>뒤에만</b> 콜백을
 * 보내므로, 여기서는 token 검증 + owner 확인 + Redis terminal 전이 + guard 해제 + 완료 푸시만 수행한다.
 * 결과 graph를 조립·검증·저장하지 않는다.
 *
 * <p>순서가 load-bearing이다:
 * <ol>
 *   <li>task 로드(없음/만료 → 404)</li>
 *   <li><b>토큰 검증(401 ERROR_1002)</b> — terminal task도 해시를 보존하므로 재콜백도 토큰으로 검증된다</li>
 *   <li>terminal이면 idempotent no-op 200 — AI callback은 commit 후 네트워크 오류로 반복될 수 있고(at-least-once),
 *       direct-write 구조에선 서버가 재처리할 것이 없어 유효한 동일 콜백을 안전하게 흡수한다.
 *       (과거 token-use 카운터는 제거 — terminal 저장 실패 뒤 정당한 재콜백까지 401로 막아 복구를 불가능하게 했다)</li>
 *   <li><b>task owner·dailyRecordId 확인</b> — 콜백은 {@code /s/api}라 request principal이 없으므로 이후 전이·guard
 *       해제는 task에 저장된 값을 쓴다. owner나 dailyRecordId가 없는 legacy task는 추정하지 않고 404로 fail-closed</li>
 *   <li>FAILED → markFailed(allowlist 분류 코드만 기록), SUCCESS → markSuccess. 그 외 status → 400</li>
 * </ol>
 *
 * <p>모든 terminal 전이(markSuccess/markFailed)는 저장 <b>성공 직후</b> 날짜 guard를 compare-and-release한다
 * (해제 경계 규칙 ③ — {@link TimelineTaskService} 참고). 날짜는 task의 dailyRecordId로 DailyRecord를 조회해
 * 얻는다 — record가 이미 없으면 다른 날짜를 추정하지 않고 guard TTL 만료에 맡긴다. terminal 저장이 실패하면
 * 해제하지 않고 TTL(1h) 만료에 맡기며(규칙 ②), AI의 콜백 재시도가 멱등 게이트를 통과해 전이를 복구한다.
 *
 * <p>terminal 확정(저장+guard 해제 시도) 뒤에는 완료 푸시를 비동기 best-effort로 예약한다 — FCM 실패·지연은
 * 콜백 200과 무관하고, 이미 terminal인 멱등 단축·토큰 거절·terminal 저장 실패 경로에서는 예약하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineCallbackService {

    /** AI가 콜백으로 보고할 수 있는 실패 코드 허용 목록(당분간 ERROR_1008 하나 — 확장 시 여기에 추가). */
    private static final Set<String> AI_FAILURE_CODES = Set.of(ErrorCode.ERROR_1008.name());

    private final TimelineTaskService timelineTaskService;
    private final DailyRecordService dailyRecordService;
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

        // 2. 멱등: 이미 종결(SUCCESS/FAILED)된 task면 재처리하지 않는다 — AI 재콜백(at-least-once)의 정상 흡수 경로.
        if (task.status() != TaskStatus.PROCESSING) {
            return;
        }

        // 3. task owner·결과 record ID 해소 — 콜백엔 request principal이 없으므로 이후 전이·guard 해제 기준이다.
        //    없는 legacy task(구 shape)는 추정하지 않고 404로 fail-closed(전이 없이 TTL 만료에 맡김).
        if (task.userId() == null || task.dailyRecordId() == null) {
            log.warn("legacy task without owner/dailyRecordId, refusing callback: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND);
        }
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

        // 4. SUCCESS: AI가 이미 final commit을 마친 상태다 — Redis 전이만 기록한다(결과 검증·조립·저장 없음).
        finishSuccess(taskId, userId, dailyRecordId, callbackTokenHash);
    }

    /** terminal 저장 성공 직후에만 guard를 해제한다(규칙 ③) — markSuccess가 던지면 여기 못 와 규칙 ②가 자동 준수된다. */
    private void finishSuccess(String taskId, long userId, long dailyRecordId, String callbackTokenHash) {
        timelineTaskService.markSuccess(taskId, userId, dailyRecordId, callbackTokenHash);
        releaseDateGuardQuietly(taskId, userId, dailyRecordId);
        enqueuePushQuietly(taskId, userId, TaskStatus.SUCCESS);
    }

    private void finishFailed(String taskId, long userId, long dailyRecordId, ErrorCode failureCode,
                              String callbackTokenHash) {
        timelineTaskService.markFailed(taskId, userId, dailyRecordId, failureCode, callbackTokenHash);
        releaseDateGuardQuietly(taskId, userId, dailyRecordId);
        enqueuePushQuietly(taskId, userId, TaskStatus.FAILED);
    }

    /**
     * terminal 확정 뒤 완료 푸시를 비동기 best-effort로 예약한다. guard 해제처럼 실패해도 콜백 200을
     * 보존한다 — executor 제출 예외까지 삼킨다(FCM은 조회를 유도하는 완료 신호일 뿐, polling이 권위·안전망).
     * finishSuccess/finishFailed 뒤에서만 호출되므로 terminal 저장 실패·멱등 단축 경로엔 알림이 없다.
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
     * guard 해제는 best-effort다 — terminal 상태는 이미 확정됐고 실패해도 TTL(1h)이 자연 해제하는 안전망이
     * 있으므로, 해제 실패로 콜백을 500으로 만들지 않는다. 날짜는 task에 없으므로(Redis shape 축소) 결과
     * record에서 얻는다 — record가 없거나 owner가 다르면 다른 날짜를 추정하지 않고 TTL 만료에 맡긴다.
     */
    private void releaseDateGuardQuietly(String taskId, long userId, long dailyRecordId) {
        try {
            Optional<LocalDate> recordDate = dailyRecordService.findById(dailyRecordId)
                    .filter(record -> record.getUserId() == userId)
                    .map(record -> record.getRecordDate());
            if (recordDate.isEmpty()) {
                log.warn("date guard release skipped, record missing (TTL로 자연 해제 예정): taskId={}", taskId);
                return;
            }
            timelineTaskService.releaseDateGuard(userId, recordDate.get(),
                    TimelineTaskService.taskGuardHolder(taskId));
        } catch (RuntimeException e) {
            log.warn("date guard release failed (TTL로 자연 해제 예정): taskId={} detail={}", taskId, e.getMessage());
        }
    }

    /**
     * AI가 보고한 실패 코드를 해석한다. 허용 목록 밖(null 포함)이면 {@link ErrorCode#ERROR_1008} 폴백 —
     * 코드 불일치로 콜백을 400으로 튕기면 task가 PROCESSING에 갇히므로 관대하게 받는다.
     * 진단용 자유 텍스트({@code error})는 저장하지 않고 로그로만 남긴다(자유 텍스트는 우리 AI 서버가 계약대로 채운다).
     */
    private ErrorCode resolveAiFailureCode(String taskId, DraftTaskCallbackRequest request) {
        String requested = request.errorCode();
        boolean known = requested != null && AI_FAILURE_CODES.contains(requested);
        if (requested != null && !known) {
            log.warn("unknown ai failure code, falling back: taskId={} requested={}", taskId, requested);
        }
        ErrorCode code = known ? ErrorCode.valueOf(requested) : ErrorCode.ERROR_1008;
        log.warn("ai reported failure: taskId={} code={} detail={}", taskId, code, request.error());
        return code;
    }
}
