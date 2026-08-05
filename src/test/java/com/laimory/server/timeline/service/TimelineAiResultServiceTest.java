package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineResultResponse;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RESULT → CALLBACK token/stage 선점과 DB 실패 rollback을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TimelineAiResultServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineAiResultTransactionService timelineAiResultTransactionService;
    @InjectMocks
    private TimelineAiResultService service;

    private static final String VERSION = "v1";
    private static final String TASK_ID = "t";
    private static final long USER_ID = 7L;
    private static final long RECORD_ID = 42L;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final String TASK_TOKEN = "raw-task-token";
    private static final String TOKEN_HASH = TaskTokens.hash(TASK_TOKEN);

    private TimelineDraftTask taskAt(ProcessStage stage) {
        return TimelineDraftTask.processing(USER_ID, RECORD_ID, null, TOKEN_HASH,
                        Instant.parse("2026-06-17T03:05:00Z"))
                .withTokenAndStage(TOKEN_HASH, stage);
    }

    private AiTimelineResultRequest result() {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "점심", null, "점심에 누구와 함께였나요?",
                OffsetDateTime.of(DATE.atTime(12, 0), KST),
                OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1"))));
    }

    @Test
    void storeResult_rotatesToCallbackToken_thenStores() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(pending), any())).thenReturn(true);

        AiTimelineResultResponse response = service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result());

        ArgumentCaptor<TimelineDraftTask> claimed = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService).replaceProcessing(eq(TASK_ID), eq(pending), claimed.capture());
        assertThat(claimed.getValue().stage()).isEqualTo(ProcessStage.CALLBACK_PENDING);
        assertThat(claimed.getValue().matchesToken(response.taskToken())).isTrue();
        assertThat(response.taskToken()).matches("[A-Za-z0-9_-]{43}");
        verify(timelineAiResultTransactionService).store(TASK_ID, RECORD_ID, result());
    }

    @Test
    void storeResult_storageFailure_restoresResultTokenAndStage() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(pending), any())).thenReturn(true);
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), any(), eq(pending))).thenReturn(true);
        doThrow(new RuntimeException("db down"))
                .when(timelineAiResultTransactionService).store(anyString(), anyLong(), any());

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        ArgumentCaptor<TimelineDraftTask> claimed = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService).replaceProcessing(eq(TASK_ID), claimed.capture(), eq(pending));
        assertThat(claimed.getValue().stage()).isEqualTo(ProcessStage.CALLBACK_PENDING);
    }

    @Test
    void storeResult_concurrentClaim_returns409() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(pending), any())).thenReturn(false);

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_wrongToken_rejectedBeforeTransaction() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(ProcessStage.RESULT_PENDING)));

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, "wrong", result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_beforeInput_rejectedByStage() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(ProcessStage.INPUT_PENDING)));

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    /** question은 선택 필드다 — 도입 이전 형식(null)도 그대로 저장 경로를 탄다. */
    @Test
    void storeResult_withoutQuestion_isStored() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.replaceProcessing(eq(TASK_ID), eq(pending), any())).thenReturn(true);
        AiTimelineResultRequest legacy = resultWithQuestion(null);

        service.storeResult(VERSION, TASK_ID, TASK_TOKEN, legacy);

        verify(timelineAiResultTransactionService).store(TASK_ID, RECORD_ID, legacy);
    }

    @Test
    void storeResult_questionTooLong_rejectedBeforeClaim() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.storeResult(
                VERSION, TASK_ID, TASK_TOKEN, resultWithQuestion("질".repeat(256))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineTaskService, never()).replaceProcessing(anyString(), any(), any());
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    private AiTimelineResultRequest resultWithQuestion(String question) {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "점심", null, question,
                OffsetDateTime.of(DATE.atTime(12, 0), KST),
                OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1"))));
    }

    @Test
    void storeResult_emptyEvents_rejectedBeforeClaim() {
        TimelineDraftTask pending = taskAt(ProcessStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.storeResult(
                VERSION, TASK_ID, TASK_TOKEN, new AiTimelineResultRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineTaskService, never()).replaceProcessing(anyString(), any(), any());
        verifyNoInteractions(timelineAiResultTransactionService);
    }
}
