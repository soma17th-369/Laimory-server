package com.laimory.server.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.laimory.server.appconfig.AppConfigService;
import com.laimory.server.notice.service.NoticeService;
import com.laimory.server.terms.service.TermCatalogService;
import com.laimory.server.user.service.SubjectMappingService;
import com.laimory.server.user.service.UserAccountService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * 애플리케이션 캐시 배선의 단일 소유자(#429). 캐시마다 저장소가 달라 {@code CacheManager}를 둘로
 * 나누고, {@code @Cacheable}/{@code @CacheEvict}는 항상 {@code cacheManager}를 명시해 어느 저장소에
 * 사는 캐시인지 선언 지점에서 읽히게 한다.
 *
 * <p><b>결정 규칙 — 저장소는 무엇으로 하나.</b> "무효화가 다른 인스턴스에 전파돼야 하는가?"
 * <ul>
 *   <li>예 → {@link #redisCacheManager}(Redis). prod는 WAS 2대가 한 Redis를 공유하므로
 *       evict(탈퇴·관리자 변경)가 전 인스턴스에 즉시 반영된다. 대가는 요청당 네트워크 왕복이다.</li>
 *   <li>아니오 → {@link #localCacheManager}(Caffeine). 값이 불변이거나 stale이 무해해서 per-host
 *       잔존이 문제가 되지 않는 캐시용 — 요청당 네트워크가 0이다.</li>
 * </ul>
 * 어느 쪽도 계층형(L1+L2)이 아니다. per-host miss 증폭이 아프거나(서버 증설) Redis 왕복이 실측에서
 * 유의미해질 때 승격을 검토한다. 캐시는 wrapper 없이 서비스 메서드에 직접 단다
 * (ACTIVE 검사 {@link UserAccountService}, subject 매핑 {@link SubjectMappingService} — #441, 관리자 변경 값
 * {@link AppConfigService}·{@link TermCatalogService}·{@link NoticeService} — #491).
 *
 * <p>{@code @Primary}는 로컬 매니저에 둔다. 매니저가 둘이라 {@code cacheManager} 미지정은 실수인데,
 * 그 실수가 "공유돼야 할 캐시가 조용히 per-host가 되는" 쪽이 아니라 로컬로 수렴하는 쪽이 되게
 * 하려는 것이다. {@code CompositeCacheManager}는 라우팅을 숨기고 이름 오타를 삼켜 쓰지 않는다.
 *
 * <p>{@code @EnableCaching}의 {@code order}를 transaction advisor(기본 {@code LOWEST_PRECEDENCE})보다
 * 한 단계 앞으로 고정한다 — 캐시 인터셉터가 안쪽이면 적중에도 transaction이 열리고 닫혀
 * {@code @Transactional} 메서드에 캐시를 다는 의미가 사라진다(조용한 성능 회귀).
 *
 * <p><b>RedisGateway 승인 예외</b>: 이 클래스만 {@code RedisAccessArchTest}가 금지하는 Spring Data
 * Redis 타입을 직접 의존한다 — {@code RedisCacheWriter}를 gateway 위에 재구현하는 것은 본말전도다.
 * 대신 gateway와 같은 {@code app.redis.key-prefix}를 캐시 키 prefix에 붙여 dev/prod가 한 Redis를
 * 공유해도 네임스페이스가 섞이지 않는다는 불변식을 그대로 지킨다.
 */
@Configuration
@EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 1)
public class CacheConfig implements CachingConfigurer {

    private final String keyPrefix;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public CacheConfig(@Value("${app.redis.key-prefix:}") String keyPrefix,
                       ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.keyPrefix = keyPrefix;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * 관리자 웹으로만 바뀌는 전역 값 캐시(앱 버전·약관 current·팝업 공지 id, #491)의 TTL. 즉시성은 관리자 쓰기의
     * evict가 담당하고 이 TTL은 evict 유실 대비 안전망이다 — 키가 사실상 하나라 길게 잡아도 메모리 부담이 없고,
     * 낮은 트래픽에서도 적중하려면 길어야 한다. 앱을 우회한 DB 직접 쓰기는 고려하지 않는다.
     */
    private static final Duration ADMIN_MANAGED_TTL = Duration.ofHours(1);

    /**
     * 공유 무효화가 필요한 캐시용 Redis 매니저. TTL은 쓰기 시점 고정(조회가 연장하지 않는다)이고,
     * 키 prefix 계산으로 실제 키 모양이 {@code {app.redis.key-prefix}user:active:{userId}} —
     * 즉 다른 application key와 같은 {@code {feature}:{entity}:{id}} 규칙에 남는다. 기본 TTL은 ACTIVE 검사
     * 기준이고, 관리자 변경 값 캐시는 {@link #ADMIN_MANAGED_TTL}을 캐시별로 덮어쓴다.
     *
     * <p>값은 JSON 직렬화한다. 캐시가 읽어 올 수 있는 유효 JSON이 기대 타입과 다르면
     * (역직렬화는 성공하고 프록시 반환 지점에서 {@code ClassCastException}) 요청이 500이 된다 —
     * 캐시 값 shape를 바꿀 때는 키를 바꾸거나 배포 전 비우는 것이 안전하다.
     *
     * <p>initial cache configuration으로 기동 시점에 캐시를 만들어 둔다. Spring Boot의 cache metrics는
     * 기동 시 존재하는 캐시만 바인딩하므로, 이게 없으면 첫 요청 뒤에야 캐시가 생겨 표준
     * {@code cache.*} meter가 영영 노출되지 않는다.
     *
     * <p>builder가 아니라 생성자로 만든다 — builder는 항상 {@link RedisCacheManager} 자체를 만들어
     * {@link SingleFlightRedisCacheManager}를 쓸 수 없다. 그래서 builder가 하던 일을 여기서 직접 한다:
     * 통계 수집기(빠지면 기동은 정상이지만 Redis 캐시의 {@code cache.gets}가 0으로 보고된다), 기동 시 캐시,
     * 실행 중 캐시 생성 허용(builder 기본값과 같음).
     */
    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(UserAccountService.TTL)
                .computePrefixWith(cacheName -> keyPrefix + cacheName + ":")
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));
        RedisCacheConfiguration adminManaged = defaults.entryTtl(ADMIN_MANAGED_TTL);
        RedisCacheWriter cacheWriter = RedisCacheWriter.nonLockingRedisCacheWriter(redisConnectionFactory)
                .withStatisticsCollector(CacheStatisticsCollector.create());
        return new SingleFlightRedisCacheManager(cacheWriter, defaults, Map.of(
                UserAccountService.CACHE_NAME, defaults,
                AppConfigService.CACHE_NAME, adminManaged,
                TermCatalogService.CACHE_NAME, adminManaged,
                NoticeService.POPUP_CACHE_NAME, adminManaged));
    }

    /**
     * 무효화 전파가 필요 없는 캐시용 per-host 매니저. 상한은 현 회원 규모와 TTL당 활성 사용자 수를
     * 넉넉히 덮고, 만료는 쓰기 시점 고정이다({@code expireAfterAccess}는 접근마다 만료가 밀려
     * "TTL마다 한 번은 원본을 다시 본다"는 보장이 깨진다).
     */
    @Bean
    @Primary
    public CaffeineCacheManager localCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(SubjectMappingService.CACHE_MAX_SIZE)
                .expireAfterWrite(SubjectMappingService.CACHE_TTL)
                .recordStats());
        cacheManager.setCacheNames(List.of(SubjectMappingService.CACHE_NAME));
        return cacheManager;
    }

    /**
     * {@code CachingConfigurer} 경유로만 등록된다 — {@code CacheErrorHandler}를 그냥 빈으로 두면
     * Spring이 조회하지 않아 조용히 기본 handler(예외 전파)가 쓰인다.
     */
    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new FailSafeCacheErrorHandler(meterRegistryProvider.getObject());
    }

    /** 모든 Redis 캐시를 {@link SingleFlightRedisCache}로 만드는 매니저 — 그 외 동작은 기본 매니저와 같다. */
    static final class SingleFlightRedisCacheManager extends RedisCacheManager {

        SingleFlightRedisCacheManager(RedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration,
                                      Map<String, RedisCacheConfiguration> initialCacheConfigurations) {
            super(cacheWriter, defaultCacheConfiguration, true, initialCacheConfigurations);
        }

        @Override
        protected RedisCache createRedisCache(String name, RedisCacheConfiguration cacheConfiguration) {
            return new SingleFlightRedisCache(name, getCacheWriter(),
                    cacheConfiguration != null ? cacheConfiguration : getDefaultCacheConfiguration());
        }
    }

    /**
     * {@code @Cacheable(sync = true)}의 값 적재를 <b>서버(JVM) 안에서 키별로 한 번</b>만 실행하는 Redis 캐시(#491).
     *
     * <p>Spring은 {@code sync = true}의 중복 적재 방지를 {@link #get(Object, Callable)} 구현에 맡기는데, 기본
     * {@link RedisCache}와 non-locking writer는 동기화를 하지 않는다. 그러면 캐시가 빈 순간(최초 적재·만료·evict
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
     *
     * <p>Spring Data Redis를 올릴 때 이 클래스가 기대는 {@link RedisCache} 생성자와
     * {@link RedisCacheManager#createRedisCache} 시그니처를 함께 점검한다.
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
