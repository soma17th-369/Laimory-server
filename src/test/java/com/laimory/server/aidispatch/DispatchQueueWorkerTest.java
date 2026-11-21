package com.laimory.server.aidispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.laimory.server.aidispatch.DispatchMessageHandler.Result;
import com.laimory.server.testsupport.InMemoryDispatchQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 대기열 worker(#515)의 메시지 수명: handler 결과대로 지우거나 잠시 뒤 다시 받게 하고, 받는 수가 빈 slot 수를 넘지 않는다.
 */
class DispatchQueueWorkerTest {

    private static final Sleeper NO_WAIT = duration -> { };

    private final InMemoryDispatchQueue queue = new InMemoryDispatchQueue();
    private DispatchQueueWorker worker;

    @AfterEach
    void tearDown() {
        if (worker != null && worker.isRunning()) {
            worker.stop();
        }
    }

    private static ReceivedDispatch message(String receipt) {
        return new ReceivedDispatch("{}", receipt, 1, Instant.parse("2026-11-21T03:00:00Z"));
    }

    @Test
    void doneMessageIsDeleted() {
        worker = new DispatchQueueWorker(queue, message -> Result.DONE, 1, NO_WAIT);

        worker.process(message("r1"));

        assertThat(queue.deleted()).containsExactly("r1");
        assertThat(queue.retried()).isEmpty();
    }

    @Test
    void retryLaterMessageBecomesVisibleAgainAfterShortDelay() {
        worker = new DispatchQueueWorker(queue, message -> Result.RETRY_LATER, 1, NO_WAIT);

        worker.process(message("r1"));

        assertThat(queue.retried()).containsEntry("r1", DispatchQueueWorker.RETRY_LATER_DELAY);
        assertThat(queue.deleted()).isEmpty();
    }

    @Test
    void unparsableMessageIsLeftUntouchedForDeadLetter() {
        worker = new DispatchQueueWorker(queue, message -> Result.LEAVE_FOR_DEAD_LETTER, 1, NO_WAIT);

        worker.process(message("r1"));

        assertThat(queue.deleted()).isEmpty();
        assertThat(queue.retried()).isEmpty();
    }

    @Test
    void handlerExceptionIsRetriedLaterInsteadOfDeleted() {
        worker = new DispatchQueueWorker(queue, message -> {
            throw new IllegalStateException("bug");
        }, 1, NO_WAIT);

        worker.process(message("r1"));

        assertThat(queue.retried()).containsKey("r1");
        assertThat(queue.deleted()).isEmpty();
    }

    @Test
    void runningWorkerProcessesQueuedMessagesAndStops() {
        queue.offer(message("r1"));
        queue.offer(message("r2"));
        worker = new DispatchQueueWorker(queue, message -> Result.DONE, 2, NO_WAIT);

        worker.start();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(queue.deleted()).containsExactlyInAnyOrder("r1", "r2"));
        worker.stop();
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void neverHoldsMoreMessagesThanSlots() throws Exception {
        List.of("r1", "r2", "r3", "r4").forEach(receipt -> queue.offer(message(receipt)));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        worker = new DispatchQueueWorker(queue, message -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return Result.DONE;
        }, 2, NO_WAIT);

        worker.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> inFlight.get() == 2);
        release.countDown();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(queue.deleted()).hasSize(4));
        assertThat(maxInFlight).hasValue(2);
    }

    @Test
    void rejectsNonPositiveSlotsAtStartup() {
        assertThatThrownBy(() -> new DispatchQueueWorker(queue, message -> Result.DONE, 0, NO_WAIT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AI_DISPATCH_WORKER_SLOTS");
    }
}
