package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * {@link CacheConfig}가 {@link FailSafeCacheErrorHandler}를 <b>실제로 물렸는지</b> 확인한다(#429).
 * {@code CacheErrorHandler}를 그냥 빈으로 두면 Spring이 조회하지 않아 조용히 기본 handler(예외 전파)가
 * 쓰이므로 — 즉 캐시 저장소 장애가 그대로 500이 되므로 — 배선 자체를 테스트로 고정한다.
 *
 * <p>항상 던지는 스탠드인 캐시로 저장소 완전 장애를 흉내 내고, 캐시된 호출이 예외 없이 로더 결과를
 * 돌려주는지(GET/PUT 삼킴)와 무효화가 예외 없이 끝나는지를 본다.
 */
@SpringJUnitConfig(CacheErrorHandlerWiringTest.ThrowingCacheConfig.class)
class CacheErrorHandlerWiringTest {

    @Autowired
    private ThrowingCacheClient client;

    @Test
    void storeFailureIsDegradedToLoaderInsteadOfPropagating() {
        AtomicInteger loads = new AtomicInteger();

        assertThatCode(() -> assertThat(client.load(1L, loads)).isEqualTo("loaded-1"))
                .doesNotThrowAnyException();
        // GET이 실패해 적재 시도(PUT)까지 실패했으므로 다음 호출도 로더를 탄다 — 강등이지 실패가 아니다.
        assertThat(client.load(1L, loads)).isEqualTo("loaded-1");
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void evictFailureDoesNotPropagate() {
        assertThatCode(() -> client.evict(1L)).doesNotThrowAnyException();
    }

    static class ThrowingCacheClient {

        @Cacheable(cacheNames = "throwing", cacheManager = "throwingManager")
        String load(long key, AtomicInteger loads) {
            loads.incrementAndGet();
            return "loaded-" + key;
        }

        @CacheEvict(cacheNames = "throwing", cacheManager = "throwingManager")
        void evict(long key) {
        }
    }

    /** 모든 저장소 연산이 실패하는 캐시 — 완전 장애를 흉내 낸다. */
    static class ThrowingCache implements Cache {

        @Override
        public String getName() {
            return "throwing";
        }

        @Override
        public Object getNativeCache() {
            return this;
        }

        @Override
        public ValueWrapper get(Object key) {
            throw new IllegalStateException("cache store is down");
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            throw new IllegalStateException("cache store is down");
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            throw new IllegalStateException("cache store is down");
        }

        @Override
        public void put(Object key, Object value) {
            throw new IllegalStateException("cache store is down");
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            throw new IllegalStateException("cache store is down");
        }

        @Override
        public void evict(Object key) {
            throw new IllegalStateException("cache store is down");
        }

        @Override
        public void clear() {
            throw new IllegalStateException("cache store is down");
        }
    }

    @Configuration
    @Import(CacheConfig.class)
    static class ThrowingCacheConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return mock(RedisConnectionFactory.class);
        }

        @Bean
        CacheManager throwingManager() {
            return new CacheManager() {

                private final Cache cache = new ThrowingCache();

                @Override
                public Cache getCache(String name) {
                    return cache;
                }

                @Override
                public List<String> getCacheNames() {
                    return List.of("throwing");
                }
            };
        }

        @Bean
        ThrowingCacheClient throwingCacheClient() {
            return new ThrowingCacheClient();
        }
    }
}
