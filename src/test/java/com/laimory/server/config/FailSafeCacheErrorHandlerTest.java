package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * 캐시 저장소 장애를 삼키는 계약(#429): 네 연산 모두 예외를 밖으로 내보내지 않고, 조회 실패만
 * fallback counter를 올린다(적재·무효화 실패는 요청을 강등시키지 않으므로 세지 않는다).
 */
class FailSafeCacheErrorHandlerTest {

    private static final Cache CACHE = new ConcurrentMapCache("user:active");

    private SimpleMeterRegistry meterRegistry;
    private FailSafeCacheErrorHandler handler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        handler = new FailSafeCacheErrorHandler(meterRegistry);
    }

    @Test
    void getError_isSwallowedAndCounted() {
        assertThatCode(() -> handler.handleCacheGetError(
                new RedisConnectionFailureException("down"), CACHE, 1L))
                .doesNotThrowAnyException();

        assertThat(meterRegistry.counter(FailSafeCacheErrorHandler.FALLBACK_METER,
                "cache", CACHE.getName()).count()).isEqualTo(1.0d);
    }

    @Test
    void putEvictAndClearErrors_areSwallowedWithoutFallbackCount() {
        assertThatCode(() -> {
            handler.handleCachePutError(new QueryTimeoutException("timeout"), CACHE, 1L, true);
            handler.handleCacheEvictError(new RedisConnectionFailureException("down"), CACHE, 1L);
            handler.handleCacheClearError(new RedisConnectionFailureException("down"), CACHE);
        }).doesNotThrowAnyException();

        assertThat(meterRegistry.find(FailSafeCacheErrorHandler.FALLBACK_METER).counter()).isNull();
    }
}
