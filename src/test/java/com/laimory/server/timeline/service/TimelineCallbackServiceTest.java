package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.dto.TimelineEventSuggestionDto;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftEventSuggestion;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 콜백 오케스트레이터 단위 검증. 404·token-first(401)·원자적 토큰 소비(1012)·멱등·source/event DB 로드·
 * 커밋후-Redis·(assemble 무결성/finalize 검증) 실패→FAILED. 인프라 0. events는 바디가 아닌 DB에서 로드·조립된다(assembler는 실제 사용).
 *
 * <p>valid token 경로는 {@code consumeCallbackToken → 1} 스텁이 필수다 — Mockito 기본값 0은
 * {@code uses != 1} 게이트에 걸려 1012로 거부된다(셋업 헬퍼 사용).
 */
@ExtendWith(MockitoExtension.class)
class TimelineCallbackServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;
    @Mock
    private TimelineDraftEventSuggestionService timelineDraftEventSuggestionService;
    @Spy
    private TimelineEventSuggestionAssembler timelineEventSuggestionAssembler = new TimelineEventSuggestionAssembler();
    @Mock
    private DailyTimelineService dailyTimelineService;
    @Mock
    private DailyRecordService dailyRecordService;

    @InjectMocks
    private TimelineCallbackService service;

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final String TOKEN = "raw-callback-token";
    private static final String TOKEN_HASH = CallbackTokens.hash(TOKEN);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long EVENT_ID = 100L;

    private TimelineDraftTask processingTask() {
        // processingStartedAt은 콜백 처리와 무관하다(PROCESSING 전용 — terminal 전이 시 폐기).
        return TimelineDraftTask.processing(DATE, DATE.atTime(12, 0), "Asia/Seoul", null, TOKEN_HASH,
                Instant.parse("2026-06-17T03:05:00Z"));
    }

    /** valid token 경로 공통 셋업: PROCESSING task 로드 + 토큰 소비 승자(INCR=1). */
    private void givenProcessingTaskWithFreshToken() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        when(timelineTaskService.consumeCallbackToken("t")).thenReturn(1L);
    }

    private DraftTaskCallbackRequest successRequest() {
        return new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null, null);
    }

    /** source 행: PK=10, 이번 task의 event 제안(EVENT_ID)에 배정됨. */
    private List<TimelineDraftSourceItem> draftRows() {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of("t", 0L, ItemType.PHOTO, "r10",
                LocalDateTime.of(2026, 6, 17, 9, 0), null,
                MAPPER.valueToTree(new PhotoPayload("u", "content://x", 1.0, 2.0, null, null)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", 10L);
        row.assignEventSuggestion(EVENT_ID);
        return List.of(row);
    }

    /** event 제안 행: PK=EVENT_ID. */
    private List<TimelineDraftEventSuggestion> eventRows() {
        return eventRows(TimelineEventType.UNKNOWN.name());
    }

    /** event 제안 행: PK=EVENT_ID, staging raw eventType 지정. */
    private List<TimelineDraftEventSuggestion> eventRows(String rawEventType) {
        TimelineDraftEventSuggestion event = TimelineDraftEventSuggestion.of("t", 0L, rawEventType,
                LocalDateTime.of(2026, 6, 17, 9, 0), null, "제목", "부제");
        ReflectionTestUtils.setField(event, "timelineDraftEventSuggestionId", EVENT_ID);
        return List.of(event);
    }

    @Test
    void handleCallback_taskNotFound_throws404() {
        when(timelineTaskService.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleCallback("v1", "missing", TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1001));
    }

    @Test
    void handleCallback_withoutCallbackToken_throws401() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));

        assertThatThrownBy(() -> service.handleCallback("v1", "t", null, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1002));
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        // 검증→소비 순서: 인증 실패한 요청은 토큰을 소비하지 않는다.
        verify(timelineTaskService, never()).consumeCallbackToken(anyString());
    }

    @Test
    void handleCallback_withWrongCallbackToken_throws401() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));

        assertThatThrownBy(() -> service.handleCallback("v1", "t", "wrong-token", successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1002));
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        // 검증→소비 순서: 인증 실패한 요청은 토큰을 소비하지 않는다.
        verify(timelineTaskService, never()).consumeCallbackToken(anyString());
    }

    @Test
    void handleCallback_consumedToken_throws401With1012() {
        // 원자적 소비 게이트: INCR 결과가 1이 아니면(이미 소비됨) 1012로 거부, 어떤 처리에도 진입하지 않는다.
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        when(timelineTaskService.consumeCallbackToken("t")).thenReturn(2L);

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1012));
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        verify(timelineDraftSourceItemService, never()).findByTaskId(anyString());
    }

    @Test
    void handleCallback_consumeReturnsNonPositive_throws401With1012() {
        // uses != 1 게이트: 손상/수동 조작으로 0 이하가 와도 보안 경로를 통과시키지 않는다.
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        when(timelineTaskService.consumeCallbackToken("t")).thenReturn(0L);

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1012));
    }

    @Test
    void handleCallback_errorCodeContract_wrongToken1002_consumedToken1012() {
        // 핵심 계약: 1002=토큰 불일치(인증 실패, 재시도 무의미) vs 1012=이미 소비됨(같은 토큰 재전송 불가).
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));

        assertThatThrownBy(() -> service.handleCallback("v1", "t", "wrong-token", successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1002));

        when(timelineTaskService.consumeCallbackToken("t")).thenReturn(2L);
        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1012));
    }

    @Test
    void handleCallback_tokenCheckedBeforeIdempotentReturn_wrongTokenOnTerminalTask_throws401() {
        // token-first: 이미 SUCCESS(terminal)여도 토큰이 틀리면 멱등 단축 전에 401로 막힌다(해시 보존 덕분).
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.success(DATE, 42L, TOKEN_HASH)));

        assertThatThrownBy(() -> service.handleCallback("v1", "t", "wrong-token", successRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1002));
    }

    @Test
    void handleCallback_reusedTokenAfterSuccess_doesNotPersistAgain() {
        // 카운터 유실 후 terminal task 재콜백 시나리오(INCR=1로 게이트 통과): 멱등 안전망이 no-op으로 흡수해
        // 중복 finalize(재저장)를 막는다. 정상 replay는 이 앞의 소비 게이트에서 1012로 거부된다.
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.success(DATE, 42L, TOKEN_HASH)));
        when(timelineTaskService.consumeCallbackToken("t")).thenReturn(1L);

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), any(), anyString());
        verify(timelineTaskService, never()).markFailed(anyString(), any(), any(), anyString());
        verify(timelineDraftSourceItemService, never()).findByTaskId(anyString());
        verify(timelineDraftEventSuggestionService, never()).findByTaskId(anyString());
    }

    @Test
    void handleCallback_aiReportedFailure_marksFailed() {
        givenProcessingTaskWithFreshToken();
        DraftTaskCallbackRequest req = new DraftTaskCallbackRequest(TaskStatus.FAILED, null, "ai gave up");

        service.handleCallback("v1", "t", TOKEN, req);

        verify(timelineTaskService).markFailed("t", DATE, ErrorCode.ERROR_1008, TOKEN_HASH); // errorCode 누락 -> 1008 폴백, 자유 텍스트는 저장 안 함
        // FAILED terminal 저장 성공 후에도 guard를 해제한다(규칙 ③ — 실패 종결도 날짜를 풀어줘야 재시도 가능).
        verify(timelineTaskService).releaseDateGuard(0L, DATE, "task:t");
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        verify(timelineDraftSourceItemService, never()).findByTaskId(anyString());
    }

    @Test
    void handleCallback_aiReportedFailure_withValidCode_storesIt() {
        givenProcessingTaskWithFreshToken();
        DraftTaskCallbackRequest req = new DraftTaskCallbackRequest(TaskStatus.FAILED, "ERROR_1008", "gpu timeout");

        service.handleCallback("v1", "t", TOKEN, req);

        verify(timelineTaskService).markFailed("t", DATE, ErrorCode.ERROR_1008, TOKEN_HASH);
    }

    @Test
    void handleCallback_aiReportedFailure_withUnknownCode_fallsBackTo1008() {
        // 허용 목록 밖 코드(HTTP용 코드 포함)는 저장하지 않고 ERROR_1008로 폴백 — 오분류·유출 차단.
        givenProcessingTaskWithFreshToken();
        DraftTaskCallbackRequest req = new DraftTaskCallbackRequest(TaskStatus.FAILED, "ERROR_9999", null);

        service.handleCallback("v1", "t", TOKEN, req);

        verify(timelineTaskService).markFailed("t", DATE, ErrorCode.ERROR_1008, TOKEN_HASH);
    }

    @Test
    void handleCallback_success_loadsFromDbAssemblesFinalizesThenMarksSuccess() {
        givenProcessingTaskWithFreshToken();
        List<TimelineDraftSourceItem> rows = draftRows();
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(rows);
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(eventRows());
        when(dailyTimelineService.appendDailyTimeline(eq(0L), eq(DATE), any(), any(), eq(rows), any()))
                .thenReturn(77L);

        service.handleCallback("v1", "t", TOKEN, successRequest());

        // events는 바디가 아닌 DB(event 제안 + source event_fk)에서 로드·조립돼 finalize에 전달된다.
        verify(dailyTimelineService).appendDailyTimeline(eq(0L), eq(DATE), any(), any(), eq(rows), any());
        // finalize가 반환한 dailyRecordId가 SUCCESS task에 기록된다(폴링은 이 ID로만 결과 조회).
        verify(timelineTaskService).markSuccess("t", DATE, 77L, TOKEN_HASH);
        verify(timelineTaskService, never()).markFailed(anyString(), any(), any(), anyString());

        // 불변식: Redis SUCCESS는 finalize(=DB 커밋) 이후에만 set되고, guard 해제는 terminal 저장 성공 뒤다(규칙 ③).
        InOrder order = inOrder(dailyTimelineService, timelineTaskService);
        order.verify(dailyTimelineService).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        order.verify(timelineTaskService).markSuccess("t", DATE, 77L, TOKEN_HASH);
        order.verify(timelineTaskService).releaseDateGuard(0L, DATE, "task:t");
    }

    @Test
    void handleCallback_success_sourceAbsent_recordExists_idempotentRecovery_marksSuccess() {
        // source 부재 + record 존재 = 이전 finalize가 커밋·삭제한 상태 → 재작성 없이 Redis SUCCESS만 set.
        givenProcessingTaskWithFreshToken();
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(List.of());
        DailyRecord finalized = DailyRecord.createDraft(0L, DATE, DATE.atTime(12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(finalized, "dailyRecordId", 42L);
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.of(finalized));

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        // 복구 경로도 조회한 record의 ID를 기록한다 — 폴링 ID 조회가 legacy 0404로 빠지지 않게.
        verify(timelineTaskService).markSuccess("t", DATE, 42L, TOKEN_HASH);
        verify(timelineTaskService, never()).markFailed(anyString(), any(), any(), anyString());
        verify(timelineTaskService).releaseDateGuard(0L, DATE, "task:t");
        // source 부재면 event 제안은 조회하지 않는다(복구 경로가 앞서 return).
        verify(timelineDraftEventSuggestionService, never()).findByTaskId(anyString());
    }

    @Test
    void handleCallback_success_sourceAbsent_recordMissing_marksFailed() {
        // source도 record도 없는 이상 상태 = SUCCESS로 두면 폴링이 500을 낸다 → FAILED로 종결한다.
        givenProcessingTaskWithFreshToken();
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(List.of());
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markFailed(eq("t"), eq(DATE), eq(ErrorCode.ERROR_1010), eq(TOKEN_HASH));
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), any(), anyString());
        verify(timelineTaskService).releaseDateGuard(0L, DATE, "task:t");
    }

    @Test
    void handleCallback_success_eventSuggestionsMissing_marksFailed() {
        // ①: source는 있는데 event 제안이 0행 = AI 미기록/조기 콜백 → 빈 finalize로 source를 지우는 사고 방지 위해 FAILED.
        givenProcessingTaskWithFreshToken();
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(draftRows());
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(List.of());

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markFailed(eq("t"), eq(DATE), eq(ErrorCode.ERROR_1010), eq(TOKEN_HASH));
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), any(), anyString());
    }

    @Test
    void handleCallback_success_sourceReferencesUnknownEvent_assemblerIntegrityViolation_marksFailed() {
        // source item이 이번 task의 event 제안에 없는 id(999)를 가리킴 → assembler가 IAE → 콜백이 잡아 FAILED(조용한 유실 차단).
        givenProcessingTaskWithFreshToken();
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of("t", 0L, ItemType.PHOTO, "r10",
                LocalDateTime.of(2026, 6, 17, 9, 0), null,
                MAPPER.valueToTree(new PhotoPayload("u", "content://x", 1.0, 2.0, null, null)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", 10L);
        row.assignEventSuggestion(999L);
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(List.of(row));
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(eventRows()); // event id=100

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markFailed(eq("t"), eq(DATE), eq(ErrorCode.ERROR_1011), eq(TOKEN_HASH));
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), any(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleCallback_success_stagingEventTypePassedToFinalize() {
        // staging raw literal이 assembler 변환을 거쳐 finalize에 enum으로 전달된다(재추론·유실 없음).
        givenProcessingTaskWithFreshToken();
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(draftRows());
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(eventRows("MEAL"));
        when(dailyTimelineService.appendDailyTimeline(any(), any(), any(), any(), any(), any())).thenReturn(77L);

        service.handleCallback("v1", "t", TOKEN, successRequest());

        ArgumentCaptor<List<TimelineEventSuggestionDto>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(dailyTimelineService).appendDailyTimeline(any(), any(), any(), any(), any(), eventsCaptor.capture());
        assertThat(eventsCaptor.getValue()).hasSize(1);
        assertThat(eventsCaptor.getValue().get(0).eventType()).isEqualTo(TimelineEventType.MEAL);
        verify(timelineTaskService).markSuccess("t", DATE, 77L, TOKEN_HASH);
    }

    @Test
    void handleCallback_success_invalidStagingEventType_marksFailedWithoutFinalize() {
        // 미지원 staging literal은 assembler 변환 IAE → ERROR_1011 FAILED, SUCCESS/finalize 미실행(D1-A 경계).
        givenProcessingTaskWithFreshToken();
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(draftRows());
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(eventRows("PICNIC"));

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markFailed(eq("t"), eq(DATE), eq(ErrorCode.ERROR_1011), eq(TOKEN_HASH));
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), any(), anyString());
    }

    @Test
    void handleCallback_finalizeFails_marksFailedAndDoesNotMarkSuccess() {
        givenProcessingTaskWithFreshToken();
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(draftRows());
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(eventRows());
        // finalize 내부 검증/SAVED 실패 → 롤백되고 콜백이 IAE로 잡아 FAILED 기록.
        doThrow(new IllegalArgumentException("event references unknown itemId: 9"))
                .when(dailyTimelineService).appendDailyTimeline(any(), any(), any(), any(), any(), any());

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markFailed("t", DATE, ErrorCode.ERROR_1011, TOKEN_HASH); // raw 메시지 대신 분류 코드
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), any(), anyString());
    }

    @Test
    void handleCallback_invalidStatus_throwsBadRequest() {
        givenProcessingTaskWithFreshToken();
        DraftTaskCallbackRequest req = new DraftTaskCallbackRequest(TaskStatus.PROCESSING, null, null);

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handleCallback_terminalSaveFails_doesNotReleaseGuard() {
        // 해제 경계 규칙 ②: terminal 저장 실패 = AI 진행 상태 불명 → guard를 풀지 않고 TTL에 맡긴다
        // (즉시 풀면 실제 finalize가 커밋된 날짜에 삭제가 끼어들 수 있다).
        givenProcessingTaskWithFreshToken();
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(draftRows());
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(eventRows());
        when(dailyTimelineService.appendDailyTimeline(any(), any(), any(), any(), any(), any())).thenReturn(77L);
        doThrow(new RuntimeException("redis down")).when(timelineTaskService)
                .markSuccess(anyString(), any(), any(), anyString());

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, successRequest()))
                .isInstanceOf(RuntimeException.class);

        verify(timelineTaskService, never()).releaseDateGuard(anyLong(), any(), anyString());
    }

    @Test
    void handleCallback_guardReleaseFails_isSwallowedAndCallbackSucceeds() {
        // 해제는 best-effort: terminal 상태는 이미 확정됐고 TTL이 안전망이라, 해제 실패로 콜백을 500으로 만들지 않는다.
        givenProcessingTaskWithFreshToken();
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(draftRows());
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(eventRows());
        when(dailyTimelineService.appendDailyTimeline(any(), any(), any(), any(), any(), any())).thenReturn(77L);
        doThrow(new RuntimeException("redis down")).when(timelineTaskService)
                .releaseDateGuard(anyLong(), any(), anyString());

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markSuccess("t", DATE, 77L, TOKEN_HASH);
    }
}
