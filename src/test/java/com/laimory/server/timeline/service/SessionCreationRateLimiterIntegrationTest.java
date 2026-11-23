package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.common.redis.RedisMirror;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 두 인스턴스가 같은 Redis 카운터를 나눠 쓸 때 합산 허가가 어느 1초에도 {@code 5L}을 넘지 않는지 실 Redis로 본다(#515 AC2).
 *
 * <p>인스턴스 둘은 연결을 따로 가진 limiter 두 개다(같은 prefix). 허가 시각은 limiter가 그 허가의 창을 정할 때 읽은 시계 값이다 —
 * 반환 뒤에 찍으면 스케줄링 지연만큼 밀려 창 경계를 넘은 것처럼 보이기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class SessionCreationRateLimiterIntegrationTest {

    private static final long PERMITS = 2;
    private static final int THREADS_PER_INSTANCE = 4;
    private static final Duration RUN_FOR = Duration.ofSeconds(4);

    @Autowired
    private StringRedisTemplate primary;

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    private LettuceConnectionFactory secondInstanceConnection;
    private String prefix;

    @BeforeEach
    void setUp() {
        secondInstanceConnection = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisHost, redisPort));
        secondInstanceConnection.afterPropertiesSet();
        secondInstanceConnection.start();
        prefix = "limiter-it-" + UUID.randomUUID() + ":";
    }

    @AfterEach
    void tearDown() {
        Set<String> keys = primary.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) {
            primary.delete(keys);
        }
        secondInstanceConnection.destroy();
    }

    @Test
    void twoInstancesTogetherNeverExceedFivePermitWindowsInAnySecond() throws Exception {
        DecisionClock clock = new DecisionClock();
        SessionCreationRateLimiter instanceA = new SessionCreationRateLimiter(
                new RedisGateway(primary, disabledMirror(), prefix), clock, PERMITS);
        SessionCreationRateLimiter instanceB = new SessionCreationRateLimiter(
                new RedisGateway(new StringRedisTemplate(secondInstanceConnection), disabledMirror(), prefix),
                clock, PERMITS);
        List<Long> grantedAt = Collections.synchronizedList(new ArrayList<>());
        long stopAt = System.currentTimeMillis() + RUN_FOR.toMillis();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS_PER_INSTANCE * 2);
        CountDownLatch done = new CountDownLatch(THREADS_PER_INSTANCE * 2);
        for (SessionCreationRateLimiter limiter : List.of(instanceA, instanceB)) {
            for (int i = 0; i < THREADS_PER_INSTANCE; i++) {
                pool.execute(() -> {
                    while (System.currentTimeMillis() < stopAt) {
                        if (limiter.tryAcquire(Duration.ofSeconds(2))) {
                            grantedAt.add(clock.lastReadByThisThread());
                        }
                    }
                    done.countDown();
                });
            }
        }

        assertThat(done.await(RUN_FOR.toMillis() + 5_000, TimeUnit.MILLISECONDS)).isTrue();
        pool.shutdownNow();

        List<Long> sorted = grantedAt.stream().sorted().toList();
        assertThat((long) maxGrantsInAnySecond(sorted)).isLessThanOrEqualTo(5 * PERMITS);
        // 한도까지 실제로 채웠는지(평균 4L/s의 절반 이상) — 아무것도 허가하지 않아 통과하는 것을 막는다.
        assertThat(sorted.size()).isGreaterThanOrEqualTo((int) (RUN_FOR.toSeconds() * 4 * PERMITS / 2));
    }

    /** dual write secondary 없음(host 빈 값 = 비활성). */
    private static RedisMirror disabledMirror() {
        return new RedisMirror("", 6379, "default", "", false, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    /** 정렬된 허가 시각에서 반열린 1초 구간 {@code [t, t+1000)}에 드는 최대 허가 수. */
    private static int maxGrantsInAnySecond(List<Long> sorted) {
        int max = 0;
        int start = 0;
        for (int end = 0; end < sorted.size(); end++) {
            while (sorted.get(end) - sorted.get(start) >= 1000) {
                start++;
            }
            max = Math.max(max, end - start + 1);
        }
        return max;
    }

    /** 시스템 시계를 그대로 주되, 스레드마다 마지막으로 읽힌 값을 기억한다(허가 창을 정한 시각). */
    private static final class DecisionClock extends Clock {

        private final ThreadLocal<Long> lastRead = new ThreadLocal<>();

        long lastReadByThisThread() {
            return lastRead.get();
        }

        @Override
        public long millis() {
            long now = System.currentTimeMillis();
            lastRead.set(now);
            return now;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
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
