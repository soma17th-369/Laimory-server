package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.config.CacheConfig;
import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.entity.UserSubjectLink;
import com.laimory.server.user.repository.UserSubjectLinkRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * subject 매핑 캐시(#429)의 계약 고정. wrapper를 없애고 {@code @Cacheable}을 {@code @Transactional}
 * 메서드에 직접 달았으므로, 검증의 핵심은 <b>적중 시 transaction이 열리지 않는다</b>는 것이다 —
 * repository 0회만 봐서는 "캐시는 맞았지만 빈 transaction은 열리고 닫히는" 조용한 성능 회귀를 못 잡는다.
 * 그래서 {@link PlatformTransactionManager}의 {@code getTransaction} 호출 수를 직접 센다.
 *
 * <p>인터셉터 순서가 검증 대상이라 캐시 배선은 스탠드인이 아니라 실 {@link CacheConfig}를 쓴다
 * ({@code @EnableCaching(order)}가 여기 있다). Redis 매니저는 빌드만 되고 이 테스트가 건드리지
 * 않으므로 mock {@link RedisConnectionFactory}로 충분하다.
 *
 * <p>테스트끼리는 고유 userId로 격리한다(캐시가 컨텍스트 수명 동안 살아 있다).
 */
@SpringJUnitConfig(SubjectMappingServiceCachingTest.CachingSliceConfig.class)
class SubjectMappingServiceCachingTest {

    private static final AtomicLong USER_ID_SEQUENCE = new AtomicLong(1_000L);

    @Autowired
    private SubjectMappingService subjectMappingService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private UserSubjectLinkRepository userSubjectLinkRepository;
    @Autowired
    private SubjectLookupKeyDeriver subjectLookupKeyDeriver;

    /** 빈은 컨텍스트 수명 동안 공유되므로 호출 수 검증 전에 되돌린다(캐시는 고유 userId로 격리). */
    @BeforeEach
    void resetMocks() {
        reset(transactionManager, userSubjectLinkRepository, subjectLookupKeyDeriver);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void firstLookupOpensTransactionAndReadsRepository() {
        long userId = nextUserId();
        UUID subjectId = stubCurrentHit(userId);

        assertThat(subjectMappingService.getRequired(userId)).isEqualTo(subjectId);

        // 양성 대조 — miss에서는 transaction이 실제로 열린다(아래 hit 검증이 vacuous하지 않다는 증명).
        verify(transactionManager, times(1)).getTransaction(any());
        verify(userSubjectLinkRepository, times(1)).findById(any());
    }

    @Test
    void cacheHitSkipsBothTransactionAndRepository() {
        long userId = nextUserId();
        UUID subjectId = stubCurrentHit(userId);

        assertThat(subjectMappingService.getRequired(userId)).isEqualTo(subjectId);
        assertThat(subjectMappingService.getRequired(userId)).isEqualTo(subjectId);

        // 캐시 인터셉터가 transaction 인터셉터보다 바깥이라 적중은 tx 개폐 자체를 건너뛴다.
        verify(transactionManager, times(1)).getTransaction(any());
        verify(userSubjectLinkRepository, times(1)).findById(any());
    }

    @Test
    void missingMappingIsNotCachedAndPropagatesUnwrapped() {
        long userId = nextUserId();
        byte[] currentKey = stubKeys(userId);
        when(userSubjectLinkRepository.findById(currentKey)).thenReturn(Optional.empty());

        // sync=true는 로더 예외를 ValueRetrievalException으로 감쌌다가 원형으로 되돌린다 —
        // 매핑 누락 fail-closed 계약(IllegalStateException)이 캐시 도입 전과 같아야 한다.
        assertThatThrownBy(() -> subjectMappingService.getRequired(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("subject mapping missing for authenticated user");

        // 실패는 캐시에 얼어붙지 않는다 — 다음 호출이 다시 조회한다.
        assertThatThrownBy(() -> subjectMappingService.getRequired(userId))
                .isInstanceOf(IllegalStateException.class);
        verify(userSubjectLinkRepository, times(2)).findById(currentKey);
    }

    @Test
    void evictCachedMappingForcesReload() {
        long userId = nextUserId();
        UUID subjectId = stubCurrentHit(userId);
        assertThat(subjectMappingService.getRequired(userId)).isEqualTo(subjectId);

        subjectMappingService.evictCachedMapping(userId);

        assertThat(subjectMappingService.getRequired(userId)).isEqualTo(subjectId);
        verify(userSubjectLinkRepository, times(2)).findById(any());
    }

    private static long nextUserId() {
        return USER_ID_SEQUENCE.incrementAndGet();
    }

    private byte[] stubKeys(long userId) {
        byte[] currentKey = new byte[] {(byte) userId, 1};
        when(subjectLookupKeyDeriver.deriveCurrent(userId)).thenReturn(currentKey);
        when(subjectLookupKeyDeriver.derivePrevious(userId)).thenReturn(Optional.empty());
        return currentKey;
    }

    private UUID stubCurrentHit(long userId) {
        byte[] currentKey = stubKeys(userId);
        UUID subjectId = UUID.randomUUID();
        when(userSubjectLinkRepository.findById(currentKey))
                .thenReturn(Optional.of(UserSubjectLink.of(currentKey, subjectId, (short) 1)));
        return subjectId;
    }

    @Configuration
    @EnableTransactionManagement
    @Import(CacheConfig.class)
    static class CachingSliceConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        /** Redis 매니저는 빌드만 되고 연산되지 않으므로 연결을 열지 않는다. */
        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return mock(RedisConnectionFactory.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
            when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            return transactionManager;
        }

        @Bean
        UserSubjectLinkRepository userSubjectLinkRepository() {
            return mock(UserSubjectLinkRepository.class);
        }

        @Bean
        SubjectLookupKeyDeriver subjectLookupKeyDeriver() {
            return mock(SubjectLookupKeyDeriver.class);
        }

        @Bean
        SubjectMappingMetrics subjectMappingMetrics(MeterRegistry meterRegistry) {
            return new SubjectMappingMetrics(meterRegistry);
        }

        @Bean
        SubjectMappingService subjectMappingService(
                UserSubjectLinkRepository userSubjectLinkRepository,
                SubjectLookupKeyDeriver subjectLookupKeyDeriver,
                SubjectMappingMetrics subjectMappingMetrics) {
            return new SubjectMappingService(
                    userSubjectLinkRepository, subjectLookupKeyDeriver, subjectMappingMetrics);
        }
    }
}
