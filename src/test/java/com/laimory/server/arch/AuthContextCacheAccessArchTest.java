package com.laimory.server.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.laimory.server.config.SecurityConfig;
import com.laimory.server.user.CurrentSubjectArgumentResolver;
import com.laimory.server.user.service.RedisActiveStatusCache;
import com.laimory.server.user.service.SubjectMappingCache;
import com.laimory.server.user.service.UserWithdrawalService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 인증 캐시 2종(#429)의 적용 경계를 빌드에서 고정한다.
 *
 * <p>ACTIVE 캐시는 필터 배선({@code SecurityConfig})과 탈퇴 evict({@code UserWithdrawalService})만
 * 쓸 수 있다 — 발급·회전({@code AuthTokenService})이 캐시를 타면 탈퇴자의 refresh 회전을 막는 유일한
 * 장치가 무력화돼 회전 사슬 1회 종결 보장이 깨진다(#429 보안 정책 개정).
 *
 * <p>subject 캐시는 요청 고정비 경로({@code CurrentSubjectArgumentResolver})와 탈퇴
 * evict({@code UserWithdrawalService})만 쓸 수 있다 — 탈퇴 transaction의 subject 해석이 캐시를 타면
 * rotation 기간의 마지막 lazy rekey가 생략돼 erasure(#302)의 current-key 전제가 깨진다.
 */
@AnalyzeClasses(packages = "com.laimory.server", importOptions = ImportOption.DoNotIncludeTests.class)
class AuthContextCacheAccessArchTest {

    @ArchTest
    static final ArchRule active_cache_only_for_filter_wiring_and_withdrawal_evict =
            noClasses()
                    .that(not(belongToAnyOf(
                            RedisActiveStatusCache.class,
                            SecurityConfig.class,
                            UserWithdrawalService.class)))
                    .should().dependOnClassesThat(belongToAnyOf(RedisActiveStatusCache.class))
                    .because("ACTIVE 캐시는 JwtAuthenticationFilter 배선과 탈퇴 evict만 쓸 수 있다 — "
                            + "발급·회전 경로는 DB 직행(UserAccountService)을 유지해야 한다(#429)");

    @ArchTest
    static final ArchRule subject_cache_only_for_resolver_and_withdrawal_evict =
            noClasses()
                    .that(not(belongToAnyOf(
                            SubjectMappingCache.class,
                            CurrentSubjectArgumentResolver.class,
                            UserWithdrawalService.class)))
                    .should().dependOnClassesThat(belongToAnyOf(SubjectMappingCache.class))
                    .because("subject 캐시는 @CurrentSubject 해석과 탈퇴 evict만 쓸 수 있다 — "
                            + "탈퇴 transaction의 해석은 lazy rekey를 위해 원본 서비스 직행이어야 한다(#429)");
}
