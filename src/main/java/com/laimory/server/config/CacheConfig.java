package com.laimory.server.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.laimory.server.user.service.RedisActiveStatusCache;
import com.laimory.server.user.service.SubjectMappingService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
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
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * 애플리케이션 캐시 배선의 단일 소유자(#429). 캐시마다 저장소가 달라 {@code CacheManager}를 둘로
 * 나누고, {@code @Cacheable}/{@code @CacheEvict}는 항상 {@code cacheManager}를 명시해 어느 저장소에
 * 사는 캐시인지 선언 지점에서 읽히게 한다.
 *
 * <p><b>결정 규칙 1 — 저장소는 무엇으로 하나.</b> "무효화가 다른 인스턴스에 전파돼야 하는가?"
 * <ul>
 *   <li>예 → {@link #activeStatusCacheManager}(Redis). prod는 WAS 2대가 한 Redis를 공유하므로
 *       탈퇴 evict가 전 인스턴스에 즉시 반영된다. 대가는 요청당 네트워크 왕복이다.</li>
 *   <li>아니오 → {@link #localCacheManager}(Caffeine). 값이 불변이거나 stale이 무해해서 per-host
 *       잔존이 문제가 되지 않는 캐시용 — 요청당 네트워크가 0이다.</li>
 * </ul>
 * 어느 쪽도 계층형(L1+L2)이 아니다. per-host miss 증폭이 아프거나(서버 증설) Redis 왕복이 실측에서
 * 유의미해질 때 승격을 검토한다.
 *
 * <p><b>결정 규칙 2 — 캐시를 어디에 다나.</b> "이 캐시를 <b>우회해야만 하는</b> 호출자가 있는가?"
 * <ul>
 *   <li>예 → 별도 wrapper 컴포넌트에 어노테이션을 달고 호출자 분리를 arch test로 고정한다.
 *       ACTIVE 검사({@link RedisActiveStatusCache})가 그 경우다 — 발급·회전은 DB 직행이어야 한다.</li>
 *   <li>아니오 → 서비스 메서드에 직접 단다. subject 매핑({@link SubjectMappingService})이 그 경우다.</li>
 * </ul>
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
     * 공유 무효화가 필요한 캐시용 Redis 매니저. TTL은 쓰기 시점 고정(조회가 연장하지 않는다)이고,
     * 키 prefix 계산으로 실제 키 모양이 {@code {app.redis.key-prefix}user:active:{userId}} —
     * 즉 다른 application key와 같은 {@code {feature}:{entity}:{id}} 규칙에 남는다.
     *
     * <p>값은 JSON 직렬화한다. 캐시가 읽어 올 수 있는 유효 JSON이 기대 타입과 다르면
     * (역직렬화는 성공하고 프록시 반환 지점에서 {@code ClassCastException}) 요청이 500이 된다 —
     * 캐시 값 shape를 바꿀 때는 키를 바꾸거나 배포 전 비우는 것이 안전하다.
     *
     * <p>{@code initialCacheNames}로 기동 시점에 캐시를 만들어 둔다. Spring Boot의 cache metrics는
     * 기동 시 존재하는 캐시만 바인딩하므로, 이게 없으면 첫 요청 뒤에야 캐시가 생겨 표준
     * {@code cache.*} meter가 영영 노출되지 않는다.
     */
    @Bean
    public RedisCacheManager activeStatusCacheManager(RedisConnectionFactory redisConnectionFactory) {
        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(RedisActiveStatusCache.TTL)
                .computePrefixWith(cacheName -> keyPrefix + cacheName + ":")
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));
        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(configuration)
                .initialCacheNames(Set.of(RedisActiveStatusCache.CACHE_NAME))
                .enableStatistics()
                .build();
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
}
