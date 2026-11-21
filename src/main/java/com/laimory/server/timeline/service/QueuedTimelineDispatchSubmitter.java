package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.aidispatch.DispatchQueue;
import com.laimory.server.aidispatch.DispatchQueueSendRejectedException;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TaskTokens;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 접수 대기열에 적재하는 경로({@code app.ai.dispatch.timeline.route=queue}, #515). AI 접수는 대기열 worker
 * ({@link TimelineQueuedDispatchHandler})가 세션 생성률 한도 안에서 한다.
 *
 * <p>순서: 유실 타이머(큐 경로 처리 기한) → PROCESSING({@code DISPATCH_PENDING}) 저장 → 적재. 적재 거절(SQS 4xx)만
 * 502로 실패시키고, 적재 확인과 적재 여부 불명은 정상 반환한다 — 불명인데 실제로 적재되지 않았다면 유실 타이머가
 * 종결·환불한다. AI 거절은 이제 응답이 아니라 폴링 FAILED({@code -1009})로 보인다.
 *
 * <p>{@code DISPATCH_PENDING} task의 token hash는 원문을 버린 token의 hash다. worker가 접수 직전에 새 token으로 바꾸고,
 * 그 전에 FAILED로 종결하면 이 hash를 보존한다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.ai.dispatch.timeline.route", havingValue = "queue")
@RequiredArgsConstructor
public class QueuedTimelineDispatchSubmitter implements TimelineDispatchSubmitter {

    private final TimelineTaskService timelineTaskService;
    private final TimelineTaskLossTimerService timelineTaskLossTimerService;
    private final DispatchQueue timelineDispatchQueue;
    private final TimelineDispatchCompensation compensation;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void submit(TimelineDispatchCommand command) {
        String taskId = command.taskId();
        String placeholderTokenHash = TaskTokens.hash(TaskTokens.generate());
        Instant processingStartedAt = clock.instant();
        try {
            timelineTaskLossTimerService.startForQueuedDispatch(taskId);
            timelineTaskService.createDispatchPending(taskId, command.subjectId(), command.dailyRecordId(),
                    command.timelineWindow(), placeholderTokenHash, processingStartedAt);
        } catch (RuntimeException e) {
            compensation.abandonUnsavedTask(taskId, command.dailyRecordId());
            throw e;
        }

        try {
            timelineDispatchQueue.send(TimelineDispatchMessage.of(command).encode(objectMapper));
        } catch (DispatchQueueSendRejectedException e) {
            // 적재되지 않았음이 확실하다 — 접수도 일어나지 않으므로 미접수 확정과 같이 종결한다.
            log.warn("timeline ai dispatch enqueue rejected (미적재 확정): taskId={} detail={}", taskId, e.getMessage());
            compensation.closeNotAccepted(taskId, command.subjectId(), command.dailyRecordId(), placeholderTokenHash);
            throw new BusinessException(ExceptionType.AI_DISPATCH_FAILED);
        } catch (RuntimeException e) {
            // 적재 여부 불명(timeout·전송 실패·5xx). 적재됐다면 worker가 접수하고, 아니라면 유실 타이머가 종결·환불한다.
            log.error("timeline ai dispatch enqueue outcome unknown, returning taskId and keeping PROCESSING: "
                    + "taskId={} detail={}", taskId, e.getMessage());
        }
    }
}
