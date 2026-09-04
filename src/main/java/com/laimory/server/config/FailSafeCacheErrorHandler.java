package com.laimory.server.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * 캐시 저장소 장애를 삼켜 요청을 원본 경로로 흘려보내는 error handler(#429). 기본
 * {@code SimpleCacheErrorHandler}는 저장소 예외를 그대로 던져 Redis 장애가 인증 500이 되게 하므로
 * 이 handler로 대체한다 — 배선은 {@link CacheConfig}가 {@code CachingConfigurer}로 등록한다.
 *
 * <p><b>값 로더 예외는 이 handler의 사정권이 아니다.</b> 여기 오는 것은 캐시 <b>저장소 연산</b>의
 * 예외(GET/PUT/EVICT/CLEAR)뿐이고, 캐시된 메서드 본문이 던진 예외는 프레임워크가 그대로 전파한다
 * ({@code sync=true}는 {@code ValueRetrievalException}으로 감쌌다가 원형으로 되돌린다). 그래서
 * 매핑 누락 fail-closed·miss 경로 DB 장애 같은 계약은 캐시 도입 전과 동일하게 유지된다.
 *
 * <p>수준을 나누는 기준은 "요청량에 비례하는가"다. GET/PUT 실패는 hot path라 요청마다 로그가
 * 쏟아지므로 DEBUG로 낮추고 관측은 {@code laimory.cache.fallback} counter와 redis 가용성 경보에
 * 맡긴다. EVICT/CLEAR 실패는 탈퇴 등 드문 이벤트라 건수가 유계이고 stale이 TTL까지 남는다는 뜻이라
 * WARN으로 남긴다. 로그·메트릭에 캐시 키(식별자)를 담지 않는다.
 */
@Slf4j
public class FailSafeCacheErrorHandler implements CacheErrorHandler {

    /** 캐시 저장소 장애로 원본 경로로 강등된 연산 수 — tag는 캐시 이름뿐이다(식별자 금지). */
    static final String FALLBACK_METER = "laimory.cache.fallback";

    private final MeterRegistry meterRegistry;

    public FailSafeCacheErrorHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 조회 실패는 miss로 강등한다 — 캐시 장애가 조용한 오판정도, 저장소발 500도 만들지 않는다. */
    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        meterRegistry.counter(FALLBACK_METER, "cache", cache.getName()).increment();
        log.debug("cache read failed - falling back to loader: cache={} type={}",
                cache.getName(), exception.getClass().getName());
    }

    /** 적재 실패는 성능 손실로 끝난다(다음 요청이 다시 로더를 탄다). */
    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.debug("cache write failed: cache={} type={}",
                cache.getName(), exception.getClass().getName());
    }

    /** 무효화 실패는 stale이 TTL까지 남는다는 뜻이라 남긴다 — 대신 건수가 유계다(탈퇴당 1건). */
    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("cache evict failed - stale entry converges by TTL: cache={} type={}",
                cache.getName(), exception.getClass().getName());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("cache clear failed - stale entries converge by TTL: cache={} type={}",
                cache.getName(), exception.getClass().getName());
    }
}
