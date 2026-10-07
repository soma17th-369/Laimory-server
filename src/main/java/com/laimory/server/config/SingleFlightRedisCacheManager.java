package com.laimory.server.config;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;

/**
 * 모든 Redis 캐시를 {@link SingleFlightRedisCache}로 만드는 매니저(#491) — 그 외 동작(조회·실행 중 캐시 생성·통계)은
 * 기본 매니저와 같다. 배선(writer·통계 수집기·캐시별 TTL)은 {@link CacheConfig#redisCacheManager}가 소유한다.
 *
 * <p>{@code RedisCacheManager.builder(...)}는 항상 {@link RedisCacheManager} 자체를 만들어 하위 클래스를 쓸 수 없으므로
 * 생성자로 만든다. Spring Data Redis를 올릴 때 이 클래스가 기대는 {@link RedisCache} 생성자와
 * {@link RedisCacheManager#createRedisCache} 시그니처를 함께 점검한다.
 */
final class SingleFlightRedisCacheManager extends RedisCacheManager {

    SingleFlightRedisCacheManager(RedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration,
                                  Map<String, RedisCacheConfiguration> initialCacheConfigurations) {
        super(cacheWriter, defaultCacheConfiguration, true, initialCacheConfigurations);
    }

    @Override
    protected RedisCache createRedisCache(String name, RedisCacheConfiguration cacheConfiguration) {
        return new SingleFlightRedisCache(name, getCacheWriter(),
                cacheConfiguration != null ? cacheConfiguration : getDefaultCacheConfiguration());
    }

    /**
     * {@code @Cacheable(sync = true)}의 값 적재를 <b>서버(JVM) 안에서 키별로 한 번</b>만 실행하는 Redis 캐시(#491).
     *
     * <p>Spring은 {@code sync = true}의 중복 적재 방지를 {@link #get(Object, Callable)} 구현에 맡기는데, 기본
     * {@link RedisCache}와 non-locking writer는 동기화를 하지 않는다(Spring Data Redis 3.4부터 값 적재 동기화가
     * {@code RedisCache}의 인스턴스 잠금에서 locking writer로 옮겨졌다 — spring-data-redis#2890). 그러면 캐시가 빈 순간(최초 적재·만료·evict
     * 직후)에 들어온 요청이 전부 로더를 실행해 Hikari 대기열에 줄을 서고, 첫 적재까지 그 줄에 서서 빈 구간이
     * 늘어나는 되먹임으로 다른 API의 커넥션까지 막는다. 여기서는 같은 키의 동시 호출이 첫 호출의 결과(또는 예외)를
     * 그대로 받는다 — 서버 간은 막지 않는다(prod 최대 2회 적재).
     *
     * <p>캐시 전체가 아니라 키별로 묶는다 — 전체를 묶으면 다른 키의 miss까지 한 줄로 선다. 적중은 기본 경로
     * 그대로 GET 한 번이며({@link #get(Object)}가 통계도 기록), miss일 때만 묶는다. {@code sync} 없는 캐시
     * ({@code user:active})는 {@code get}/{@code put}만 쓰므로 이 경로를 타지 않는다. Spring Data Redis의 locking
     * writer는 쓰지 않는다 — 적중을 포함한 모든 연산에 잠금 확인 왕복이 붙고, 기본 잠금 TTL이 없어 보유 서버가
     * 죽으면 무한 대기이며, 잠금 키에 {@code app.redis.key-prefix}가 붙지 않아 Redis를 공유하는 환경끼리 잠금을
     * 공유한다.
     */
    static final class SingleFlightRedisCache extends RedisCache {

        private final ConcurrentMap<Object, CompletableFuture<Object>> loads = new ConcurrentHashMap<>();

        SingleFlightRedisCache(String name, RedisCacheWriter cacheWriter, RedisCacheConfiguration cacheConfiguration) {
            super(name, cacheWriter, cacheConfiguration);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, Callable<T> valueLoader) {
            ValueWrapper cached = get(key);
            if (cached != null) {
                return (T) cached.get();
            }
            CompletableFuture<Object> load = new CompletableFuture<>();
            CompletableFuture<Object> inFlight = loads.putIfAbsent(key, load);
            if (inFlight != null) {
                return (T) await(inFlight);
            }
            try {
                // 기본 경로가 Redis를 한 번 더 보고(그사이 다른 서버가 채웠을 수 있다) 없을 때만 적재·저장한다.
                T value = super.get(key, valueLoader);
                load.complete(value);
                return value;
            } catch (RuntimeException | Error failure) {
                load.completeExceptionally(failure);
                throw failure;
            } finally {
                loads.remove(key, load);
            }
        }

        private static Object await(CompletableFuture<Object> inFlight) {
            try {
                return inFlight.join();
            } catch (CompletionException wrapped) {
                // 첫 호출이 던진 예외를 그대로 다시 던진다 — 로더 예외(ValueRetrievalException)와 저장소 예외를
                // 구분해 처리하는 Spring의 계약(FailSafeCacheErrorHandler 강등 포함)이 대기자에게도 같게 적용된다.
                if (wrapped.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (wrapped.getCause() instanceof Error error) {
                    throw error;
                }
                throw wrapped;
            }
        }
    }
}
