package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.aidispatch.DispatchMessageHandler;
import com.laimory.server.aidispatch.ReceivedDispatch;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * TIMELINE 접수 대기열 메시지를 AI에 접수시키는 worker 처리(#515, {@code app.ai.dispatch.worker.enabled=true}).
 *
 * <p>순서: 처리권({@code SET NX}) → task가 {@code DISPATCH_PENDING}인지 → 접수 대기 예산 → 세션 생성 허가 → 새 token으로
 * {@code INPUT_PENDING} 전이(접수 <b>전에</b> 쓴다) → 접수. 접수 전에 처리 단계를 바꾸므로 그 뒤의 재전달은 단계 검사에서
 * 중복으로 버려지고, 같은 task가 AI에 두 번 가지 않는다. 처리권 수명({@link #CLAIM_TTL})은 visibility(60s)보다 짧아,
 * 처리권을 잡고 전이 전에 죽은 메시지는 재전달 때 다시 잡혀 접수된다.
 *
 * <p>결과 분류는 inline 경로와 같다 — 미접수 확정은 환불·FAILED({@code -1009}), UNKNOWN은 PROCESSING 유지(재전송 없음,
 * 유실 타이머가 종결). throttle은 같은 실행 안에서 재시도한다({@link SessionCreationGate}). 결과 중복 반영은 기존 token
 * 회전·결과 선점이 막는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.dispatch.worker.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TimelineQueuedDispatchHandler implements DispatchMessageHandler {

    /** 처리권 수명. 대기열 visibility timeout(60s)보다 5초 짧다 — 두 값은 함께 바꾼다. */
    static final Duration CLAIM_TTL = Duration.ofSeconds(55);

    private final TimelineTaskService timelineTaskService;
    private final TimelineAiDispatcher timelineAiDispatcher;
    private final TimelineDispatchCompensation compensation;
    private final SessionCreationGate gate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public Result handle(ReceivedDispatch received) {
        TimelineDispatchMessage message;
        try {
            message = TimelineDispatchMessage.decode(objectMapper, received.body());
        } catch (IllegalArgumentException e) {
            log.error("timeline 대기열 메시지 해석 실패(dead-letter로 보낸다): receiveCount={} detail={}",
                    received.receiveCount(), e.getMessage());
            return Result.LEAVE_FOR_DEAD_LETTER;
        }

        String taskId = message.taskId();
        TimelineDraftTask accepted;
        String taskToken;
        try {
            if (!timelineTaskService.claimQueuedDispatch(taskId, CLAIM_TTL)) {
                log.info("timeline 대기열 중복 메시지(다른 worker가 처리 중): taskId={}", taskId);
                return Result.DONE;
            }
            Optional<TimelineDraftTask> found = timelineTaskService.find(taskId);
            if (found.isEmpty() || found.get().status() != TaskStatus.PROCESSING
                    || found.get().stage() != ProcessStage.DISPATCH_PENDING) {
                log.info("timeline 대기열 메시지 건너뜀(이미 접수·종결·만료): taskId={} status={} stage={}", taskId,
                        found.map(TimelineDraftTask::status).orElse(null),
                        found.map(TimelineDraftTask::stage).orElse(null));
                return Result.DONE;
            }
            TimelineDraftTask pending = found.get();
            if (pending.dailyRecordId() != message.dailyRecordId()) {
                log.error("timeline 대기열 메시지와 task의 dailyRecordId가 다르다(버린다): taskId={}", taskId);
                return Result.DONE;
            }
            Duration waited = Duration.between(pending.processingStartedAt(), clock.instant());
            if (waited.compareTo(TimelineTaskService.DISPATCH_QUEUE_WAIT_BUDGET) > 0) {
                log.warn("timeline 접수 대기 예산 초과(접수하지 않고 종결): taskId={} waitedMs={}",
                        taskId, waited.toMillis());
                compensation.closeNotAccepted(taskId, pending.subjectId(), pending.dailyRecordId(),
                        pending.tokenHash());
                return Result.DONE;
            }
            if (!gate.acquirePermit()) {
                log.warn("timeline 세션 생성 허가 대기 초과(잠시 뒤 다시 받는다): taskId={}", taskId);
                releaseClaimQuietly(taskId);
                return Result.RETRY_LATER;
            }
            taskToken = TaskTokens.generate();
            accepted = pending.withTokenAndStage(TaskTokens.hash(taskToken), ProcessStage.INPUT_PENDING);
            if (!timelineTaskService.saveProcessingIfPresent(taskId, accepted)) {
                log.info("timeline 대기열 task가 접수 직전에 만료됐다: taskId={}", taskId);
                return Result.DONE;
            }
        } catch (RuntimeException e) {
            // 접수 전 실패(Redis·역직렬화 오류) — 처리권을 풀고 잠시 뒤 다시 받는다. 반복되면 dead-letter로 간다.
            log.warn("timeline 대기열 접수 준비 실패(잠시 뒤 다시 받는다): taskId={} receiveCount={} error={}",
                    taskId, received.receiveCount(), e.toString());
            releaseClaimQuietly(taskId);
            return Result.RETRY_LATER;
        }

        dispatch(accepted, taskId, taskToken, message);
        return Result.DONE;
    }

    private void dispatch(TimelineDraftTask task, String taskId, String taskToken, TimelineDispatchMessage message) {
        AiTimelineDispatchRequest request =
                new AiTimelineDispatchRequest(taskId, taskToken, message.dailyRecordId(), message.window());
        try {
            gate.dispatch(() -> timelineAiDispatcher.dispatch(request));
            log.info("timeline 대기열 접수 완료: taskId={}", taskId);
        } catch (TimelineAiDispatchRejectedException e) {
            log.warn("timeline ai dispatch rejected (미접수 확정): taskId={} detail={}", taskId, e.getMessage());
            compensation.closeNotAccepted(taskId, task.subjectId(), task.dailyRecordId(), task.tokenHash());
        } catch (RuntimeException e) {
            // UNKNOWN — AI가 이미 접수했을 수 있어 재전송하지 않는다. AI callback 또는 유실 타이머가 종결한다.
            log.error("timeline ai dispatch outcome unknown, keeping PROCESSING: taskId={} detail={}",
                    taskId, e.getMessage());
        }
    }

    private void releaseClaimQuietly(String taskId) {
        try {
            timelineTaskService.releaseQueuedDispatchClaim(taskId);
        } catch (RuntimeException e) {
            // 남은 처리권은 TTL(55s)이 풀고, 그 전에 다시 온 메시지는 중복으로 버려진다 — 유실 타이머가 종결한다.
            log.warn("timeline 대기열 처리권 해제 실패: taskId={} error={}", taskId, e.toString());
        }
    }
}
