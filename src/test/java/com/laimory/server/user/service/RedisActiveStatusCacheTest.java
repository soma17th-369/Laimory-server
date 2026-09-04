package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * ACTIVE 검사 캐시(#429)의 어노테이션 계약 고정: 적중 시 DB 미호출, 음성(false) 미적재,
 * evict 후 재조회는 DB 재확인, miss 경로 DB 장애는 그대로 전파(필터 fail-closed 500 계약 유지).
 *
 * <p>저장소는 스탠드인 {@link ConcurrentMapCacheManager}다 — 검증 대상이 "어느 저장소에 어떻게
 * 쓰이는가"가 아니라 "{@code @Cacheable}/{@code @CacheEvict}가 이 메서드에 어떤 계약을 만드는가"라서다.
 * 다만 <b>빈 이름은 실제 매니저 이름과 같아야</b> 어노테이션의 {@code cacheManager} 지정이 풀린다 —
 * 이름이 어긋나면 컨텍스트가 뜨지 않아 오타가 여기서 잡힌다. 실 Redis 왕복(키 모양·TTL 고정·공유
 * DEL 전파)은 {@code RedisActiveStatusCacheIntegrationTest}가 담당한다.
 */
@SpringJUnitConfig(RedisActiveStatusCacheTest.CacheSliceConfig.class)
class RedisActiveStatusCacheTest {

    private static final long USER_ID = 42L;

    @Autowired
    private UserAccountService userAccountService;
    @Autowired
    private RedisActiveStatusCache cache;
    @Autowired
    private CacheManager activeStatusCacheManager;

    @BeforeEach
    void resetSlice() {
        reset(userAccountService);
        activeStatusCacheManager.getCache(RedisActiveStatusCache.CACHE_NAME).clear();
    }

    @Test
    void isActive_cacheHit_trustsCacheWithoutDbCall() {
        when(userAccountService.isActive(USER_ID)).thenReturn(true);

        assertThat(cache.isActive(USER_ID)).isTrue();
        assertThat(cache.isActive(USER_ID)).isTrue();

        // 두 번째 호출은 DB를 아예 안 본다 — 요청 고정비 제거의 본체이자 "hit는 캐시 신뢰" 의미론.
        verify(userAccountService, times(1)).isActive(USER_ID);
    }

    @Test
    void isActive_negativeResult_isNeverCached() {
        when(userAccountService.isActive(USER_ID)).thenReturn(false);

        assertThat(cache.isActive(USER_ID)).isFalse();
        assertThat(cache.isActive(USER_ID)).isFalse();

        // 음성 캐시 금지 — 탈퇴 판정이 캐시에 얼어붙으면 안 된다(#429 원칙 2).
        verify(userAccountService, times(2)).isActive(USER_ID);
        assertThat(activeStatusCacheManager.getCache(RedisActiveStatusCache.CACHE_NAME).get(USER_ID))
                .isNull();
    }

    @Test
    void evict_forcesDbReverificationOnNextCall() {
        when(userAccountService.isActive(USER_ID)).thenReturn(true).thenReturn(false);

        assertThat(cache.isActive(USER_ID)).isTrue();
        cache.evict(USER_ID);

        // 탈퇴 커밋 후 evict → 다음 검사는 miss → DB 재확인 → 차단.
        assertThat(cache.isActive(USER_ID)).isFalse();
        verify(userAccountService, times(2)).isActive(USER_ID);
    }

    @Test
    void isActive_missPathDbFailure_propagates() {
        when(userAccountService.isActive(USER_ID)).thenThrow(new IllegalStateException("db down"));

        // miss 경로의 DB 장애는 그대로 전파 — 필터의 fail-closed 500 -500 계약이 여기서 성립한다.
        assertThatThrownBy(() -> cache.isActive(USER_ID)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * {@code proxyTargetClass}는 슬라이스에 Boot의 AOP auto-config(기본값이 class proxying)가 없어서
     * 명시한다 — 이 빈은 인터페이스를 구현하므로 JDK 프록시가 되면 구체 타입 주입이 깨진다
     * (배선을 그 형태로 하는 곳은 {@code SecurityConfig}다).
     */
    @Configuration
    @EnableCaching(proxyTargetClass = true)
    static class CacheSliceConfig {

        /** 빈 이름이 곧 어노테이션의 {@code cacheManager} 지정값이다. */
        @Bean
        CacheManager activeStatusCacheManager() {
            return new ConcurrentMapCacheManager(RedisActiveStatusCache.CACHE_NAME);
        }

        @Bean
        UserAccountService userAccountService() {
            return mock(UserAccountService.class);
        }

        @Bean
        RedisActiveStatusCache redisActiveStatusCache(UserAccountService userAccountService) {
            return new RedisActiveStatusCache(userAccountService);
        }
    }
}
