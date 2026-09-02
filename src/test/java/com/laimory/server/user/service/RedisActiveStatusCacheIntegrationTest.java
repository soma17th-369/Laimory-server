package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.redis.RedisGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ACTIVE 캐시 ↔ 실 Redis 왕복 검증(#429, change-impact의 "Redis key/value/TTL = unit + integration").
 * ① SET EX TTL이 쓰기 시점 고정이고 조회(hit)가 연장하지 않는지 ② 캐시 컴포넌트 인스턴스 2개가 같은
 * Redis를 볼 때 한쪽 DEL을 다른 쪽이 즉시 관측하는지(prod WAS 2대 topology) 실제 명령으로 확인한다.
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class RedisActiveStatusCacheIntegrationTest {

    // 실행 간 충돌을 피하는 임의 userId(양수) — 키는 테스트가 끝나면 지운다.
    private final long userId = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE);

    @Autowired
    private RedisGateway redisGateway;

    // TTL 검사는 gateway에 없는 PTTL이 필요하다 — 테스트는 arch 규칙 대상 밖이라 template 직접 사용.
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${app.redis.key-prefix:}")
    private String keyPrefix;

    @AfterEach
    void cleanUp() {
        redisGateway.delete(RedisActiveStatusCache.KEY_PREFIX + userId);
    }

    @Test
    void ttlIsFixedAtWriteAndNotExtendedByHits() throws Exception {
        UserAccountService delegate = mock(UserAccountService.class);
        when(delegate.isActive(userId)).thenReturn(true);
        RedisActiveStatusCache cache =
                new RedisActiveStatusCache(delegate, redisGateway, new SimpleMeterRegistry());

        assertThat(cache.isActive(userId)).isTrue();
        String rawKey = keyPrefix + RedisActiveStatusCache.KEY_PREFIX + userId;
        Long ttlAfterWrite = stringRedisTemplate.getExpire(rawKey, TimeUnit.MILLISECONDS);
        assertThat(ttlAfterWrite)
                .isGreaterThan(RedisActiveStatusCache.TTL.toMillis() - 30_000)
                .isLessThanOrEqualTo(RedisActiveStatusCache.TTL.toMillis());

        Thread.sleep(100); // hit가 TTL을 리셋하면 아래 값이 ttlAfterWrite 근처로 되돌아간다.
        assertThat(cache.isActive(userId)).isTrue();
        Long ttlAfterHit = stringRedisTemplate.getExpire(rawKey, TimeUnit.MILLISECONDS);
        assertThat(ttlAfterHit).isLessThan(ttlAfterWrite);

        // 그리고 hit는 DB를 다시 보지 않았어야 한다(적재 1회뿐).
        verify(delegate, times(1)).isActive(userId);
    }

    @Test
    void evictFromOneInstanceIsObservedImmediatelyByAnother() {
        UserAccountService delegateA = mock(UserAccountService.class);
        // 적재 시점엔 ACTIVE, evict(탈퇴) 뒤 재조회는 비활성 — 탈퇴 커밋 후 차단 시나리오.
        when(delegateA.isActive(userId)).thenReturn(true).thenReturn(false);
        RedisActiveStatusCache instanceA =
                new RedisActiveStatusCache(delegateA, redisGateway, new SimpleMeterRegistry());
        RedisActiveStatusCache instanceB =
                new RedisActiveStatusCache(mock(UserAccountService.class), redisGateway,
                        new SimpleMeterRegistry());

        assertThat(instanceA.isActive(userId)).isTrue();      // A가 적재
        instanceB.evict(userId);                              // 다른 인스턴스(=다른 WAS)가 탈퇴 evict

        // A의 다음 검사가 즉시 miss → DB 재확인 → 차단. 공유 저장소라 인스턴스 간 전파 지연이 없다.
        assertThat(instanceA.isActive(userId)).isFalse();
        verify(delegateA, times(2)).isActive(userId);
    }
}
