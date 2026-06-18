package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.CardSuggestionDto;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 콜백 오케스트레이터 단위 검증. 404·멱등·status 분기·검증실패→FAILED. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class TimelineCallbackServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private CardSuggestionValidator cardSuggestionValidator;
    @Mock
    private DailyTimelineService dailyTimelineService;

    @InjectMocks
    private TimelineCallbackService service;

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);

    private DraftTaskCallbackRequest successRequest() {
        List<SourceItemDto> sources = List.of(new SourceItemDto(0,
                LocalDateTime.of(2026, 6, 17, 9, 0), null, "s", new PhotoPayload("u", 1.0, 2.0)));
        List<CardSuggestionDto> cards = List.of(new CardSuggestionDto("제목", "부제",
                LocalDateTime.of(2026, 6, 17, 9, 0), null, List.of(0)));
        return new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null, sources, cards);
    }

    @Test
    void handleCallback_taskNotFound_throws404() {
        when(timelineTaskService.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleCallback("missing", successRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void handleCallback_alreadyTerminal_isIdempotentNoOp() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.success(DATE)));

        service.handleCallback("t", successRequest());

        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any());
        verify(timelineTaskService, never()).markSuccess(anyString(), any());
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyString());
    }

    @Test
    void handleCallback_aiReportedFailure_marksFailed() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.processing(DATE)));
        DraftTaskCallbackRequest req =
                new DraftTaskCallbackRequest(TaskStatus.FAILED, "ai gave up", null, null);

        service.handleCallback("t", req);

        verify(timelineTaskService).markFailed("t", DATE, "ai gave up");
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any());
    }

    @Test
    void handleCallback_success_validatesPersistsAndMarksSuccess() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.processing(DATE)));
        DraftTaskCallbackRequest req = successRequest();

        service.handleCallback("t", req);

        verify(cardSuggestionValidator).validate(req.sourceItems(), req.cards());
        verify(dailyTimelineService).appendDailyTimeline(0L, DATE, req.sourceItems(), req.cards());
        verify(timelineTaskService).markSuccess("t", DATE);
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyString());
    }

    @Test
    void handleCallback_validationFails_marksFailedAndDoesNotPersist() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.processing(DATE)));
        doThrow(new IllegalArgumentException("dup itemId"))
                .when(cardSuggestionValidator).validate(any(), any());

        // 검증 실패는 밖으로 던지지 않고 FAILED로 기록한다(콜백은 200).
        service.handleCallback("t", successRequest());

        verify(timelineTaskService).markFailed("t", DATE, "dup itemId");
        verify(dailyTimelineService, never()).appendDailyTimeline(any(), any(), any(), any());
        verify(timelineTaskService, never()).markSuccess(anyString(), any());
    }

    @Test
    void handleCallback_invalidStatus_throwsBadRequest() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.processing(DATE)));
        DraftTaskCallbackRequest req =
                new DraftTaskCallbackRequest(TaskStatus.PROCESSING, null, null, null);

        assertThatThrownBy(() -> service.handleCallback("t", req))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
