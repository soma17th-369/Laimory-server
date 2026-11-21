package com.laimory.server.aidispatch;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 접수 대기열을 long polling으로 소비하는 in-process worker(#515). poller 스레드 하나가 빈 slot 수만큼만 받아
 * 전용 executor에 넘기므로 내부 대기 작업이 없고, 실행 수는 slot 수로 묶인다. executor는 이 worker가 소유해
 * API·scheduler pool과 격리된다.
 *
 * <p>메시지 처리 의미(접수·보상)는 {@link DispatchMessageHandler}가 맡고, 이 클래스는 결과대로 메시지를 지우거나 잠시 뒤
 * 다시 받게 할 뿐이다. 처리 중 예외는 다시 받기로 처리한다 — 반복되면 큐의 {@code maxReceiveCount}가 dead-letter로 옮긴다.
 *
 * <p>종료: polling을 멈추고, 진행 중인 long poll이 돌아오면 받은 메시지까지 처리한 뒤 진행 중 작업을 기다린다.
 * long poll을 interrupt하지 않는다 — SQS가 이미 숨긴 메시지를 응답 중간에 버리면 visibility 동안 아무도 처리하지 못한다.
 */
@Slf4j
public class DispatchQueueWorker implements SmartLifecycle {

    static final int MAX_RECEIVE_BATCH = 10;
    static final Duration RETRY_LATER_DELAY = Duration.ofSeconds(5);
    private static final Duration RECEIVE_FAILURE_BACKOFF = Duration.ofSeconds(1);
    private static final Duration SLOT_POLL = Duration.ofSeconds(1);
    /** 종료 대기 상한. long poll(20s)이 돌아오고 진행 중 접수(limiter 대기 + ack)가 끝날 시간이다. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(25);

    private final DispatchQueue queue;
    private final DispatchMessageHandler handler;
    private final Sleeper sleeper;
    private final int slotCount;
    private final Semaphore slots;
    private final ThreadPoolTaskExecutor executor;
    private volatile boolean running;
    private Thread poller;

    public DispatchQueueWorker(DispatchQueue queue, DispatchMessageHandler handler, int slotCount, Sleeper sleeper) {
        if (slotCount < 1) {
            throw new IllegalStateException(
                    "app.ai.dispatch.worker.slots는 1 이상이어야 합니다(APP_AI_DISPATCH_WORKER_SLOTS).");
        }
        this.queue = queue;
        this.handler = handler;
        this.sleeper = sleeper;
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
            List<ReceivedDispatch> messages;
            try {
                messages = queue.receive(permits);
            } catch (RuntimeException e) {
                slots.release(permits);
                log.warn("AI 접수 대기열 수신 실패: error={}", e.toString());
                if (!sleepQuietly(RECEIVE_FAILURE_BACKOFF)) {
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

    private void submit(ReceivedDispatch message) {
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
            log.error("AI 접수 대기열 실행 거절: receiveCount={}", message.receiveCount(), e);
        }
    }

    /** 메시지 하나를 처리하고 결과대로 정리한다. */
    void process(ReceivedDispatch message) {
        DispatchMessageHandler.Result result;
        try {
            result = handler.handle(message);
        } catch (RuntimeException e) {
            log.error("AI 접수 대기열 메시지 처리 실패(다시 받는다): receiveCount={}", message.receiveCount(), e);
            result = DispatchMessageHandler.Result.RETRY_LATER;
        }
        try {
            switch (result) {
                case DONE -> queue.delete(message.receiptHandle());
                case RETRY_LATER -> queue.retryAfter(message.receiptHandle(), RETRY_LATER_DELAY);
                case LEAVE_FOR_DEAD_LETTER -> {
                    // visibility 만료 뒤 재전달되다가 maxReceiveCount를 넘으면 dead-letter로 간다.
                }
            }
        } catch (RuntimeException e) {
            // 지우지 못한 메시지는 visibility 뒤 다시 오고, handler의 중복 판정이 버린다.
            log.warn("AI 접수 대기열 메시지 정리 실패: result={} error={}", result, e.toString());
        }
    }

    private boolean sleepQuietly(Duration duration) {
        try {
            sleeper.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
