package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.TaskTokenFixtures.derivedTokenHashes;
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
import org.springframework.dao.DataIntegrityViolationException;

/**
 * AI 결과 저장 오케스트레이터 단위 검증: 단계 토큰 게이트, 형식 검증이 transaction 앞에서 걸리는지,
 * 영수증 duplicate key의 "이미 반영" 변환, 다음 토큰 발급. 저장 규칙 자체는 transaction service가 소유한다.
 */
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
    private static final String INPUT_TOKEN = "raw-input-token";
    private static final String RESULT_TOKEN = TaskTokens.deriveResultToken(INPUT_TOKEN, TASK_ID);
    private static final TimelineDraftTask.TokenHashes TOKEN_HASHES =
            derivedTokenHashes(INPUT_TOKEN, TASK_ID);

    private TimelineDraftTask processingTask() {
        return TimelineDraftTask.processing(USER_ID, RECORD_ID, null, TOKEN_HASHES,
                Instant.parse("2026-06-17T03:05:00Z"));
    }

    private void givenProcessingTask() {
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(processingTask()));
    }

    private AiTimelineResultRequest result() {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "점심", null,
                OffsetDateTime.of(DATE.atTime(12, 0), KST), OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1"))));
    }

    @Test
    void storeResult_storesThenReturnsCallbackToken() {
        givenProcessingTask();

        String callbackToken = service.storeResult(VERSION, TASK_ID, RESULT_TOKEN, result()).callbackToken();

        verify(timelineAiResultTransactionService).store(TASK_ID, RECORD_ID, result());
        assertThat(callbackToken).isEqualTo(TaskTokens.deriveCallbackToken(RESULT_TOKEN, TASK_ID));
    }

    @Test
    void storeResult_retriedAfterLostResponse_isIdempotentSuccess() {
        // 영수증 duplicate key = 이미 반영된 task. graph를 건드리지 않고 같은 다음 토큰으로 성공한다.
        givenProcessingTask();
        doThrow(new DataIntegrityViolationException("duplicate receipt"))
                .when(timelineAiResultTransactionService).store(anyString(), anyLong(), any());

        String callbackToken = service.storeResult(VERSION, TASK_ID, RESULT_TOKEN, result()).callbackToken();

        assertThat(callbackToken).isEqualTo(TaskTokens.deriveCallbackToken(RESULT_TOKEN, TASK_ID));
        verify(timelineTaskService).refreshProcessing(TASK_ID, processingTask());
    }

    @Test
    void storeResult_refreshesProcessingTtlAfterCommit() {
        givenProcessingTask();

        service.storeResult(VERSION, TASK_ID, RESULT_TOKEN, result());

        verify(timelineTaskService).refreshProcessing(TASK_ID, processingTask());
    }

    @Test
    void storeResult_storeFails_propagatesWithoutTtlRefresh() {
        givenProcessingTask();
        doThrow(new RuntimeException("db down"))
                .when(timelineAiResultTransactionService).store(anyString(), anyLong(), any());

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, RESULT_TOKEN, result()))
                .isInstanceOf(RuntimeException.class);

        verify(timelineTaskService, never()).refreshProcessing(anyString(), any());
    }

    @Test
    void storeResult_taskNotFound_throws404() {
        when(timelineTaskService.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.storeResult(VERSION, "missing", RESULT_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1001));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_wrongStageToken_throws401() {
        // 입력 단계 토큰(T1)으로는 결과를 저장할 수 없다.
        givenProcessingTask();

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, INPUT_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_terminalTask_throws1017() {
        when(timelineTaskService.find(TASK_ID))
                .thenReturn(Optional.of(TimelineDraftTask.failed(USER_ID, RECORD_ID, -1008, TOKEN_HASHES)));

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, RESULT_TOKEN, result()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_emptyEvents_rejectedBeforeOpeningTransaction() {
        givenProcessingTask();

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, RESULT_TOKEN,
                new AiTimelineResultRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_eventWithoutSourceRawIds_rejectedBeforeOpeningTransaction() {
        givenProcessingTask();

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, RESULT_TOKEN,
                new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                        TimelineEventType.MEAL, "점심", null,
                        OffsetDateTime.of(DATE.atTime(12, 0), KST), null, List.of())))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_eventEndingBeforeStart_rejected() {
        givenProcessingTask();

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, RESULT_TOKEN,
                new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                        TimelineEventType.MEAL, "점심", null,
                        OffsetDateTime.of(DATE.atTime(12, 0), KST),
                        OffsetDateTime.of(DATE.atTime(11, 0), KST), List.of("raw-1"))))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_blankTitle_rejected() {
        givenProcessingTask();

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, RESULT_TOKEN,
                new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                        TimelineEventType.MEAL, "   ", null,
                        OffsetDateTime.of(DATE.atTime(12, 0), KST), null, List.of("raw-1"))))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_titleTooLong_rejected() {
        givenProcessingTask();

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, RESULT_TOKEN,
                new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                        TimelineEventType.MEAL, "가".repeat(256), null,
                        OffsetDateTime.of(DATE.atTime(12, 0), KST), null, List.of("raw-1"))))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(timelineAiResultTransactionService);
    }

    @Test
    void storeResult_missingEventType_rejected() {
        givenProcessingTask();

        assertThatThrownBy(() -> service.storeResult(VERSION, TASK_ID, RESULT_TOKEN,
                new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                        null, "점심", null,
                        OffsetDateTime.of(DATE.atTime(12, 0), KST), null, List.of("raw-1"))))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(timelineAiResultTransactionService);
    }
}
