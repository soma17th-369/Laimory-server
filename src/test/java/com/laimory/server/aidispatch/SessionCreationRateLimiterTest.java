package com.laimory.server.aidispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.Test;

/**
 * 세션 생성 limiter(#515)의 창 계산과 대기 판정. Redis 카운터는 mock이고 시계는 테스트가 움직인다 — 대기는 실제로 자지 않고
 * 요청 시간만큼 시계를 앞으로 민다. 여러 인스턴스의 실제 원자성은 {@code SessionCreationRateLimiterIntegrationTest}가 본다.
 */
class SessionCreationRateLimiterTest {

    private static final long PERMITS = 5;
    // 창(250ms) 시작에서 100ms 지난 시각 — 다음 창까지 150ms 남는다.
    private static final long START_MILLIS = 1_000_000_000_000L + 100;

    private final RedisGateway redis = mock(RedisGateway.class);
    private final MovableClock clock = new MovableClock(START_MILLIS);
    private final List<Duration> sleeps = new ArrayList<>();
    private final Sleeper advancingSleeper = duration -> {
        sleeps.add(duration);
        clock.advance(duration.toMillis());
    };

    @Test
    void grantsWhileWindowCountIsWithinPermits() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenReturn(PERMITS);
        SessionCreationRateLimiter limiter = new SessionCreationRateLimiter(redis, clock, advancingSleeper, PERMITS);

        boolean granted = limiter.tryAcquire(Duration.ofSeconds(10));

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
        SessionCreationRateLimiter limiter = new SessionCreationRateLimiter(redis, clock, advancingSleeper, PERMITS);

        boolean granted = limiter.tryAcquire(Duration.ofSeconds(10));

        assertThat(granted).isTrue();
        // 다음 창 시작까지 남은 150ms에 jitter(0~50ms)를 더해 한 번 기다린다.
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(Duration.ofMillis(150), Duration.ofMillis(200));
    }

    @Test
    void returnsFalseWithoutWaitingWhenNextWindowIsBeyondMaxWait() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenReturn(PERMITS + 1);
        SessionCreationRateLimiter limiter = new SessionCreationRateLimiter(redis, clock, advancingSleeper, PERMITS);

        boolean granted = limiter.tryAcquire(Duration.ofMillis(100));

        assertThat(granted).isFalse();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void returnsFalseOnceMaxWaitIsUsedUpByFullWindows() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenReturn(PERMITS + 1);
        SessionCreationRateLimiter limiter = new SessionCreationRateLimiter(redis, clock, advancingSleeper, PERMITS);

        boolean granted = limiter.tryAcquire(Duration.ofSeconds(1));

        assertThat(granted).isFalse();
        assertThat(sleeps.stream().mapToLong(Duration::toMillis).sum()).isLessThanOrEqualTo(1000);
    }

    @Test
    void redisFailurePropagatesSoNothingIsSentUncounted() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenThrow(new IllegalStateException("redis down"));
        SessionCreationRateLimiter limiter = new SessionCreationRateLimiter(redis, clock, advancingSleeper, PERMITS);

        assertThatThrownBy(() -> limiter.tryAcquire(Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis down");
    }

    @Test
    void interruptedWaitReturnsFalseAndKeepsInterruptFlag() {
        when(redis.incrementWithTtlOnCreate(anyString(), any())).thenReturn(PERMITS + 1);
        Sleeper interrupted = duration -> {
            throw new InterruptedException("shutdown");
        };
        SessionCreationRateLimiter limiter = new SessionCreationRateLimiter(redis, clock, interrupted, PERMITS);

        boolean granted = limiter.tryAcquire(Duration.ofSeconds(10));

        assertThat(granted).isFalse();
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void rejectsNonPositivePermitsAtStartup() {
        assertThatThrownBy(() -> new SessionCreationRateLimiter(redis, clock, advancingSleeper, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_AI_DISPATCH_LIMITER_PERMITS");
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
