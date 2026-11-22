package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * 접수 대기열(SQS)에 적재하는 경로({@code app.ai.dispatch.timeline.route=queue}, #515). AI 접수는 대기열 worker
 * ({@code TimelineDispatchQueueWorker})가 세션 생성률 한도 안에서 한다.
 *
 * <p>순서: 유실 타이머(큐 경로 처리 기한) → PROCESSING({@code DISPATCH_PENDING}) 저장 → 적재. 적재 거절(SQS 4xx)만
 * 502로 실패시키고, 적재 확인과 적재 여부 불명은 정상 반환한다 — 불명인데 실제로 적재되지 않았다면 유실 타이머가
 * 종결·환불한다. AI 거절은 이제 응답이 아니라 폴링 FAILED({@code -1009})로 보인다.
 *
 * <p>{@code DISPATCH_PENDING} task의 token hash는 원문을 버린 token의 hash다. worker가 접수 직전에 새 token으로 바꾸고,
 * 그 전에 FAILED로 종결하면 이 hash를 보존한다.
 *
 * <p>SDK 기본 재시도를 유지한다 — 적재가 중복돼도 worker의 처리권·처리 단계 검사가 두 번째를 버린다. 요청 스레드에서
 * 부르므로 시도·전체 상한을 짧게 건다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.ai.dispatch.timeline.route", havingValue = "queue")
public class QueuedTimelineDispatchSubmitter implements TimelineDispatchSubmitter {

    private static final Duration SEND_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SEND_CALL_TIMEOUT = Duration.ofSeconds(5);

    private final TimelineTaskService timelineTaskService;
    private final TimelineTaskLossTimerService timelineTaskLossTimerService;
    private final TimelineDispatchCompensation compensation;
    private final SqsClient sqsClient;
    private final String queueUrl;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public QueuedTimelineDispatchSubmitter(TimelineTaskService timelineTaskService,
                                           TimelineTaskLossTimerService timelineTaskLossTimerService,
                                           TimelineDispatchCompensation compensation,
                                           SqsClient aiDispatchSqsClient,
                                           @Value("${app.ai.dispatch.queue.timeline-url:}") String queueUrl,
                                           ObjectMapper objectMapper, Clock clock) {
        this.timelineTaskService = timelineTaskService;
        this.timelineTaskLossTimerService = timelineTaskLossTimerService;
        this.compensation = compensation;
        this.sqsClient = aiDispatchSqsClient;
        this.queueUrl = queueUrl;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void submit(String taskId, UUID subjectId, long dailyRecordId, TimelineDraftTask.TimelineWindow timelineWindow,
                       AiTimelineDispatchRequest.Window offsetWindow) {
        String placeholderTokenHash = TaskTokens.hash(TaskTokens.generate());
        Instant processingStartedAt = clock.instant();
        try {
            timelineTaskLossTimerService.startForQueuedDispatch(taskId);
            timelineTaskService.createDispatchPending(taskId, subjectId, dailyRecordId, timelineWindow,
                    placeholderTokenHash, processingStartedAt);
        } catch (RuntimeException e) {
            compensation.abandonUnsavedTask(taskId, dailyRecordId);
            throw e;
        }

        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(TimelineDispatchMessage.of(taskId, dailyRecordId, offsetWindow).encode(objectMapper))
                    .overrideConfiguration(override -> override
                            .apiCallAttemptTimeout(SEND_ATTEMPT_TIMEOUT)
                            .apiCallTimeout(SEND_CALL_TIMEOUT))
                    .build());
        } catch (SqsException e) {
            if (e.statusCode() < 400 || e.statusCode() >= 500) {
                logUnknownEnqueue(taskId, e);
                return;
            }
            // 요청 검증·권한·큐 부재로 거절 = 적재되지 않았음이 확실하다. 접수도 일어나지 않으므로 미접수 확정과 같이 종결한다.
            log.warn("timeline ai dispatch enqueue rejected (미적재 확정): taskId={} status={} code={}", taskId,
                    e.statusCode(), e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode());
            compensation.closeNotAccepted(taskId, subjectId, dailyRecordId, placeholderTokenHash);
            throw new BusinessException(ExceptionType.AI_DISPATCH_FAILED);
        } catch (RuntimeException e) {
            logUnknownEnqueue(taskId, e);
        }
    }

    /** 적재 여부 불명(timeout·전송 실패·5xx). 적재됐다면 worker가 접수하고, 아니라면 유실 타이머가 종결·환불한다. */
    private static void logUnknownEnqueue(String taskId, RuntimeException e) {
        log.error("timeline ai dispatch enqueue outcome unknown, returning taskId and keeping PROCESSING: "
                + "taskId={} detail={}", taskId, e.getMessage());
    }
}
