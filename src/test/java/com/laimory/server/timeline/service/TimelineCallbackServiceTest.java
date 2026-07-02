package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftEventSuggestion;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 콜백 오케스트레이터 단위 검증. 404·token-first(401)·멱등·source/event DB 로드·커밋후-Redis·
 * (assemble 무결성/finalize 검증) 실패→FAILED. 인프라 0. events는 바디가 아닌 DB에서 로드·조립된다(assembler는 실제 사용).
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
        return TimelineDraftTask.processing(DATE, DATE.atTime(12, 0), "Asia/Seoul", TOKEN_HASH);
    }

    private DraftTaskCallbackRequest successRequest() {
        return new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null);
    }

    /** source 행: PK=10, 이번 task의 event 제안(EVENT_ID)에 배정됨. */
    private List<TimelineDraftSourceItem> draftRows() {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of("t", 0L, ItemType.PHOTO,
                LocalDateTime.of(2026, 6, 17, 9, 0), null,
                MAPPER.valueToTree(new PhotoPayload("u", "content://x", 1.0, 2.0)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", 10L);
        row.assignEventSuggestion(EVENT_ID);
        return List.of(row);
    }

    /** event 제안 행: PK=EVENT_ID. */
    private List<TimelineDraftEventSuggestion> eventRows() {
        TimelineDraftEventSuggestion event = TimelineDraftEventSuggestion.of("t", 0L,
                LocalDateTime.of(2026, 6, 17, 9, 0), null, "제목", "부제");
        ReflectionTestUtils.setField(event, "timelineDraftEventSuggestionId", EVENT_ID);
        return List.of(event);
    }

    @Test
    void handleCallback_taskNotFound_throws404() {
        when(timelineTaskService.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleCallback("v1", "missing", TOKEN, successRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void handleCallback_withoutCallbackToken_throws401() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));

        assertThatThrownBy(() -> service.handleCallback("v1", "t", null, successRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
    }

    @Test
    void handleCallback_withWrongCallbackToken_throws401() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));

        assertThatThrownBy(() -> service.handleCallback("v1", "t", "wrong-token", successRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
    }

    @Test
    void handleCallback_tokenCheckedBeforeIdempotentReturn_wrongTokenOnTerminalTask_throws401() {
        // token-first: 이미 SUCCESS(terminal)여도 토큰이 틀리면 멱등 단축 전에 401로 막힌다(해시 보존 덕분).
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.success(DATE, TOKEN_HASH)));

        assertThatThrownBy(() -> service.handleCallback("v1", "t", "wrong-token", successRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void handleCallback_reusedTokenAfterSuccess_doesNotPersistAgain() {
        // 이미 SUCCESS(종결)된 task면 토큰 검증 통과 후 idempotent no-op (재저장 없음 = replay 무효).
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.success(DATE, TOKEN_HASH)));

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), anyString());
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyString(), anyString());
        verify(timelineDraftSourceItemService, never()).findByTaskId(anyString());
        verify(timelineDraftEventSuggestionService, never()).findByTaskId(anyString());
    }

    @Test
    void handleCallback_aiReportedFailure_marksFailed() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        DraftTaskCallbackRequest req = new DraftTaskCallbackRequest(TaskStatus.FAILED, "ai gave up");

        service.handleCallback("v1", "t", TOKEN, req);

        verify(timelineTaskService).markFailed("t", DATE, "ai gave up", TOKEN_HASH);
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        verify(timelineDraftSourceItemService, never()).findByTaskId(anyString());
    }

    @Test
    void handleCallback_success_loadsFromDbAssemblesFinalizesThenMarksSuccess() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        List<TimelineDraftSourceItem> rows = draftRows();
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(rows);
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(eventRows());

        service.handleCallback("v1", "t", TOKEN, successRequest());

        // events는 바디가 아닌 DB(event 제안 + source event_fk)에서 로드·조립돼 finalize에 전달된다.
        verify(dailyTimelineService).appendDailyTimeline(eq(0L), eq(DATE), any(), any(), eq(rows), any());
        verify(timelineTaskService).markSuccess("t", DATE, TOKEN_HASH);
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyString(), anyString());

        // 불변식: Redis SUCCESS는 finalize(=DB 커밋) 이후에만 set된다.
        InOrder order = inOrder(dailyTimelineService, timelineTaskService);
        order.verify(dailyTimelineService).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        order.verify(timelineTaskService).markSuccess("t", DATE, TOKEN_HASH);
    }

    @Test
    void handleCallback_success_sourceAbsent_recordExists_idempotentRecovery_marksSuccess() {
        // source 부재 + record 존재 = 이전 finalize가 커밋·삭제한 상태 → 재작성 없이 Redis SUCCESS만 set.
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(List.of());
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE))
                .thenReturn(Optional.of(DailyRecord.createDraft(0L, DATE, DATE.atTime(12, 0), "Asia/Seoul")));

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        verify(timelineTaskService).markSuccess("t", DATE, TOKEN_HASH);
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyString(), anyString());
        // source 부재면 event 제안은 조회하지 않는다(복구 경로가 앞서 return).
        verify(timelineDraftEventSuggestionService, never()).findByTaskId(anyString());
    }

    @Test
    void handleCallback_success_sourceAbsent_recordMissing_marksFailed() {
        // source도 record도 없는 이상 상태 = SUCCESS로 두면 폴링이 500을 낸다 → FAILED로 종결한다.
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(List.of());
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markFailed(eq("t"), eq(DATE), anyString(), eq(TOKEN_HASH));
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), anyString());
    }

    @Test
    void handleCallback_success_eventSuggestionsMissing_marksFailed() {
        // ①: source는 있는데 event 제안이 0행 = AI 미기록/조기 콜백 → 빈 finalize로 source를 지우는 사고 방지 위해 FAILED.
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(draftRows());
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(List.of());

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markFailed(eq("t"), eq(DATE), anyString(), eq(TOKEN_HASH));
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), anyString());
    }

    @Test
    void handleCallback_success_sourceReferencesUnknownEvent_assemblerIntegrityViolation_marksFailed() {
        // source item이 이번 task의 event 제안에 없는 id(999)를 가리킴 → assembler가 IAE → 콜백이 잡아 FAILED(조용한 유실 차단).
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of("t", 0L, ItemType.PHOTO,
                LocalDateTime.of(2026, 6, 17, 9, 0), null,
                MAPPER.valueToTree(new PhotoPayload("u", "content://x", 1.0, 2.0)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", 10L);
        row.assignEventSuggestion(999L);
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(List.of(row));
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(eventRows()); // event id=100

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markFailed(eq("t"), eq(DATE), anyString(), eq(TOKEN_HASH));
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any(), any(), any());
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), anyString());
    }

    @Test
    void handleCallback_finalizeFails_marksFailedAndDoesNotMarkSuccess() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(draftRows());
        when(timelineDraftEventSuggestionService.findByTaskId("t")).thenReturn(eventRows());
        // finalize 내부 검증/SAVED 실패 → 롤백되고 콜백이 IAE로 잡아 FAILED 기록.
        doThrow(new IllegalArgumentException("event references unknown itemId: 9"))
                .when(dailyTimelineService).appendDailyTimeline(any(), any(), any(), any(), any(), any());

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markFailed("t", DATE, "event references unknown itemId: 9", TOKEN_HASH);
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), anyString());
    }

    @Test
    void handleCallback_invalidStatus_throwsBadRequest() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        DraftTaskCallbackRequest req = new DraftTaskCallbackRequest(TaskStatus.PROCESSING, null);

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, req))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
