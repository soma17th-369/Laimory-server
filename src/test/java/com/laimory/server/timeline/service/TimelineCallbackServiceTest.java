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
import com.laimory.server.timeline.dto.TimelineEventSuggestionDto;
import com.laimory.server.timeline.entity.DailyRecord;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** 콜백 오케스트레이터 단위 검증. 404·token-first(401)·멱등·draft 로드·커밋후-Redis·검증실패→FAILED. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class TimelineCallbackServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;
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

    private TimelineDraftTask processingTask() {
        return TimelineDraftTask.processing(DATE, TOKEN_HASH);
    }

    private DraftTaskCallbackRequest successRequest() {
        List<TimelineEventSuggestionDto> events = List.of(new TimelineEventSuggestionDto("제목", "부제",
                LocalDateTime.of(2026, 6, 17, 9, 0), null, List.of(0L)));
        return new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null, events);
    }

    private List<TimelineDraftSourceItem> draftRows() {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of("t", 0L, DATE, "Asia/Seoul", ItemType.PHOTO,
                LocalDateTime.of(2026, 6, 17, 9, 0), null, "s",
                MAPPER.valueToTree(new PhotoPayload("u", 1.0, 2.0)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", 0L);
        return List.of(row);
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
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any());
    }

    @Test
    void handleCallback_withWrongCallbackToken_throws401() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));

        assertThatThrownBy(() -> service.handleCallback("v1", "t", "wrong-token", successRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any());
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

        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any());
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), anyString());
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyString(), anyString());
        verify(timelineDraftSourceItemService, never()).findByTaskId(anyString());
    }

    @Test
    void handleCallback_aiReportedFailure_marksFailed() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        DraftTaskCallbackRequest req =
                new DraftTaskCallbackRequest(TaskStatus.FAILED, "ai gave up", null);

        service.handleCallback("v1", "t", TOKEN, req);

        verify(timelineTaskService).markFailed("t", DATE, "ai gave up", TOKEN_HASH);
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any());
        verify(timelineDraftSourceItemService, never()).findByTaskId(anyString());
    }

    @Test
    void handleCallback_success_loadsDraftsFinalizesThenMarksSuccess() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(draftRows());
        DraftTaskCallbackRequest req = successRequest();

        service.handleCallback("v1", "t", TOKEN, req);

        // draft는 바디가 아닌 서비스에서 로드돼 finalize에 전달된다.
        verify(dailyTimelineService).appendDailyTimeline(eq(0L), eq(DATE), any(), eq(req.events()));
        verify(timelineTaskService).markSuccess("t", DATE, TOKEN_HASH);
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyString(), anyString());

        // 불변식: Redis SUCCESS는 finalize(=DB 커밋) 이후에만 set된다.
        InOrder order = inOrder(dailyTimelineService, timelineTaskService);
        order.verify(dailyTimelineService).appendDailyTimeline(any(), any(), any(), any());
        order.verify(timelineTaskService).markSuccess("t", DATE, TOKEN_HASH);
    }

    @Test
    void handleCallback_success_draftAbsent_recordExists_idempotentRecovery_marksSuccess() {
        // draft 부재 + record 존재 = 이전 finalize가 커밋·삭제한 상태 → 재작성 없이 Redis SUCCESS만 set.
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(List.of());
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE))
                .thenReturn(Optional.of(DailyRecord.createDraft(0L, DATE, "Asia/Seoul")));

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any());
        verify(timelineTaskService).markSuccess("t", DATE, TOKEN_HASH);
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyString(), anyString());
    }

    @Test
    void handleCallback_success_draftAbsent_recordMissing_marksFailed() {
        // draft도 record도 없는 이상 상태 = SUCCESS로 두면 폴링이 500을 낸다 → FAILED로 종결한다.
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(List.of());
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markFailed(eq("t"), eq(DATE), anyString(), eq(TOKEN_HASH));
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), anyString());
    }

    @Test
    void handleCallback_finalizeFails_marksFailedAndDoesNotMarkSuccess() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        when(timelineDraftSourceItemService.findByTaskId("t")).thenReturn(draftRows());
        // finalize 내부 검증/SAVED 실패 → 롤백되고 콜백이 IAE로 잡아 FAILED 기록.
        doThrow(new IllegalArgumentException("event references unknown itemId: 9"))
                .when(dailyTimelineService).appendDailyTimeline(any(), any(), any(), any());

        service.handleCallback("v1", "t", TOKEN, successRequest());

        verify(timelineTaskService).markFailed("t", DATE, "event references unknown itemId: 9", TOKEN_HASH);
        verify(timelineTaskService, never()).markSuccess(anyString(), any(), anyString());
    }

    @Test
    void handleCallback_invalidStatus_throwsBadRequest() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask()));
        DraftTaskCallbackRequest req =
                new DraftTaskCallbackRequest(TaskStatus.PROCESSING, null, null);

        assertThatThrownBy(() -> service.handleCallback("v1", "t", TOKEN, req))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
