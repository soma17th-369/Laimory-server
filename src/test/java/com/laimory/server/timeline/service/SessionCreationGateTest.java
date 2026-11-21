package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.laimory.server.aidispatch.SessionCreationRateLimiter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * throttle 재시도(#515): 용량 한도 거절만 잠시 뒤 다시 보내고, 재시도마다 세션 생성 허가를 다시 받는다. 대기는 실제로 자지
 * 않고 요청 시간만 기록한다.
 */
class SessionCreationGateTest {

    private final SessionCreationRateLimiter limiter = mock(SessionCreationRateLimiter.class);
    private final List<Duration> sleeps = new ArrayList<>();
    private final SessionCreationGate gate = new SessionCreationGate(limiter, sleeps::add);

    @Test
    void throttledCallIsRetriedAfterBackoffWithNewPermit() {
        when(limiter.tryAcquire(any())).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();

        gate.dispatch(() -> {
            if (calls.incrementAndGet() == 1) {
                throw throttled();
            }
        });

        assertThat(calls).hasValue(2);
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(Duration.ofSeconds(1), Duration.ofMillis(1250));
    }

    @Test
    void throttleAfterAllRetriesIsRethrownAsNotAccepted() {
        when(limiter.tryAcquire(any())).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        TimelineAiDispatchThrottledException last = throttled();

        assertThatThrownBy(() -> gate.dispatch(() -> {
            calls.incrementAndGet();
            throw last;
        })).isSameAs(last);

        assertThat(calls).hasValue(1 + SessionCreationGate.THROTTLE_BACKOFFS.size());
        assertThat(sleeps).hasSize(SessionCreationGate.THROTTLE_BACKOFFS.size());
    }

    @Test
    void retryWithoutPermitStopsAndRethrowsThrottle() {
        when(limiter.tryAcquire(any())).thenReturn(false);
        AtomicInteger calls = new AtomicInteger();
        TimelineAiDispatchThrottledException throttle = throttled();

        assertThatThrownBy(() -> gate.dispatch(() -> {
            calls.incrementAndGet();
            throw throttle;
        })).isSameAs(throttle);

        assertThat(calls).hasValue(1);
    }

    @Test
    void nonThrottleRejectionIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        TimelineAiDispatchRejectedException rejected = new TimelineAiDispatchRejectedException("invalid", null);

        assertThatThrownBy(() -> gate.dispatch(() -> {
            calls.incrementAndGet();
            throw rejected;
        })).isSameAs(rejected);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void unknownOutcomeIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException unknown = new IllegalStateException("read timeout");

        assertThatThrownBy(() -> gate.dispatch(() -> {
            calls.incrementAndGet();
            throw unknown;
        })).isSameAs(unknown);

        assertThat(calls).hasValue(1);
    }

    private static TimelineAiDispatchThrottledException throttled() {
        return new TimelineAiDispatchThrottledException("throttled", null);
    }
}
