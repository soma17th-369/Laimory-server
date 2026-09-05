package com.laimory.server.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.laimory.server.config.CacheConfig;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.user.service.RedisActiveStatusCache;
import com.laimory.server.user.service.UserWithdrawalService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ACTIVE 검사 캐시(#429)의 적용 경계를 빌드에서 고정한다.
 *
 * <p>이 캐시가 서비스 메서드가 아니라 별도 컴포넌트인 이유가 곧 이 규칙이다 — 발급·회전
 * ({@code AuthTokenService})이 캐시를 타면 탈퇴자의 refresh 회전을 막는 유일한 장치가 무력화돼
 * 회전 사슬 1회 종결 보장이 깨진다(#429 보안 정책 개정). 그래서 쓸 수 있는 곳은 필터 배선
 * ({@code SecurityConfig})과 탈퇴 evict({@code UserWithdrawalService})뿐이고, 나머지 호출자는
 * {@code UserAccountService} 직행을 유지한다.
 *
 * <p>{@link CacheConfig}는 캐시 이름·TTL 상수만 참조하는 배선이라 우회 위험이 없어 예외다.
 *
 * <p>subject 매핑 캐시에는 대응 규칙이 없다 — 우회해야 하는 호출자가 없어
 * {@code SubjectMappingService}에 직접 달았기 때문이다(rotation rekey의 최종 보장은 erasure의
 * 대상 해석이 하고, 유예가 캐시 TTL을 압도해 그 시점엔 사실상 miss다).
 */
@AnalyzeClasses(packages = "com.laimory.server", importOptions = ImportOption.DoNotIncludeTests.class)
class AuthContextCacheAccessArchTest {

    @ArchTest
    static final ArchRule active_cache_only_for_filter_wiring_and_withdrawal_evict =
            noClasses()
                    .that(not(belongToAnyOf(
                            RedisActiveStatusCache.class,
                            SecurityConfig.class,
                            UserWithdrawalService.class,
                            CacheConfig.class)))
                    .should().dependOnClassesThat(belongToAnyOf(RedisActiveStatusCache.class))
                    .because("ACTIVE 캐시는 JwtAuthenticationFilter 배선과 탈퇴 evict만 쓸 수 있다 — "
                            + "발급·회전 경로는 DB 직행(UserAccountService)을 유지해야 한다(#429). "
                            + "CacheConfig는 캐시 이름·TTL 상수를 읽는 배선이라 예외다");
}
