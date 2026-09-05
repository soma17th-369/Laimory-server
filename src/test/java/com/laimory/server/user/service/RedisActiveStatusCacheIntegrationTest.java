package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.redis.RedisGateway;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 인증 캐시 ↔ 실 Redis·실 배선 검증(#429, change-impact의 "Redis key/value/TTL = unit + integration").
 * 여기서만 볼 수 있는 것은 셋이다 — ① 캐시 배선이 만드는 <b>실제 키 모양</b>이 환경 prefix를 포함해
 * 기존 계약과 같은지 ② TTL이 쓰기 시점 고정이고 조회(hit)가 연장하지 않는지 ③ 공유 저장소라 DEL이
 * 즉시 관측되는지. 캐시된 빈은 프록시라 {@code new}로 만들 수 없어 실 빈을 주입받고 DB 쪽만 mock한다.
 *
 * <p>표준 {@code cache.*} meter 노출도 여기서 확인한다 — 기동 시 캐시가 만들어져 있어야 바인딩되므로
 * ({@code initialCacheNames}/{@code setCacheNames}) 슬라이스로는 검증되지 않는 배선 조건이다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class RedisActiveStatusCacheIntegrationTest {

    // 실행 간 충돌을 피하는 임의 userId(양수) — 키는 테스트가 끝나면 지운다.
    private final long userId = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE);

    @Autowired
    private RedisActiveStatusCache cache;

    @Autowired
    private SubjectMappingService subjectMappingService;

    @MockitoBean
    private UserAccountService userAccountService;

    @Autowired
    private RedisGateway redisGateway;

    // TTL 검사는 gateway에 없는 PTTL이 필요하다 — 테스트는 arch 규칙 대상 밖이라 template 직접 사용.
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${app.redis.key-prefix:}")
    private String keyPrefix;

    @AfterEach
    void cleanUp() {
        redisGateway.delete(RedisActiveStatusCache.KEY_PREFIX + userId);
    }

    @Test
    void ttlIsFixedAtWriteAndNotExtendedByHits() throws Exception {
        when(userAccountService.isActive(userId)).thenReturn(true);

        assertThat(cache.isActive(userId)).isTrue();
        // 키 모양은 {환경 prefix}user:active:{userId} — 캐시 매니저가 붙이는 prefix가 계약이다.
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
        verify(userAccountService, times(1)).isActive(userId);
    }

    @Test
    void negativeResultIsNotWrittenToRedis() {
        when(userAccountService.isActive(userId)).thenReturn(false);

        assertThat(cache.isActive(userId)).isFalse();

        // 음성 미캐시는 키 부재로 확인한다 — 탈퇴 판정이 캐시에 얼어붙지 않는다(#429 원칙 2).
        assertThat(stringRedisTemplate.hasKey(keyPrefix + RedisActiveStatusCache.KEY_PREFIX + userId))
                .isFalse();
    }

    @Test
    void evictRemovesTheSharedKeyAndForcesDbReverification() {
        // 적재 시점엔 ACTIVE, evict(탈퇴) 뒤 재조회는 비활성 — 탈퇴 커밋 후 차단 시나리오.
        when(userAccountService.isActive(userId)).thenReturn(true).thenReturn(false);
        String rawKey = keyPrefix + RedisActiveStatusCache.KEY_PREFIX + userId;

        assertThat(cache.isActive(userId)).isTrue();
        cache.evict(userId);

        // 공유 저장소에서 키가 사라졌다는 것이 곧 "다른 WAS도 즉시 miss"라는 뜻이다.
        assertThat(stringRedisTemplate.hasKey(rawKey)).isFalse();
        assertThat(cache.isActive(userId)).isFalse();
        verify(userAccountService, times(2)).isActive(userId);
    }

    @Test
    void bothCachesExposeStandardCacheMeters() {
        when(userAccountService.isActive(userId)).thenReturn(true);
        cache.isActive(userId);
        // 매핑이 없는 임의 userId라 fail-closed로 끝나지만, 캐시 조회 자체는 일어난다.
        try {
            subjectMappingService.getRequired(userId);
        } catch (IllegalStateException expected) {
            // 매핑 누락 — 이 테스트의 관심사가 아니다.
        }

        // 기동 시 캐시가 만들어져 있어야 Spring Boot가 meter를 바인딩한다(#429 ⑥).
        assertThat(meterRegistry.find("cache.gets")
                .tag("cache", RedisActiveStatusCache.CACHE_NAME).meters()).isNotEmpty();
        assertThat(meterRegistry.find("cache.gets")
                .tag("cache", SubjectMappingService.CACHE_NAME).meters()).isNotEmpty();
    }
}
