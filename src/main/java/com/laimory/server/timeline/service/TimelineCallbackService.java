package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TimelineDefaults;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.dto.TimelineEventSuggestionDto;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftEventSuggestion;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 작성 콜백 오케스트레이터. task 로드 + 토큰 검증 + 멱등 + status 분기 + finalize/Redis 전이를 합성한다.
 *
 * <p>결과물(이벤트 제안)은 콜백 바디로 오지 않는다 — AI가 콜백 전 DB에 write-then-notify로 저장한다:
 * 이벤트 메타는 {@code timeline_draft_event_suggestions}, 각 이벤트에 묶이는 source item은
 * {@code timeline_draft_source_items.timeline_draft_event_suggestion_id}(soft ref). 서버는 taskId로 이 둘을 로드해
 * {@link TimelineEventSuggestionAssembler}로 {@code events}를 조립한다(입력·출력 모두 DB 경유).
 *
 * <p>순서가 load-bearing이다(spec §Callback 고정):
 * <ol>
 *   <li>task 로드(없음/만료 → 404)</li>
 *   <li><b>토큰 검증(401 ERROR_1002) 먼저</b> — 소비·멱등 단축보다 앞 (토큰이 유일 보호장치이고, terminal task도 해시를 보존하므로 가능)</li>
 *   <li><b>토큰 소비(원자적, 승자 1)</b> — Redis INCR 결과가 1이 아니면 401 ERROR_1012(이미 소비됨).
 *       replay와 동시 중복 콜백을 여기서 원자적으로 거부한다</li>
 *   <li>terminal이면 idempotent return(200) — 중복 finalize 방지 안전망(카운터 유실 시에도 상태 오염 차단)</li>
 *   <li>FAILED → markFailed</li>
 *   <li>SUCCESS → source 행을 DB에서 로드. 없으면(이미 finalize돼 삭제됨) record 존재 시 Redis SUCCESS만 set(멱등 복구),
 *       record도 없으면 FAILED. 있으면 event 제안 행을 로드 — 0행이면(AI 미기록/조기 콜백) 빈 finalize로 source를
 *       지우는 사고를 막기 위해 FAILED. 있으면 assemble(soft ref 무결성 검증) 후 단일 트랜잭션
 *       {@code appendDailyTimeline}(검증+영속+두 staging 삭제)을 호출하고 <b>커밋 이후에만</b> Redis SUCCESS.
 *       assemble/finalize 실패 시 FAILED.</li>
 *   <li>그 외 status → 400</li>
 * </ol>
 *
 * <p>finalize 트랜잭션은 별도 빈 {@link DailyTimelineService#appendDailyTimeline}이 경계다 —
 * 이 클래스가 그 빈을 통해(Spring 프록시 경유) 호출하므로 트랜잭션이 실제로 활성화된다(self-invocation 아님).
 * Redis SUCCESS는 appendDailyTimeline이 반환(=DB 커밋)한 뒤에만 set돼, polling이 'SUCCESS인데 record 없음'을 보지 않는다.
 *
 * <p>모든 terminal 전이(markSuccess/markFailed)는 저장 <b>성공 직후</b> 날짜 guard를 compare-and-release한다
 * (해제 경계 규칙 ③ — {@link TimelineTaskService} 참고). terminal 저장이 실패하면 해제하지 않고
 * TTL(1h) 만료에 맡긴다(규칙 ② — AI 진행 상태 불명인데 풀면 진행 중 삭제가 허용될 수 있음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineCallbackService {

    /** AI가 콜백으로 보고할 수 있는 실패 코드 허용 목록(당분간 ERROR_1008 하나 — 확장 시 여기에 추가). */
    private static final Set<String> AI_FAILURE_CODES = Set.of(ErrorCode.ERROR_1008.name());

    private final TimelineTaskService timelineTaskService;
    private final TimelineDraftSourceItemService timelineDraftSourceItemService;
    private final TimelineDraftEventSuggestionService timelineDraftEventSuggestionService;
    private final TimelineEventSuggestionAssembler timelineEventSuggestionAssembler;
    private final DailyTimelineService dailyTimelineService;
    private final DailyRecordService dailyRecordService;

    public void handleCallback(String applicationVersion, String taskId,
                               String callbackToken, DraftTaskCallbackRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineDraftTask task = timelineTaskService.find(taskId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));

        // 1. 토큰 검증을 먼저 한다(소비·멱등 단축보다 앞). terminal task도 해시를 보존하므로 재콜백도 토큰으로 막힌다.
        if (!CallbackTokens.matches(callbackToken, task.callbackTokenHash())) {
            throw new BusinessException(ExceptionType.CALLBACK_TOKEN_MISMATCH);
        }

        // 2. 토큰 소비(원자적 test-and-consume): INCR 결과가 정확히 1인 요청만 승자.
        //    replay·동시 중복 콜백은 여기서 401로 거부된다(1002=불일치와 달리 1012=이미 소비됨).
        long uses = timelineTaskService.consumeCallbackToken(taskId);
        if (uses != 1) {
            log.warn("callback token replay detected: taskId={} uses={}", taskId, uses);
            throw new BusinessException(ExceptionType.CALLBACK_TOKEN_ALREADY_USED);
        }

        // 3. 멱등: 이미 종결(SUCCESS/FAILED)된 task면 재처리하지 않는다.
        //    replay 거부 안전망이 아니라 중복 finalize 방지 안전망이다 — 카운터 키만 유실된 뒤 terminal task에
        //    재콜백이 오면 새 카운터 INCR=1로 게이트를 통과하고 여기서 no-op 200으로 흡수된다(상태 오염 없음).
        if (task.status() != TaskStatus.PROCESSING) {
            return;
        }

        LocalDate recordDate = task.recordDate();
        String callbackTokenHash = task.callbackTokenHash();

        // AI가 자신의 실패를 보고한 경우: 분류 코드로 FAILED 기록(draft는 cleanup이 보관기간 후 정리).
        // 자유 텍스트(error)는 저장하지 않고 로그로만 — 폴링 body.error 유출 경로 차단.
        if (request.status() == TaskStatus.FAILED) {
            finishFailed(taskId, recordDate, resolveAiFailureCode(taskId, request), callbackTokenHash);
            return;
        }
        if (request.status() != TaskStatus.SUCCESS) {
            throw new IllegalArgumentException("invalid callback status: " + request.status());
        }

        // 3. SUCCESS: source 행을 DB에서 로드.
        List<TimelineDraftSourceItem> draftRows = timelineDraftSourceItemService.findByTaskId(taskId);
        if (draftRows.isEmpty()) {
            // PROCESSING인데 source 부재 = 보통 이전 finalize가 record 생성+staging 삭제를 커밋한 상태(멱등 복구).
            // 단, record가 실제로 존재할 때만 SUCCESS를 확정한다 — record 없이 SUCCESS로 두면 polling이
            // 결과 없는 SUCCESS를 본다(source도 record도 없는 이상 상태). 없으면 FAILED로 종결.
            // 복구 경로에서도 dailyRecordId를 기록한다 — 폴링은 이 ID로만 결과를 조회한다.
            Optional<DailyRecord> finalizedRecord = dailyRecordService
                    .findByUserIdAndRecordDate(TimelineDefaults.DEFAULT_USER_ID, recordDate);
            if (finalizedRecord.isPresent()) {
                finishSuccess(taskId, recordDate, finalizedRecord.get().getDailyRecordId(), callbackTokenHash);
            } else {
                log.warn("staging missing on recovery path: taskId={} (draft rows absent, no finalized record)", taskId);
                finishFailed(taskId, recordDate, ErrorCode.ERROR_1010, callbackTokenHash);
            }
            return;
        }

        // 4. 이벤트 제안 행을 DB staging에서 로드. SUCCESS인데 0행 = AI 미기록/조기 콜백 → 빈 finalize로 source를
        //    지우는 사고를 막기 위해 FAILED로 종결한다(write-then-notify에선 '진짜 0개'와 구분 불가하므로 보수적).
        List<TimelineDraftEventSuggestion> eventRows = timelineDraftEventSuggestionService.findByTaskId(taskId);
        if (eventRows.isEmpty()) {
            log.warn("event suggestions missing for SUCCESS task: taskId={}", taskId);
            finishFailed(taskId, recordDate, ErrorCode.ERROR_1010, callbackTokenHash);
            return;
        }

        // 5. finalize: assemble(soft ref 무결성 검증) → 단일 트랜잭션(검증→record/events/items 저장→두 staging 삭제).
        //    커밋 후에만 Redis SUCCESS. assemble의 무결성 위반이나 finalize 검증/SAVED 실패는 여기서 잡아 FAILED로 기록한다(콜백은 200).
        //    assemble은 트랜잭션 밖이라 실패 시 롤백할 것이 없고, finalize 실패는 롤백된다.
        try {
            List<TimelineEventSuggestionDto> events = timelineEventSuggestionAssembler.assemble(eventRows, draftRows);
            Long dailyRecordId = dailyTimelineService.appendDailyTimeline(
                    TimelineDefaults.DEFAULT_USER_ID, recordDate, task.recordAt(), task.recordTimezone(),
                    draftRows, events);
            finishSuccess(taskId, recordDate, dailyRecordId, callbackTokenHash);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("finalize failed: taskId={} detail={}", taskId, e.getMessage());
            finishFailed(taskId, recordDate, ErrorCode.ERROR_1011, callbackTokenHash);
        }
    }

    /** terminal 저장 성공 직후에만 guard를 해제한다(규칙 ③) — markSuccess가 던지면 여기 못 와 규칙 ②가 자동 준수된다. */
    private void finishSuccess(String taskId, LocalDate recordDate, Long dailyRecordId, String callbackTokenHash) {
        timelineTaskService.markSuccess(taskId, recordDate, dailyRecordId, callbackTokenHash);
        releaseDateGuardQuietly(taskId, recordDate);
    }

    private void finishFailed(String taskId, LocalDate recordDate, ErrorCode failureCode, String callbackTokenHash) {
        timelineTaskService.markFailed(taskId, recordDate, failureCode, callbackTokenHash);
        releaseDateGuardQuietly(taskId, recordDate);
    }

    /**
     * guard 해제는 best-effort다 — terminal 상태는 이미 확정됐고 실패해도 TTL(1h)이 자연 해제하는 안전망이
     * 있으므로, 해제 실패로 콜백을 500으로 만들지 않는다(AI 재콜백은 토큰 소비 게이트가 흡수).
     */
    private void releaseDateGuardQuietly(String taskId, LocalDate recordDate) {
        try {
            timelineTaskService.releaseDateGuard(TimelineDefaults.DEFAULT_USER_ID, recordDate,
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
