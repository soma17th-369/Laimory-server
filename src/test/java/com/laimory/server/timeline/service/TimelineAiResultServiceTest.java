package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.timeline.TaskStage;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Redis 결과 단계 선점·rollback·재시도와 단일 task token 검증을 다룬다. */
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

    private TimelineDraftTask taskAt(TaskStage stage) {
        return TimelineDraftTask.processing(USER_ID, RECORD_ID, null, TOKEN_HASH,
                        Instant.parse("2026-06-17T03:05:00Z"))
                .withStage(stage);
    }

    private AiTimelineResultRequest result() {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "점심", null,
                OffsetDateTime.of(DATE.atTime(12, 0), KST),
                OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1"))));
    }

    @Test
    void storeResult_claimsWriting_stores_andAdvancesCallbackStage() {
        TimelineDraftTask pending = taskAt(TaskStage.RESULT_PENDING);
        TimelineDraftTask writing = pending.withStage(TaskStage.RESULT_WRITING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.transitionStage(TASK_ID, pending, TaskStage.RESULT_WRITING)).thenReturn(true);
        when(timelineTaskService.transitionStage(TASK_ID, writing, TaskStage.CALLBACK_PENDING)).thenReturn(true);

        service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result());

        verify(timelineAiResultTransactionService).store(TASK_ID, RECORD_ID, result());
        verify(timelineTaskService).transitionStage(TASK_ID, writing, TaskStage.CALLBACK_PENDING);
    }

    @Test
    void storeResult_replayAtCallbackPending_isBodilessSuccessWithoutWrite() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(TaskStage.CALLBACK_PENDING)));

        service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result());

        verifyNoInteractions(timelineAiResultTransactionService);
        verify(timelineTaskService, never()).transitionStage(anyString(), any(), any());
    }

    @Test
    void storeResult_storageFailure_revertsToResultPending() {
        TimelineDraftTask pending = taskAt(TaskStage.RESULT_PENDING);
        TimelineDraftTask writing = pending.withStage(TaskStage.RESULT_WRITING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));
        when(timelineTaskService.transitionStage(TASK_ID, pending, TaskStage.RESULT_WRITING)).thenReturn(true);
        when(timelineTaskService.transitionStage(TASK_ID, writing, TaskStage.RESULT_PENDING)).thenReturn(true);
        doThrow(new RuntimeException("db down"))
                .when(timelineAiResultTransactionService).store(anyString(), anyLong(), any());

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(timelineTaskService).transitionStage(TASK_ID, writing, TaskStage.RESULT_PENDING);
    }

    @Test
    void storeResult_concurrentClaimAlreadyCompleted_isReplaySuccess() {
        TimelineDraftTask pending = taskAt(TaskStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(pending), Optional.of(taskAt(TaskStage.CALLBACK_PENDING)));
        when(timelineTaskService.transitionStage(TASK_ID, pending, TaskStage.RESULT_WRITING)).thenReturn(false);

        service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result());

        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_concurrentWriting_returns409() {
        TimelineDraftTask pending = taskAt(TaskStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(pending), Optional.of(taskAt(TaskStage.RESULT_WRITING)));
        when(timelineTaskService.transitionStage(TASK_ID, pending, TaskStage.RESULT_WRITING)).thenReturn(false);

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_wrongToken_rejectedBeforeTransaction() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(TaskStage.RESULT_PENDING)));

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, "wrong", result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_beforeInput_rejectedByStage() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(taskAt(TaskStage.INPUT_PENDING)));

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, TASK_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_emptyEvents_rejectedBeforeStageClaim() {
        TimelineDraftTask pending = taskAt(TaskStage.RESULT_PENDING);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.storeResult(
                VERSION, TASK_ID, TASK_TOKEN, new AiTimelineResultRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineTaskService, never()).transitionStage(anyString(), any(), any());
        verifyNoInteractions(timelineAiResultTransactionService);
    }
}
