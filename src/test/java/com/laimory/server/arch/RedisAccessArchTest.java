package com.laimory.server.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.laimory.server.config.CacheConfig;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Redis 접근은 환경 prefix(dev_ 등)가 강제되도록 RedisGateway만 거쳐야 한다.
 * 다른 클래스가 StringRedisTemplate/RedisTemplate/RedisOperations 등 Spring Data Redis 타입을
 * 직접 의존하면 gateway를 우회해 prefix가 누락될 수 있으므로 빌드에서 차단한다.
 *
 * <p>승인 예외는 Spring Cache의 Redis 캐시 배선 {@link CacheConfig}(#429)와 그 캐시 구현
 * {@code SingleFlightRedisCacheManager}(중첩 클래스 포함, #491 — 서버 안 키별 single-flight)다. Redis 매니저를
 * gateway 위에 재구현(RedisCacheWriter)하는 것은 본말전도라서 이 둘만 Spring Data Redis 타입을 직접 쓰되,
 * 같은 {@code app.redis.key-prefix}를 캐시 키 prefix로 붙여 격리 불변식 자체는 지킨다.
 */
@AnalyzeClasses(packages = "com.laimory.server", importOptions = ImportOption.DoNotIncludeTests.class)
class RedisAccessArchTest {

    @ArchTest
    static final ArchRule redis_access_only_through_gateway =
            noClasses()
                    .that().doNotHaveFullyQualifiedName("com.laimory.server.common.redis.RedisGateway")
                    .and(not(belongToAnyOf(CacheConfig.class)))
                    // package-private 클래스라 이름으로 지정한다 — 중첩 클래스($...)까지 포함.
                    .and().haveNameNotMatching("com\\.laimory\\.server\\.config\\.SingleFlightRedisCacheManager(\\$.*)?")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.data.redis..")
                    .because("Redis 접근은 환경 prefix가 강제되도록 RedisGateway만 거쳐야 한다 "
                            + "(승인 예외: CacheConfig의 Spring Cache Redis 매니저 배선과 그 캐시 구현 "
                            + "SingleFlightRedisCacheManager — 같은 prefix를 붙인다)");
}
