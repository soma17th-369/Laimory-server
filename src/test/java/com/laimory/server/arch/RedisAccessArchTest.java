package com.laimory.server.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Redis 접근은 환경 prefix(dev_ 등)가 강제되도록 NamespacedRedis facade만 거쳐야 한다.
 * 다른 클래스가 StringRedisTemplate/RedisTemplate/RedisOperations 등 Spring Data Redis 타입을
 * 직접 의존하면 facade를 우회해 prefix가 누락될 수 있으므로 빌드에서 차단한다.
 */
@AnalyzeClasses(packages = "com.laimory.server", importOptions = ImportOption.DoNotIncludeTests.class)
class RedisAccessArchTest {

    @ArchTest
    static final ArchRule redis_access_only_through_facade =
            noClasses()
                    .that().doNotHaveFullyQualifiedName("com.laimory.server.common.redis.NamespacedRedis")
                    .and().doNotHaveFullyQualifiedName("com.laimory.server.common.redis.RedisSslConfig")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.data.redis..")
                    .because("Redis 접근은 환경 prefix가 강제되도록 NamespacedRedis facade만 거쳐야 한다"
                            + " (RedisSslConfig는 키 접근이 아닌 Lettuce 연결 TLS 설정이라 prefix 불변식과 무관 → 예외)");
}
