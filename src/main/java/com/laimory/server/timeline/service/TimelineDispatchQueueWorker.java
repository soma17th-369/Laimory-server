package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * TIMELINE 접수 대기열(SQS)을 받아 세션 생성 한도 안에서 AI에 접수시키는 worker(#515,
 * {@code app.ai.dispatch.worker.enabled=true}).
 *
 * <p>메시지 하나의 순서: 처리권({@code SET NX}) → task가 {@code DISPATCH_PENDING}인지 → 접수 대기 예산 → 세션 생성 허가 →
 * 새 token으로 {@code INPUT_PENDING} 전이(접수 <b>전에</b> 쓴다) → 접수. 접수 전에 처리 단계를 바꾸므로 그 뒤의 재전달은
 * 단계 검사에서 중복으로 버려지고, 같은 task가 AI에 두 번 가지 않는다. 처리권 수명({@link #CLAIM_TTL})은 visibility(60s)
 * 보다 짧아, 처리권을 잡고 전이 전에 죽은 메시지는 재전달 때 다시 잡혀 접수된다.
 *
 * <p>결과 분류는 inline 경로와 같다 — 미접수 확정은 환불·FAILED({@code -1009}), UNKNOWN은 PROCESSING 유지(재전송 없음,
 * 유실 타이머가 종결). throttle은 같은 실행 안에서 재시도한다. 처리가 끝난 메시지(접수·종결·중복)는 지우고, 접수 전
 * 실패는 {@link #RETRY_LATER_DELAY} 뒤 다시 받고, 해석할 수 없는 메시지는 건드리지 않아 dead-letter로 보낸다.
 *
 * <p>poller 스레드 하나가 빈 slot 수만큼만 long polling으로 받아 이 worker가 소유한 executor에 넘긴다 — 내부 대기 작업이
 * 없고, 실행 수는 slot 수로 묶이며, API·scheduler pool과 격리된다. 종료 시 long poll을 interrupt하지 않는다 — SQS가 이미
 * 숨긴 메시지를 응답 중간에 버리면 visibility 동안 아무도 처리하지 못하기 때문이다. 받은 메시지까지 처리한 뒤 진행 중
 * 작업을 기다린다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.dispatch.worker.enabled", havingValue = "true")
public class TimelineDispatchQueueWorker implements SmartLifecycle {

    /** 처리권 수명. 대기열 visibility timeout(60s)보다 5초 짧다 — 두 값은 함께 바꾼다. */
    static final Duration CLAIM_TTL = Duration.ofSeconds(55);
    static final Duration RETRY_LATER_DELAY = Duration.ofSeconds(5);
    /** long polling 대기. SQS 상한(20s)이다. */
    static final Duration RECEIVE_WAIT = Duration.ofSeconds(20);
    private static final int MAX_RECEIVE_BATCH = 10;
    private static final Duration RECEIVE_CALL_TIMEOUT = RECEIVE_WAIT.plusSeconds(10);
    private static final Duration RECEIVE_FAILURE_BACKOFF = Duration.ofSeconds(1);
    private static final Duration SLOT_POLL = Duration.ofSeconds(1);
    /** 종료 대기 상한. long poll(20s)이 돌아오고 진행 중 접수(허가 대기 + ack)가 끝날 시간이다. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(25);

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final TimelineTaskService timelineTaskService;
    private final TimelineAiDispatcher timelineAiDispatcher;
    private final TimelineDispatchCompensation compensation;
    private final SessionCreationRateLimiter limiter;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int slotCount;
    private final Semaphore slots;
    private final ThreadPoolTaskExecutor executor;
    private volatile boolean running;
    private Thread poller;

    public TimelineDispatchQueueWorker(SqsClient aiDispatchSqsClient,
                                       @Value("${app.ai.dispatch.queue.timeline-url:}") String queueUrl,
                                       @Value("${app.ai.dispatch.worker.slots:16}") int slotCount,
                                       TimelineTaskService timelineTaskService,
                                       TimelineAiDispatcher timelineAiDispatcher,
                                       TimelineDispatchCompensation compensation,
                                       SessionCreationRateLimiter limiter,
                                       ObjectMapper objectMapper, Clock clock) {
        if (slotCount < 1) {
            throw new IllegalStateException(
                    "app.ai.dispatch.worker.slots는 1 이상이어야 합니다(APP_AI_DISPATCH_WORKER_SLOTS).");
        }
        this.sqsClient = aiDispatchSqsClient;
        this.queueUrl = queueUrl;
        this.timelineTaskService = timelineTaskService;
        this.timelineAiDispatcher = timelineAiDispatcher;
        this.compensation = compensation;
        this.limiter = limiter;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.slotCount = slotCount;
        this.slots = new Semaphore(slotCount);
        this.executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(slotCount);
        executor.setMaxPoolSize(slotCount);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("ai-dispatch-");
    }

    @Override
    public void start() {
        executor.initialize();
        running = true;
        poller = new Thread(this::pollLoop, "ai-dispatch-poller");
        poller.start();
        log.info("AI 접수 대기열 worker 시작: slots={}", slotCount);
    }

    @Override
    public void stop() {
        running = false;
        long deadline = System.nanoTime() + STOP_WAIT.toNanos();
        try {
            if (poller != null) {
                poller.join(STOP_WAIT.toMillis());
            }
            long remaining = Math.max(0, deadline - System.nanoTime());
            if (slots.tryAcquire(slotCount, remaining, TimeUnit.NANOSECONDS)) {
                slots.release(slotCount);
            } else {
                // 남은 메시지는 visibility 뒤 다른 인스턴스(또는 재기동 뒤 이 인스턴스)가 다시 받는다.
                log.warn("AI 접수 대기열 worker 종료 대기 초과: inFlight={}", slotCount - slots.availablePermits());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdown();
        log.info("AI 접수 대기열 worker 종료");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        while (running) {
            int permits;
            try {
                permits = acquireSlots();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (permits == 0) {
                continue;
            }
            List<Message> messages;
            try {
                messages = receive(permits);
            } catch (RuntimeException e) {
                slots.release(permits);
                log.warn("AI 접수 대기열 수신 실패: error={}", e.toString());
                try {
                    Thread.sleep(RECEIVE_FAILURE_BACKOFF.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            slots.release(permits - messages.size());
            messages.forEach(this::submit);
        }
    }

    /** 빈 slot을 1~{@value #MAX_RECEIVE_BATCH}개 잡는다. 잡기 전에 종료되면 0이다. */
    private int acquireSlots() throws InterruptedException {
        while (running) {
            if (slots.tryAcquire(SLOT_POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                int acquired = 1;
                while (acquired < MAX_RECEIVE_BATCH && slots.tryAcquire()) {
                    acquired++;
                }
                return acquired;
            }
        }
        return 0;
    }

    private List<Message> receive(int maxMessages) {
        return sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds((int) RECEIVE_WAIT.toSeconds())
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .overrideConfiguration(override -> override.apiCallTimeout(RECEIVE_CALL_TIMEOUT))
                .build()).messages();
    }

    private void submit(Message message) {
        try {
            executor.execute(() -> {
                try {
                    process(message);
                } finally {
                    slots.release();
                }
            });
        } catch (TaskRejectedException e) {
            // slot만큼만 받으므로 생기지 않아야 한다. 메시지는 건드리지 않아 visibility 뒤 다시 온다.
            slots.release();
            log.error("AI 접수 대기열 실행 거절: receiveCount={}", receiveCount(message), e);
        }
    }

    /** 메시지 하나를 처리하고 결과대로 지우거나 잠시 뒤 다시 받게 한다. */
    void process(Message message) {
        Outcome outcome;
        try {
            outcome = handle(message);
        } catch (RuntimeException e) {
            log.error("AI 접수 대기열 메시지 처리 실패(다시 받는다): receiveCount={}", receiveCount(message), e);
            outcome = Outcome.RETRY_LATER;
        }
        try {
            switch (outcome) {
                case DONE -> sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build());
                case RETRY_LATER -> sqsClient.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .visibilityTimeout((int) RETRY_LATER_DELAY.toSeconds())
                        .build());
                case LEAVE_FOR_DEAD_LETTER -> {
                    // visibility 만료 뒤 재전달되다가 maxReceiveCount를 넘으면 dead-letter로 간다(포렌식 보존).
                }
            }
        } catch (RuntimeException e) {
            // 지우지 못한 메시지는 visibility 뒤 다시 오고, 처리 단계 검사가 중복으로 버린다.
            log.warn("AI 접수 대기열 메시지 정리 실패: outcome={} error={}", outcome, e.toString());
        }
    }

    private Outcome handle(Message received) {
        TimelineDispatchMessage message;
        try {
            message = TimelineDispatchMessage.decode(objectMapper, received.body());
        } catch (IllegalArgumentException e) {
            log.error("timeline 대기열 메시지 해석 실패(dead-letter로 보낸다): receiveCount={} detail={}",
                    receiveCount(received), e.getMessage());
            return Outcome.LEAVE_FOR_DEAD_LETTER;
        }

        String taskId = message.taskId();
        TimelineDraftTask accepted;
        String taskToken;
        try {
            if (!timelineTaskService.claimQueuedDispatch(taskId, CLAIM_TTL)) {
                log.info("timeline 대기열 중복 메시지(다른 worker가 처리 중): taskId={}", taskId);
                return Outcome.DONE;
            }
            Optional<TimelineDraftTask> found = timelineTaskService.find(taskId);
            if (found.isEmpty() || found.get().status() != TaskStatus.PROCESSING
                    || found.get().stage() != ProcessStage.DISPATCH_PENDING) {
                log.info("timeline 대기열 메시지 건너뜀(이미 접수·종결·만료): taskId={} status={} stage={}", taskId,
                        found.map(TimelineDraftTask::status).orElse(null),
                        found.map(TimelineDraftTask::stage).orElse(null));
                return Outcome.DONE;
            }
            TimelineDraftTask pending = found.get();
            if (pending.dailyRecordId() != message.dailyRecordId()) {
                log.error("timeline 대기열 메시지와 task의 dailyRecordId가 다르다(버린다): taskId={}", taskId);
                return Outcome.DONE;
            }
            Duration waited = Duration.between(pending.processingStartedAt(), clock.instant());
            if (waited.compareTo(TimelineTaskService.DISPATCH_QUEUE_WAIT_BUDGET) > 0) {
                log.warn("timeline 접수 대기 예산 초과(접수하지 않고 종결): taskId={} waitedMs={}",
                        taskId, waited.toMillis());
                compensation.closeNotAccepted(taskId, pending.subjectId(), pending.dailyRecordId(),
                        pending.tokenHash());
                return Outcome.DONE;
            }
            if (!limiter.tryAcquire()) {
                log.warn("timeline 세션 생성 허가 대기 초과(잠시 뒤 다시 받는다): taskId={}", taskId);
                releaseClaimQuietly(taskId);
                return Outcome.RETRY_LATER;
            }
            taskToken = TaskTokens.generate();
            accepted = pending.withTokenAndStage(TaskTokens.hash(taskToken), ProcessStage.INPUT_PENDING);
            if (!timelineTaskService.saveProcessingIfPresent(taskId, accepted)) {
                log.info("timeline 대기열 task가 접수 직전에 만료됐다: taskId={}", taskId);
                return Outcome.DONE;
            }
        } catch (RuntimeException e) {
            // 접수 전 실패(Redis·역직렬화 오류) — 처리권을 풀고 잠시 뒤 다시 받는다. 반복되면 dead-letter로 간다.
            log.warn("timeline 대기열 접수 준비 실패(잠시 뒤 다시 받는다): taskId={} receiveCount={} error={}",
                    taskId, receiveCount(received), e.toString());
            releaseClaimQuietly(taskId);
            return Outcome.RETRY_LATER;
        }

        AiTimelineDispatchRequest request =
                new AiTimelineDispatchRequest(taskId, taskToken, message.dailyRecordId(), message.window());
        try {
            limiter.runWithThrottleRetry(() -> timelineAiDispatcher.dispatch(request));
            log.info("timeline 대기열 접수 완료: taskId={}", taskId);
        } catch (TimelineAiDispatchRejectedException e) {
            log.warn("timeline ai dispatch rejected (미접수 확정): taskId={} detail={}", taskId, e.getMessage());
            compensation.closeNotAccepted(taskId, accepted.subjectId(), accepted.dailyRecordId(), accepted.tokenHash());
        } catch (RuntimeException e) {
            // UNKNOWN — AI가 이미 접수했을 수 있어 재전송하지 않는다. AI callback 또는 유실 타이머가 종결한다.
            log.error("timeline ai dispatch outcome unknown, keeping PROCESSING: taskId={} detail={}",
                    taskId, e.getMessage());
        }
        return Outcome.DONE;
    }

    private void releaseClaimQuietly(String taskId) {
        try {
            timelineTaskService.releaseQueuedDispatchClaim(taskId);
        } catch (RuntimeException e) {
            // 남은 처리권은 TTL(55s)이 풀고, 그 전에 다시 온 메시지는 중복으로 버려진다 — 유실 타이머가 종결한다.
            log.warn("timeline 대기열 처리권 해제 실패: taskId={} error={}", taskId, e.toString());
        }
    }

    private static String receiveCount(Message message) {
        return message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
    }

    private enum Outcome {
        /** 처리가 끝났다(접수·종결·중복 판정 포함) — 메시지를 지운다. */
        DONE,
        /** 접수 전에 실패했다 — 잠시 뒤 다시 받는다. 반복되면 dead-letter로 간다. */
        RETRY_LATER,
        /** 해석할 수 없는 메시지다 — 건드리지 않아 visibility 만료 뒤 재전달되다가 dead-letter로 간다. */
        LEAVE_FOR_DEAD_LETTER
    }
}
