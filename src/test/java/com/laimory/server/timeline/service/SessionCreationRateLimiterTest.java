package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.redis.RedisGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 세션 생성 limiter(#515)의 창 계산·대기 판정과 throttle 재시도. Redis 카운터는 mock이고 시계는 테스트가 움직인다 — 대기는
 * 실제로 자지 않고 요청 시간만큼 시계를 앞으로 민다. 여러 인스턴스의 실제 원자성은
 * {@code SessionCreationRateLimiterIntegrationTest}가 본다.
 */
class SessionCreationRateLimiterTest {

    private static final long PERMITS = 5;
    // 창(250ms) 시작에서 100ms 지난 시각 — 다음 창까지 150ms 남는다.
    private static final long START_MILLIS = 1_000_000_000_000L + 100;

    private final RedisGateway redis = mock(RedisGateway.class);
    private final MovableClock clock = new MovableClock(START_MILLIS);
    private final List<Duration> sleeps = new ArrayList<>();

    /** 대기를 기록하고 그만큼 시계를 민다. */
    private SessionCreationRateLimiter limiter() {
        return new SessionCreationRateLimiter(redis, clock, PERMITS) {
            @Override
            void sleep(Duration duration) {
                sleeps.add(duration);
                clock.advance(duration.toMillis());
            }
        };
    }

    // ── 허가 ──

    @Test
    void grantsWhileWindowCountIsWithinPermits() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenReturn(PERMITS);

        boolean granted = limiter().tryAcquire();

        assertThat(granted).isTrue();
        assertThat(sleeps).isEmpty();
        verify(redis).incrementWithTtlOnCreate(eq(SessionCreationRateLimiter.KEY_PREFIX + (START_MILLIS / 250)),
                eq(SessionCreationRateLimiter.WINDOW_KEY_TTL));
    }

    @Test
    void fullWindowWaitsForNextWindowAndCountsThere() {
        long firstWindow = START_MILLIS / 250;
        when(redis.incrementWithTtlOnCreate(eq(SessionCreationRateLimiter.KEY_PREFIX + firstWindow), any()))
                .thenReturn(PERMITS + 1);
        when(redis.incrementWithTtlOnCreate(eq(SessionCreationRateLimiter.KEY_PREFIX + (firstWindow + 1)), any()))
                .thenReturn(1L);

        boolean granted = limiter().tryAcquire();

        assertThat(granted).isTrue();
        // 다음 창 시작까지 남은 150ms에 jitter(0~50ms)를 더해 한 번 기다린다.
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(Duration.ofMillis(150), Duration.ofMillis(200));
    }

    @Test
    void permitWhoseWindowEndedWhileWaitingForRedisIsDiscardedAndRecountedInCurrentWindow() {
        // 창 0(100ms 지점)에서 INCR을 보냈는데 응답을 받고 다시 실행된 시각이 창 1이다(스레드가 멈췄다). 창 0 허가로
        // 지금 보내면 창 1 몫에 얹히므로 버리고 창 1에서 다시 센다.
        long firstWindow = START_MILLIS / 250;
        when(redis.incrementWithTtlOnCreate(eq(SessionCreationRateLimiter.KEY_PREFIX + firstWindow), any()))
                .thenAnswer(invocation -> {
                    clock.advance(200);
                    return 1L;
                });
        when(redis.incrementWithTtlOnCreate(eq(SessionCreationRateLimiter.KEY_PREFIX + (firstWindow + 1)), any()))
                .thenReturn(1L);

        boolean granted = limiter().tryAcquire();

        assertThat(granted).isTrue();
        InOrder order = inOrder(redis);
        order.verify(redis).incrementWithTtlOnCreate(eq(SessionCreationRateLimiter.KEY_PREFIX + firstWindow), any());
        order.verify(redis).incrementWithTtlOnCreate(
                eq(SessionCreationRateLimiter.KEY_PREFIX + (firstWindow + 1)), any());
        assertThat(sleeps).isEmpty();
    }

    @Test
    void discardedPermitPastDeadlineReturnsFalseWithoutRecounting() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenAnswer(invocation -> {
            clock.advance(200);
            return 1L;
        });

        boolean granted = limiter().tryAcquire(Duration.ZERO);

        assertThat(granted).isFalse();
        verify(redis, times(1)).incrementWithTtlOnCreate(anyString(), any());
    }

    @Test
    void returnsFalseOnceMaxWaitIsUsedUpByFullWindows() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenReturn(PERMITS + 1);

        boolean granted = limiter().tryAcquire();

        assertThat(granted).isFalse();
        assertThat(sleeps.stream().mapToLong(Duration::toMillis).sum())
                .isLessThanOrEqualTo(SessionCreationRateLimiter.MAX_WAIT.toMillis());
    }

    @Test
    void redisFailurePropagatesSoNothingIsSentUncounted() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> limiter().tryAcquire())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis down");
    }

    @Test
    void interruptedWaitReturnsFalseAndKeepsInterruptFlag() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenReturn(PERMITS + 1);
        SessionCreationRateLimiter interrupted = new SessionCreationRateLimiter(redis, clock, PERMITS) {
            @Override
            void sleep(Duration duration) throws InterruptedException {
                throw new InterruptedException("shutdown");
            }
        };

        boolean granted = interrupted.tryAcquire();

        assertThat(granted).isFalse();
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void rejectsNonPositivePermitsAtStartup() {
        assertThatThrownBy(() -> new SessionCreationRateLimiter(redis, clock, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AI_DISPATCH_LIMITER_PERMITS");
    }

    // ── throttle 재시도 ──

    @Test
    void throttledCallIsRetriedAfterBackoffWithNewPermit() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenReturn(1L);
        AtomicInteger calls = new AtomicInteger();

        limiter().runWithThrottleRetry(() -> {
            if (calls.incrementAndGet() == 1) {
                throw throttled();
            }
        });

        assertThat(calls).hasValue(2);
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(Duration.ofSeconds(1), Duration.ofMillis(1250));
        // 재시도 전에 허가를 다시 받는다.
        verify(redis).incrementWithTtlOnCreate(anyString(), any());
    }

    @Test
    void throttleAfterAllRetriesIsRethrownAsNotAccepted() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenReturn(1L);
        AtomicInteger calls = new AtomicInteger();
        TimelineAiDispatchThrottledException last = throttled();

        assertThatThrownBy(() -> limiter().runWithThrottleRetry(() -> {
            calls.incrementAndGet();
            throw last;
        })).isSameAs(last);

        assertThat(calls).hasValue(1 + SessionCreationRateLimiter.THROTTLE_BACKOFFS.size());
        assertThat(sleeps).hasSize(SessionCreationRateLimiter.THROTTLE_BACKOFFS.size());
    }

    @Test
    void retryWithoutPermitStopsAndRethrowsThrottle() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenReturn(PERMITS + 1);
        AtomicInteger calls = new AtomicInteger();
        TimelineAiDispatchThrottledException throttle = throttled();

        assertThatThrownBy(() -> limiter().runWithThrottleRetry(() -> {
            calls.incrementAndGet();
            throw throttle;
        })).isSameAs(throttle);

        assertThat(calls).hasValue(1);
    }

    @Test
    void nonThrottleRejectionIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        TimelineAiDispatchRejectedException rejected = new TimelineAiDispatchRejectedException("invalid", null);

        assertThatThrownBy(() -> limiter().runWithThrottleRetry(() -> {
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

        assertThatThrownBy(() -> limiter().runWithThrottleRetry(() -> {
            calls.incrementAndGet();
            throw unknown;
        })).isSameAs(unknown);

        assertThat(calls).hasValue(1);
    }

    private static TimelineAiDispatchThrottledException throttled() {
        return new TimelineAiDispatchThrottledException("throttled", null);
    }

    /** 테스트가 직접 움직이는 시계. */
    private static final class MovableClock extends Clock {

        private long millis;

        MovableClock(long millis) {
            this.millis = millis;
        }

        void advance(long delta) {
            millis += delta;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
