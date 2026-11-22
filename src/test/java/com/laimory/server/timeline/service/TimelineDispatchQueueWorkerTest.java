package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * TIMELINE 대기열 worker(#515). 메시지 하나의 결과별 동작(접수는 {@code INPUT_PENDING} 전이 뒤에만, 미접수 확정·대기 예산
 * 초과는 inline 경로와 같은 보상)과 메시지 정리(삭제·잠시 뒤 재수신), 실행 수 ≤ slot을 본다.
 * SQS는 client mock이고, 보상은 실물이며 그 leaf 협력자만 mock이다. limiter는 mock이되 throttle 재시도는 실물 로직을
 * 부른다(대기는 실제로 자지 않는다).
 */
@ExtendWith(MockitoExtension.class)
class TimelineDispatchQueueWorkerTest {

    private static final String QUEUE_URL =
            "https://sqs.ap-northeast-2.amazonaws.com/000000000000/laimory-dev-ai-dispatch-timeline";
    private static final String RECEIPT = "receipt-1";
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
    private SqsClient sqsClient;
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

    private final Queue<Message> receivable = new ConcurrentLinkedQueue<>();
    private final List<ReceiveMessageRequest> receiveRequests = new ArrayList<>();
    private TimelineDispatchQueueWorker worker;

    @BeforeEach
    void setUp() {
        lenient().doCallRealMethod().when(limiter).runWithThrottleRetry(any());
        worker = workerAt(STARTED_AT.plusSeconds(5), 1);
    }

    @AfterEach
    void tearDown() {
        if (worker.isRunning()) {
            worker.stop();
        }
    }

    private TimelineDispatchQueueWorker workerAt(Instant now, int slots) {
        TimelineDispatchCompensation compensation = new TimelineDispatchCompensation(creditService, lossTimerService,
                dailyRecordService, timelineTaskService, sourceItemService);
        return new TimelineDispatchQueueWorker(sqsClient, QUEUE_URL, slots, timelineTaskService, timelineAiDispatcher,
                compensation, limiter, objectMapper, Clock.fixed(now, ZoneOffset.UTC));
    }

    private Message message(String taskId, long dailyRecordId, String receipt) {
        return Message.builder()
                .body(TimelineDispatchMessage.of(taskId, dailyRecordId, OFFSET_WINDOW).encode(objectMapper))
                .receiptHandle(receipt)
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"))
                .build();
    }

    private static TimelineDraftTask dispatchPending() {
        return TimelineDraftTask.dispatchPending(SUBJECT_ID, RECORD_ID, LOCAL_WINDOW, PLACEHOLDER_HASH, STARTED_AT);
    }

    private void claimableTask(TimelineDraftTask task) {
        when(timelineTaskService.claimQueuedDispatch(TASK_ID, TimelineDispatchQueueWorker.CLAIM_TTL)).thenReturn(true);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.of(task));
    }

    private void acceptsStageWrite() {
        when(limiter.tryAcquire()).thenReturn(true);
        when(timelineTaskService.saveProcessingIfPresent(eq(TASK_ID), any())).thenReturn(true);
    }

    private void assertDeleted() {
        ArgumentCaptor<DeleteMessageRequest> request = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqsClient).deleteMessage(request.capture());
        assertThat(request.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(request.getValue().receiptHandle()).isEqualTo(RECEIPT);
        verify(sqsClient, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    }

    private void assertRetriedLater() {
        ArgumentCaptor<ChangeMessageVisibilityRequest> request =
                ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(sqsClient).changeMessageVisibility(request.capture());
        assertThat(request.getValue().receiptHandle()).isEqualTo(RECEIPT);
        assertThat(request.getValue().visibilityTimeout())
                .isEqualTo((int) TimelineDispatchQueueWorker.RETRY_LATER_DELAY.toSeconds());
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void acceptedMessageWritesInputPendingBeforeDispatchingWithTheSameToken() {
        claimableTask(dispatchPending());
        acceptsStageWrite();

        worker.process(message(TASK_ID, RECORD_ID, RECEIPT));

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
        // 메시지에서 읽은 window가 offset(+09:00)을 보존해야 AI 접수 계약 형식이 같다.
        assertThat(sent.getValue().window()).isEqualTo(OFFSET_WINDOW);
        assertThat(sent.getValue().window().startAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        verify(creditService, never()).refund(any(), anyString(), any());
        assertDeleted();
    }

    @Test
    void duplicateDeliveryWhileAnotherWorkerHoldsClaimIsDeletedWithoutDispatch() {
        when(timelineTaskService.claimQueuedDispatch(TASK_ID, TimelineDispatchQueueWorker.CLAIM_TTL))
                .thenReturn(false);

        worker.process(message(TASK_ID, RECORD_ID, RECEIPT));

        verify(timelineAiDispatcher, never()).dispatch(any());
        assertDeleted();
    }

    @Test
    void redeliveryAfterAcceptanceIsDeletedWithoutSecondDispatch() {
        claimableTask(dispatchPending().withTokenAndStage(TaskTokens.hash("accepted"), ProcessStage.INPUT_PENDING));

        worker.process(message(TASK_ID, RECORD_ID, RECEIPT));

        verify(timelineAiDispatcher, never()).dispatch(any());
        verify(timelineTaskService, never()).saveProcessingIfPresent(anyString(), any());
        assertDeleted();
    }

    @Test
    void messageForExpiredTaskIsDeletedWithoutDispatch() {
        when(timelineTaskService.claimQueuedDispatch(TASK_ID, TimelineDispatchQueueWorker.CLAIM_TTL))
                .thenReturn(true);
        when(timelineTaskService.find(TASK_ID)).thenReturn(Optional.empty());

        worker.process(message(TASK_ID, RECORD_ID, RECEIPT));

        verify(timelineAiDispatcher, never()).dispatch(any());
        assertDeleted();
    }

    @Test
    void messageWithDifferentDailyRecordIsDiscardedWithoutDispatch() {
        claimableTask(dispatchPending());

        worker.process(message(TASK_ID, RECORD_ID + 1, RECEIPT));

        verify(timelineAiDispatcher, never()).dispatch(any());
        assertDeleted();
    }

    @Test
    void taskWaitingLongerThanBudgetIsClosedAsNotAcceptedWithoutDispatch() {
        worker = workerAt(STARTED_AT.plus(TimelineTaskService.DISPATCH_QUEUE_WAIT_BUDGET).plusSeconds(1), 1);
        claimableTask(dispatchPending());

        worker.process(message(TASK_ID, RECORD_ID, RECEIPT));

        verify(timelineAiDispatcher, never()).dispatch(any());
        verify(creditService).refund(CreditCostType.TIMELINE_CREATION, TASK_ID,
                CreditRefundReason.DISPATCH_REJECTED);
        verify(lossTimerService).cancel(TASK_ID);
        verify(dailyRecordService).softDeleteFailedDraft(RECORD_ID);
        verify(timelineTaskService).markFailed(TASK_ID, SUBJECT_ID, RECORD_ID, ExceptionType.AI_DISPATCH_FAILED,
                PLACEHOLDER_HASH);
        assertDeleted();
    }

    @Test
    void noSessionPermitReleasesClaimAndRetriesLaterWithoutTouchingTask() {
        claimableTask(dispatchPending());
        when(limiter.tryAcquire()).thenReturn(false);

        worker.process(message(TASK_ID, RECORD_ID, RECEIPT));

        verify(timelineTaskService).releaseQueuedDispatchClaim(TASK_ID);
        verify(timelineTaskService, never()).saveProcessingIfPresent(anyString(), any());
        verify(timelineAiDispatcher, never()).dispatch(any());
        assertRetriedLater();
    }

    @Test
    void redisFailureBeforeDispatchReleasesClaimAndRetriesLater() {
        when(timelineTaskService.claimQueuedDispatch(TASK_ID, TimelineDispatchQueueWorker.CLAIM_TTL))
                .thenReturn(true);
        when(timelineTaskService.find(TASK_ID)).thenThrow(new IllegalStateException("redis down"));

        worker.process(message(TASK_ID, RECORD_ID, RECEIPT));

        verify(timelineTaskService).releaseQueuedDispatchClaim(TASK_ID);
        verify(timelineAiDispatcher, never()).dispatch(any());
        assertRetriedLater();
    }

    @Test
    void rejectedDispatchIsClosedAsNotAcceptedWithTheWrittenTokenHash() {
        claimableTask(dispatchPending());
        acceptsStageWrite();
        doThrow(new TimelineAiDispatchRejectedException("invalid", null))
                .when(timelineAiDispatcher).dispatch(any());

        worker.process(message(TASK_ID, RECORD_ID, RECEIPT));

        ArgumentCaptor<TimelineDraftTask> written = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskService).saveProcessingIfPresent(eq(TASK_ID), written.capture());
        verify(creditService).refund(CreditCostType.TIMELINE_CREATION, TASK_ID,
                CreditRefundReason.DISPATCH_REJECTED);
        verify(timelineTaskService).markFailed(TASK_ID, SUBJECT_ID, RECORD_ID, ExceptionType.AI_DISPATCH_FAILED,
                written.getValue().tokenHash());
        assertDeleted();
    }

    @Test
    void throttledDispatchIsRetriedAndAcceptedWithoutRefund() {
        claimableTask(dispatchPending());
        acceptsStageWrite();
        doThrow(new TimelineAiDispatchThrottledException("throttled", null))
                .doNothing()
                .when(timelineAiDispatcher).dispatch(any());

        worker.process(message(TASK_ID, RECORD_ID, RECEIPT));

        verify(timelineAiDispatcher, times(2)).dispatch(any());
        verify(creditService, never()).refund(any(), anyString(), any());
        assertDeleted();
    }

    @Test
    void unknownOutcomeKeepsProcessingWithoutRefundOrResend() {
        claimableTask(dispatchPending());
        acceptsStageWrite();
        doThrow(new IllegalStateException("read timeout")).when(timelineAiDispatcher).dispatch(any());

        worker.process(message(TASK_ID, RECORD_ID, RECEIPT));

        verify(timelineAiDispatcher, times(1)).dispatch(any());
        verify(creditService, never()).refund(any(), anyString(), any());
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyLong(), any(), any());
        assertDeleted();
    }

    @Test
    void unparsableMessageIsDeletedWithoutTouchingRedis() {
        // 연결된 task가 없어 다시 받아도 처리할 수 없다 — dead-letter 큐 없이 로그만 남기고 지운다.
        worker.process(Message.builder().messageId("m-1").body("{not json").receiptHandle(RECEIPT).build());

        verify(timelineTaskService, never()).claimQueuedDispatch(anyString(), any());
        assertDeleted();
    }

    /** long polling 흉내 — {@link #receivable}에서 요청 수만큼 주고, 빈 큐면 잠깐 쉬고 빈 응답을 준다. */
    private void stubLongPolling() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            ReceiveMessageRequest request = invocation.getArgument(0);
            synchronized (receiveRequests) {
                receiveRequests.add(request);
            }
            List<Message> batch = new ArrayList<>();
            Message next;
            while (batch.size() < request.maxNumberOfMessages() && (next = receivable.poll()) != null) {
                batch.add(next);
            }
            if (batch.isEmpty()) {
                Thread.sleep(10);
            }
            return ReceiveMessageResponse.builder().messages(batch).build();
        });
    }

    @Test
    void runningWorkerLongPollsWithinFreeSlotsAndDeletesHandledMessages() {
        stubLongPolling();
        receivable.add(message("task-1", RECORD_ID, "r1"));
        receivable.add(message("task-2", RECORD_ID, "r2"));
        // 다른 worker가 처리 중인 중복으로 판정 — 접수 없이 지워지는 가장 짧은 경로.
        when(timelineTaskService.claimQueuedDispatch(anyString(), any())).thenReturn(false);
        worker = workerAt(STARTED_AT, 2);

        worker.start();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(sqsClient, times(2)).deleteMessage(any(DeleteMessageRequest.class)));
        worker.stop();
        assertThat(worker.isRunning()).isFalse();
        synchronized (receiveRequests) {
            assertThat(receiveRequests).isNotEmpty().allSatisfy(request -> {
                assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
                assertThat(request.waitTimeSeconds()).isEqualTo(20);
                assertThat(request.maxNumberOfMessages()).isBetween(1, 2);
            });
        }
    }

    @Test
    void neverHoldsMoreMessagesThanSlots() {
        stubLongPolling();
        List.of("r1", "r2", "r3", "r4").forEach(receipt -> receivable.add(message(receipt, RECORD_ID, receipt)));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(timelineTaskService.claimQueuedDispatch(anyString(), any())).thenAnswer(invocation -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            release.await(5, TimeUnit.SECONDS);
            inFlight.decrementAndGet();
            return false;
        });
        worker = workerAt(STARTED_AT, 2);

        worker.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> inFlight.get() == 2);
        release.countDown();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(sqsClient, times(4)).deleteMessage(any(DeleteMessageRequest.class)));
        assertThat(maxInFlight).hasValue(2);
        verify(sqsClient, atLeastOnce()).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void rejectsNonPositiveSlotsAtStartup() {
        assertThatThrownBy(() -> workerAt(STARTED_AT, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AI_DISPATCH_WORKER_SLOTS");
    }
}
