package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.user.UserStatus;
import com.laimory.server.user.repository.UserRepository;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * ACTIVE 검사 캐시(#429·#441)의 어노테이션 계약 고정: 적중 시 DB 미호출, 음성(false) 미적재,
 * {@code evictActive} 후 재조회는 DB 재확인, miss 경로 DB 장애는 그대로 전파(필터 fail-closed 500
 * 계약 유지). #441부터 캐시 대상이 {@link UserAccountService} 자체라(wrapper 제거) DB 쪽
 * {@link UserRepository}를 mock 하고 실 서비스 빈을 프록시로 띄운다 — 필터·발급·회전이 전부 이
 * 프록시 하나를 경유하므로 여기 계약이 세 경로 공통이다.
 *
 * <p>저장소는 스탠드인 {@link ConcurrentMapCacheManager}다 — 검증 대상이 "어느 저장소에 어떻게
 * 쓰이는가"가 아니라 "{@code @Cacheable}/{@code @CacheEvict}가 이 메서드에 어떤 계약을 만드는가"라서다.
 * 다만 <b>빈 이름은 실제 매니저 이름과 같아야</b> 어노테이션의 {@code cacheManager} 지정이 풀린다 —
 * 이름이 어긋나면 컨텍스트가 뜨지 않아 오타가 여기서 잡힌다. 실 Redis 왕복(키 모양·TTL 고정·공유
 * DEL 전파)은 {@code ActiveStatusCacheIntegrationTest}가 담당한다.
 */
@SpringJUnitConfig(UserAccountServiceCachingTest.CacheSliceConfig.class)
class UserAccountServiceCachingTest {

    private static final long USER_ID = 42L;

    @Autowired
    private UserAccountService userAccountService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CacheManager activeStatusCacheManager;

    @BeforeEach
    void resetSlice() {
        reset(userRepository);
        activeStatusCacheManager.getCache(UserAccountService.CACHE_NAME).clear();
    }

    @Test
    void isActive_cacheHit_trustsCacheWithoutDbCall() {
        when(userRepository.existsByUserIdAndStatus(USER_ID, UserStatus.ACTIVE)).thenReturn(true);

        assertThat(userAccountService.isActive(USER_ID)).isTrue();
        assertThat(userAccountService.isActive(USER_ID)).isTrue();

        // 두 번째 호출은 DB를 아예 안 본다 — 요청 고정비 제거의 본체이자 "hit는 캐시 신뢰" 의미론.
        verify(userRepository, times(1)).existsByUserIdAndStatus(USER_ID, UserStatus.ACTIVE);
    }

    @Test
    void isActive_methodReference_sharesTheSameCacheProxy() {
        when(userRepository.existsByUserIdAndStatus(USER_ID, UserStatus.ACTIVE)).thenReturn(true);

        // 회전 경로의 형태 그대로 — AuthTokenService가 프록시 빈의 method reference를 넘긴다(#441).
        LongPredicate ownerActive = userAccountService::isActive;
        assertThat(userAccountService.isActive(USER_ID)).isTrue();
        assertThat(ownerActive.test(USER_ID)).isTrue();

        // 발급·회전도 같은 프록시를 타므로 필터가 적재한 캐시를 공유한다(DB 재조회 없음).
        verify(userRepository, times(1)).existsByUserIdAndStatus(USER_ID, UserStatus.ACTIVE);
    }

    @Test
    void isActive_negativeResult_isNeverCached() {
        when(userRepository.existsByUserIdAndStatus(USER_ID, UserStatus.ACTIVE)).thenReturn(false);

        assertThat(userAccountService.isActive(USER_ID)).isFalse();
        assertThat(userAccountService.isActive(USER_ID)).isFalse();

        // 음성 캐시 금지 — 탈퇴 판정이 캐시에 얼어붙으면 안 된다(#429 원칙 2).
        verify(userRepository, times(2)).existsByUserIdAndStatus(USER_ID, UserStatus.ACTIVE);
        assertThat(activeStatusCacheManager.getCache(UserAccountService.CACHE_NAME).get(USER_ID))
                .isNull();
    }

    @Test
    void evictActive_forcesDbReverificationOnNextCall() {
        when(userRepository.existsByUserIdAndStatus(USER_ID, UserStatus.ACTIVE))
                .thenReturn(true).thenReturn(false);

        assertThat(userAccountService.isActive(USER_ID)).isTrue();
        userAccountService.evictActive(USER_ID);

        // 탈퇴 커밋 후 evict → 다음 검사는 miss → DB 재확인 → 차단(필터·발급·회전 공통).
        assertThat(userAccountService.isActive(USER_ID)).isFalse();
        verify(userRepository, times(2)).existsByUserIdAndStatus(USER_ID, UserStatus.ACTIVE);
    }

    @Test
    void isActive_missPathDbFailure_propagates() {
        when(userRepository.existsByUserIdAndStatus(USER_ID, UserStatus.ACTIVE))
                .thenThrow(new IllegalStateException("db down"));

        // miss 경로의 DB 장애는 그대로 전파 — 필터의 fail-closed 500 -500 계약이 여기서 성립한다.
        assertThatThrownBy(() -> userAccountService.isActive(USER_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * {@code proxyTargetClass}는 슬라이스에 Boot의 AOP auto-config(기본값이 class proxying)가 없어서
     * 명시한다 — 캐시 대상 빈이 인터페이스 없는 구체 클래스라(#441) class proxying이어야만 프록시가 뜬다.
     */
    @Configuration
    @EnableCaching(proxyTargetClass = true)
    static class CacheSliceConfig {

        /** 빈 이름이 곧 어노테이션의 {@code cacheManager} 지정값이다. */
        @Bean
        CacheManager activeStatusCacheManager() {
            return new ConcurrentMapCacheManager(UserAccountService.CACHE_NAME);
        }

        @Bean
        UserRepository userRepository() {
            return mock(UserRepository.class);
        }

        @Bean
        UserAccountService userAccountService(UserRepository userRepository) {
            return new UserAccountService(userRepository);
        }
    }
}
