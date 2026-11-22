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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.CreditRefundReason;
import com.laimory.server.credit.service.CreditService;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * 대기열 적재 경로(#515): {@code DISPATCH_PENDING} 저장 뒤 식별자만 실은 메시지를 적재하고, 적재 거절(SQS 4xx)만 502로
 * 실패시킨다. SQS는 client mock이다(네트워크 없음).
 */
@ExtendWith(MockitoExtension.class)
class QueuedTimelineDispatchSubmitterTest {

    private static final String TASK_ID = "0190a1b2-0000-7000-8000-00000000abcd";
    private static final UUID SUBJECT_ID = TestSubjects.id(7L);
    private static final long RECORD_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-11-21T03:00:00Z");
    private static final TimelineDraftTask.TimelineWindow LOCAL_WINDOW = new TimelineDraftTask.TimelineWindow(
            LocalDateTime.of(2026, 11, 21, 0, 0), LocalDateTime.of(2026, 11, 22, 0, 0));
    private static final AiTimelineDispatchRequest.Window OFFSET_WINDOW = new AiTimelineDispatchRequest.Window(
            OffsetDateTime.of(2026, 11, 21, 0, 0, 0, 0, ZoneOffset.ofHours(9)),
            OffsetDateTime.of(2026, 11, 22, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
    private static final String QUEUE_URL =
            "https://sqs.ap-northeast-2.amazonaws.com/000000000000/laimory-dev-ai-dispatch-timeline";

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineTaskLossTimerService lossTimerService;
    @Mock
    private CreditService creditService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineDraftSourceItemService sourceItemService;
    @Mock
    private SqsClient sqsClient;

    private QueuedTimelineDispatchSubmitter submitter;

    @BeforeEach
    void setUp() {
        TimelineDispatchCompensation compensation = new TimelineDispatchCompensation(creditService, lossTimerService,
                dailyRecordService, timelineTaskService, sourceItemService);
        submitter = new QueuedTimelineDispatchSubmitter(timelineTaskService, lossTimerService, compensation, sqsClient,
                QUEUE_URL, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void savesDispatchPendingWithQueuedTimerThenEnqueuesIdentifiersOnly() throws Exception {
        submit();

        InOrder order = inOrder(lossTimerService, timelineTaskService, sqsClient);
        order.verify(lossTimerService).startForQueuedDispatch(TASK_ID);
        order.verify(timelineTaskService).createDispatchPending(eq(TASK_ID), eq(SUBJECT_ID), eq(RECORD_ID),
                eq(LOCAL_WINDOW), anyString(), eq(NOW));
        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        order.verify(sqsClient).sendMessage(sent.capture());
        assertThat(sent.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        JsonNode body = objectMapper.readTree(sent.getValue().messageBody());
        assertThat(body.get("requestType").asText()).isEqualTo("TIMELINE");
        assertThat(body.get("taskId").asText()).isEqualTo(TASK_ID);
        assertThat(body.get("dailyRecordId").asLong()).isEqualTo(RECORD_ID);
        assertThat(body.get("window").get("startAt").asText()).isEqualTo("2026-11-21T00:00:00+09:00");
        // token은 worker가 접수 직전에 만든다 — 메시지에 비밀이 없다.
        assertThat(body.has("taskToken")).isFalse();
        verify(lossTimerService, never()).start(anyString());
    }

    @Test
    void enqueueRejectionClosesAsNotAcceptedAndFailsWith502() {
        when(sqsClient.sendMessage(any(SendMessageRequest.class))).thenThrow(sqsError(403, "AccessDenied"));

        assertThatThrownBy(this::submit)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ExceptionType.AI_DISPATCH_FAILED.code()));

        ArgumentCaptor<String> placeholderHash = ArgumentCaptor.forClass(String.class);
        verify(timelineTaskService).createDispatchPending(eq(TASK_ID), eq(SUBJECT_ID), eq(RECORD_ID), eq(LOCAL_WINDOW),
                placeholderHash.capture(), eq(NOW));
        verify(creditService).refund(CreditCostType.TIMELINE_CREATION, TASK_ID, CreditRefundReason.DISPATCH_REJECTED);
        verify(dailyRecordService).softDeleteFailedDraft(RECORD_ID);
        verify(timelineTaskService).markFailed(TASK_ID, SUBJECT_ID, RECORD_ID, ExceptionType.AI_DISPATCH_FAILED,
                placeholderHash.getValue());
    }

    @Test
    void serverErrorOnEnqueueIsUnknownAndKeepsProcessingForLossTimer() {
        when(sqsClient.sendMessage(any(SendMessageRequest.class))).thenThrow(sqsError(500, "InternalError"));

        submit();

        verify(creditService, never()).refund(any(), anyString(), any());
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyLong(), any(), any());
    }

    @Test
    void timeoutOnEnqueueIsUnknownAndKeepsProcessingForLossTimer() {
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SdkClientException.create("api call timeout"));

        submit();

        verify(creditService, never()).refund(any(), anyString(), any());
        verify(timelineTaskService, never()).markFailed(anyString(), any(), anyLong(), any(), any());
    }

    @Test
    void taskSaveFailureAbandonsTaskAndRethrowsWithoutEnqueue() {
        RuntimeException redisDown = new IllegalStateException("redis down");
        doThrow(redisDown).when(timelineTaskService)
                .createDispatchPending(anyString(), any(), anyLong(), any(), anyString(), any());

        assertThatThrownBy(this::submit).isSameAs(redisDown);

        verify(sourceItemService).deleteByTaskId(TASK_ID);
        verify(creditService).refund(CreditCostType.TIMELINE_CREATION, TASK_ID, CreditRefundReason.TASK_SAVE_FAILED);
        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    private void submit() {
        submitter.submit(TASK_ID, SUBJECT_ID, RECORD_ID, LOCAL_WINDOW, OFFSET_WINDOW);
    }

    private static SqsException sqsError(int status, String code) {
        return (SqsException) SqsException.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .message(code)
                .build();
    }
}
