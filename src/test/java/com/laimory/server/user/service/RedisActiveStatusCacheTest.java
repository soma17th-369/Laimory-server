package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.redis.RedisGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * ACTIVE 검사 캐시(#429)의 계약 고정: ACTIVE=true만 캐시(음성 미적재), 적중 시 DB 미호출,
 * Redis 장애 fail-safe-to-DB(GET=miss 간주·SET 무시·DEL은 TTL 수렴), miss 경로 DB 장애는
 * 그대로 전파(필터 fail-closed 500 계약 유지).
 */
class RedisActiveStatusCacheTest {

    private static final long USER_ID = 42L;
    private static final String KEY = RedisActiveStatusCache.KEY_PREFIX + USER_ID;

    private UserAccountService userAccountService;
    private RedisGateway redisGateway;
    private RedisActiveStatusCache cache;

    @BeforeEach
    void setUp() {
        userAccountService = mock(UserAccountService.class);
        redisGateway = mock(RedisGateway.class);
        cache = new RedisActiveStatusCache(userAccountService, redisGateway, new SimpleMeterRegistry());
    }

    @Test
    void isActive_cacheHit_trustsCacheWithoutDbCall() {
        when(redisGateway.get(KEY)).thenReturn(RedisActiveStatusCache.CACHED_VALUE);

        assertThat(cache.isActive(USER_ID)).isTrue();

        // 적중은 DB를 아예 안 본다 — 요청 고정비 제거의 본체이자 "hit는 캐시 신뢰" 의미론.
        verifyNoInteractions(userAccountService);
        verify(redisGateway, never()).set(Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    @Test
    void isActive_missAndActive_populatesWithWriteFixedTtl() {
        when(redisGateway.get(KEY)).thenReturn(null);
        when(userAccountService.isActive(USER_ID)).thenReturn(true);

        assertThat(cache.isActive(USER_ID)).isTrue();

        verify(redisGateway).set(KEY, RedisActiveStatusCache.CACHED_VALUE, RedisActiveStatusCache.TTL);
    }

    @Test
    void isActive_missAndInactive_neverCachesNegative() {
        when(redisGateway.get(KEY)).thenReturn(null);
        when(userAccountService.isActive(USER_ID)).thenReturn(false);

        assertThat(cache.isActive(USER_ID)).isFalse();

        // 음성 캐시 금지 — 탈퇴 판정이 캐시에 얼어붙으면 안 된다(#429 원칙 2).
        verify(redisGateway, never()).set(Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    @Test
    void isActive_redisReadFailure_fallsBackToDbWithoutWriteAttempt() {
        when(redisGateway.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));
        when(userAccountService.isActive(USER_ID)).thenReturn(true);

        // Redis 장애는 miss로 강등 — 인증을 Redis발 500으로 만들지도, 조용한 401로 숨기지도 않는다.
        assertThat(cache.isActive(USER_ID)).isTrue();

        // GET이 실패한 요청은 SET도 생략 — 장애 중 요청당 command timeout을 두 번 물지 않는다.
        verify(redisGateway, never()).set(Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    @Test
    void isActive_unknownCachedValue_isNotTrustedAndReverifiesAgainstDb() {
        when(redisGateway.get(KEY)).thenReturn("0");
        when(userAccountService.isActive(USER_ID)).thenReturn(true);

        assertThat(cache.isActive(USER_ID)).isTrue();

        // "1" 외 값은 hit가 아니다 — 손상·비호환 값이 DB 확인 없이 인증되지 않고, 정상 적재가 덮어쓴다.
        verify(userAccountService).isActive(USER_ID);
        verify(redisGateway).set(KEY, RedisActiveStatusCache.CACHED_VALUE, RedisActiveStatusCache.TTL);
    }

    @Test
    void isActive_redisWriteFailure_isSwallowed() {
        when(redisGateway.get(KEY)).thenReturn(null);
        when(userAccountService.isActive(USER_ID)).thenReturn(true);
        Mockito.doThrow(new QueryTimeoutException("timeout"))
                .when(redisGateway).set(KEY, RedisActiveStatusCache.CACHED_VALUE, RedisActiveStatusCache.TTL);

        assertThat(cache.isActive(USER_ID)).isTrue();
    }

    @Test
    void isActive_missPathDbFailure_propagates() {
        when(redisGateway.get(KEY)).thenReturn(null);
        when(userAccountService.isActive(USER_ID)).thenThrow(new IllegalStateException("db down"));

        // miss 경로의 DB 장애는 그대로 전파 — 필터의 fail-closed 500 -500 계약이 여기서 성립한다.
        assertThatThrownBy(() -> cache.isActive(USER_ID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void evict_deletesSharedKey() {
        cache.evict(USER_ID);

        verify(redisGateway).delete(KEY);
    }

    @Test
    void evict_deleteFailure_isSwallowedForTtlConvergence() {
        when(redisGateway.delete(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        // DEL 실패는 TTL 수렴(#429 정책 ⓐ) — 탈퇴 202를 캐시 장애로 실패시키지 않는다.
        cache.evict(USER_ID);
    }
}
