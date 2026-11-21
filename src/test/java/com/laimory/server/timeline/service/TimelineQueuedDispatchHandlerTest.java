package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laimory.server.aidispatch.DispatchMessageHandler.Result;
import com.laimory.server.aidispatch.ReceivedDispatch;
import com.laimory.server.aidispatch.SessionCreationRateLimiter;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.CreditRefundReason;
import com.laimory.server.credit.service.CreditService;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TIMELINE 대기열 worker 처리(#515)의 결과별 동작. 접수는 {@code INPUT_PENDING} 전이 뒤에만 일어나고, 미접수 확정·대기 예산
 * 초과는 inline 경로와 같은 보상(환불 {@code DISPATCH_REJECTED}·FAILED {@code -1009})으로 끝난다. 보상은 실물이고 그 leaf
 * 협력자만 mock이다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineQueuedDispatchHandlerTest {

    private static final String TASK_ID = "0190a1b2-0000-7000-8000-00000000abcd";
    private static final UUID SUBJECT_ID = TestSubjects.id(7L);
    private static final long RECORD_ID = 42L;
    private static final Instant STARTED_AT = Instant.parse("2026-11-21T03:00:00Z");
    private static final String PLACEHOLDER_HASH = TaskTokens.hash("placeholder-token-never-sent");
    private static final TimelineDraftTask.TimelineWindow LOCAL_WINDOW = new TimelineDraftTask.TimelineWindow(
            LocalDateTime.of(2026, 11, 21, 0, 0), LocalDateTime.of(2026, 11, 22, 0, 0));
    private static final AiTimelineDispatchRequest.Window OFFSET_WINDOW = new AiTimelineDispatchRequest.Window(
            OffsetDateTime.of(2026, 11, 21, 0, 0, 0, 0, ZoneOffset.ofHours(9)),
            OffsetDateTime.of(2026, 11, 22, 0, 0, 0, 0, ZoneOffset.ofHours(9)));

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineAiDispatcher timelineAiDispatcher;
    @Mock
    private CreditService creditService;
    @Mock
    private TimelineTaskLossTimerService lossTimerService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineDraftSourceItemService sourceItemService;
    @Mock
    private SessionCreationRateLimiter limiter;

    private final List<Duration> sleeps = new ArrayList<>();
    private TimelineQueuedDispatchHandler handler;

    @BeforeEach
    void setUp() {
        handler = handlerAt(STARTED_AT.plusSeconds(5));
    }

    private TimelineQueuedDispatchHandler handlerAt(Instant now) {
        TimelineDispatchCompensation compensation = new TimelineDispatchCompensation(creditService, lossTimerService,
                dailyRecordService, timelineTaskService, sourceItemService);
        return new TimelineQueuedDispatchHandler(timelineTaskService, timelineAiDispatcher, compensation,
                new SessionCreationGate(limiter, sleeps::add), objectMapper, Clock.fixed(now, ZoneOffset.UTC));
    }

    private ReceivedDispatch message(long dailyRecordId) {
        String body = TimelineDispatchMessage.of(new TimelineDispatchCommand(TASK_ID, SUBJECT_ID, dailyRecordId,
                LOCAL_WINDOW, OFFSET_WINDOW)).encode(objectMapper);
        return new ReceivedDispatch(body, "receipt-1", 1, STARTED_AT);
    }

    private static TimelineDraftTask dispatchPending() {
        return TimelineDraftTask.dispatchPending(SUBJECT_ID, RECORD_ID, LOCAL_WINDOW, PLACEHOLDER_HASH, STARTED_AT);
    }

    private void claimableTask(TimelineDraftTask task) {
        when(timelineTaskService.claimQueuedDispatch(TASK_ID, TimelineQueuedDispatchHandler.CLAIM_TTL))
                .thenReturn(true);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
    }

    private void acceptsStageWrite() {
        when(limiter.tryAcquire(any())).thenReturn(true);
        when(timelineTaskService.saveProcessingIfPresent(eq(TASK_ID), any())).thenReturn(true);
    }

    @Test
    void acceptedMessageWritesInputPendingBeforeDispatchingWithTheSameToken() {
        claimableTask(dispatchPending());
        acceptsStageWrite();

        Result result = handler.handle(message(RECORD_ID));

        assertThat(result).isEqualTo(Result.DONE);
        ArgumentCaptor<TimelineDraftTask> written = ArgumentCaptor.forClass(TimelineDraftTask.class);
        ArgumentCaptor<AiTimelineDispatchRequest> sent = ArgumentCaptor.forClass(AiTimelineDispatchRequest.class);
        InOrder order = inOrder(timelineTaskService, timelineAiDispatcher);
        order.verify(timelineTaskService).saveProcessingIfPresent(eq(TASK_ID), written.capture());
        order.verify(timelineAiDispatcher).dispatch(sent.capture());
        assertThat(written.getValue().stage()).isEqualTo(ProcessStage.INPUT_PENDING);
        assertThat(written.getValue().processingStartedAt()).isEqualTo(STARTED_AT);
        assertThat(written.getValue().tokenHash()).isEqualTo(TaskTokens.hash(sent.getValue().taskToken()));
        assertThat(sent.getValue().taskId()).isEqualTo(TASK_ID);
        assertThat(sent.getValue().dailyRecordId()).isEqualTo(RECORD_ID);
        assertThat(sent.getValue().window()).isEqualTo(OFFSET_WINDOW);
        verify(creditService, never()).refund(any(), anyString(), any());
    }

    @Test
    void duplicateDeliveryWhileAnotherWorkerHoldsClaimIsDoneWithoutDispatch() {
        when(timelineTaskService.claimQueuedDispatch(TASK_ID, TimelineQueuedDispatchHandler.CLAIM_TTL))
                .thenReturn(false);

        Result result = handler.handle(message(RECORD_ID));

        assertThat(result).isEqualTo(Result.DONE);
        verify(timelineAiDispatcher, never()).dispatch(any());
    }

    @Test
    void redeliveryAfterAcceptanceIsDoneWithoutSecondDispatch() {
        claimableTask(dispatchPending().withTokenAndStage(TaskTokens.hash("accepted"), ProcessStage.INPUT_PENDING));

        Result result = handler.handle(message(RECORD_ID));

        assertThat(result).isEqualTo(Result.DONE);
        verify(timelineAiDispatcher, never()).dispatch(any());
        verify(timelineTaskService, never()).saveProcessingIfPresent(anyString(), any());
    }

    @Test
    void messageForExpiredTaskIsDoneWithoutDispatch() {
        when(timelineTaskService.claimQueuedDispatch(TASK_ID, TimelineQueuedDispatchHandler.CLAIM_TTL))
                .thenReturn(true);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.empty());

        Result result = handler.handle(message(RECORD_ID));

        assertThat(result).isEqualTo(Result.DONE);
        verify(timelineAiDispatcher, never()).dispatch(any());
    }

    @Test
    void messageWithDifferentDailyRecordIsDiscardedWithoutDispatch() {
        claimableTask(dispatchPending());

        Result result = handler.handle(message(RECORD_ID + 1));

        assertThat(result).isEqualTo(Result.DONE);
        verify(timelineAiDispatcher, never()).dispatch(any());
    }

    @Test
    void taskWaitingLongerThanBudgetIsClosedAsNotAcceptedWithoutDispatch() {
        handler = handlerAt(STARTED_AT.plus(TimelineTaskService.DISPATCH_QUEUE_WAIT_BUDGET).plusSeconds(1));
        claimableTask(dispatchPending());

        Result result = handler.handle(message(RECORD_ID));

        assertThat(result).isEqualTo(Result.DONE);
        verify(timelineAiDispatcher, never()).dispatch(any());
        verify(creditService).refund(CreditCostType.TIMELINE_CREATION, TASK_ID, CreditRefundReason.DISPATCH_REJECTED);
        verify(lossTimerService).cancel(TASK_ID);
        verify(dailyRecordService).softDeleteFailedDraft(RECORD_ID);
        verify(timelineTaskService).markFailed(TASK_ID, SUBJECT_ID, RECORD_ID, ExceptionType.AI_DISPATCH_FAILED,
                PLACEHOLDER_HASH);
    }

    @Test
    void noSessionPermitReleasesClaimAndRetriesLaterWithoutTouchingTask() {
        claimableTask(dispatchPending());
        when(limiter.tryAcquire(any())).thenReturn(false);

        Result result = handler.handle(message(RECORD_ID));

        assertThat(result).isEqualTo(Result.RETRY_LATER);
        verify(timelineTaskService).releaseQueuedDispatchClaim(TASK_ID);
        verify(timelineTaskService, never()).saveProcessingIfPresent(anyString(), any());
        verify(timelineAiDispatcher, never()).dispatch(any());
    }

    @Test
    void redisFailureBeforeDispatchReleasesClaimAndRetriesLater() {
        when(timelineTaskService.claimQueuedDispatch(TASK_ID, TimelineQueuedDispatchHandler.CLAIM_TTL))
                .thenReturn(true);
        when(timelineTaskService.find(TASK_ID)).thenThrow(new IllegalStateException("redis down"));

        Result result = handler.handle(message(RECORD_ID));

        assertThat(result).isEqualTo(Result.RETRY_LATER);
        verify(timelineTaskService).releaseQueuedDispatchClaim(TASK_ID);
        verify(timelineAiDispatcher, never()).dispatch(any());
    }

    @Test
    void rejectedDispatchIsClosedAsNotAcceptedWithTheWrittenTokenHash() {
        claimableTask(dispatchPending());
        acceptsStageWrite();
        doThrow(new TimelineAiDispatchRejectedException("invalid", null)).when(timelineAiDispatcher).dispatch(any());

        Result result = handler.handle(message(RECORD_ID));

        assertThat(result).isEqualTo(Result.DONE);
        ArgumentCaptor<TimelineDraftTask> written = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService).saveProcessingIfPresent(eq(TASK_ID), written.capture());
        verify(creditService).refund(CreditCostType.TIMELINE_CREATION, TASK_ID, CreditRefundReason.DISPATCH_REJECTED);
        verify(timelineTaskService).markFailed(TASK_ID, SUBJECT_ID, RECORD_ID, ExceptionType.AI_DISPATCH_FAILED,
                written.getValue().tokenHash());
    }

    @Test
    void throttledDispatchIsRetriedAndAcceptedWithoutRefund() {
        claimableTask(dispatchPending());
        acceptsStageWrite();
        doThrow(new TimelineAiDispatchThrottledException("throttled", null))
                .doNothing()
                .when(timelineAiDispatcher).dispatch(any());

        Result result = handler.handle(message(RECORD_ID));

        assertThat(result).isEqualTo(Result.DONE);
        verify(timelineAiDispatcher, times(2)).dispatch(any());
        verify(creditService, never()).refund(any(), anyString(), any());
    }

    @Test
    void unknownOutcomeKeepsProcessingWithoutRefundOrResend() {
        claimableTask(dispatchPending());
        acceptsStageWrite();
        doThrow(new IllegalStateException("read timeout")).when(timelineAiDispatcher).dispatch(any());

        Result result = handler.handle(message(RECORD_ID));

        assertThat(result).isEqualTo(Result.DONE);
        verify(timelineAiDispatcher, times(1)).dispatch(any());
        verify(creditService, never()).refund(any(), anyString(), any());
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyLong(), any(), any());
    }

    @Test
    void unparsableMessageIsLeftForDeadLetterWithoutTouchingRedis() {
        Result result = handler.handle(new ReceivedDispatch("{not json", "receipt-1", 1, STARTED_AT));

        assertThat(result).isEqualTo(Result.LEAVE_FOR_DEAD_LETTER);
        verify(timelineTaskService, never()).claimQueuedDispatch(anyString(), any());
    }
}
