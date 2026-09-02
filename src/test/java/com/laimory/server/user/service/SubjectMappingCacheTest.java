package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * subject 매핑 캐시(#429)의 계약 고정: 적중 시 원본 서비스(=transaction·repository) 미호출,
 * 실패(매핑 누락 fail-closed 포함)는 캐시하지 않고 전파, 탈퇴 evict 후 재적재.
 */
class SubjectMappingCacheTest {

    private static final long USER_ID = 7L;

    private SubjectMappingService subjectMappingService;
    private SubjectMappingCache cache;

    @BeforeEach
    void setUp() {
        subjectMappingService = mock(SubjectMappingService.class);
        cache = new SubjectMappingCache(subjectMappingService, new SimpleMeterRegistry());
    }

    @Test
    void getRequired_secondCallIsServedFromCache() {
        UUID subjectId = UUID.randomUUID();
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(subjectId);

        assertThat(cache.getRequired(USER_ID)).isEqualTo(subjectId);
        assertThat(cache.getRequired(USER_ID)).isEqualTo(subjectId);

        // 적중은 위임하지 않는다 — @Transactional 경계 앞 캐시라 transaction 진입 자체가 생략된다.
        verify(subjectMappingService, times(1)).getRequired(USER_ID);
    }

    @Test
    void getRequired_failureIsNotCachedAndPropagates() {
        when(subjectMappingService.getRequired(USER_ID))
                .thenThrow(new IllegalStateException("subject mapping missing for authenticated user"))
                .thenReturn(UUID.randomUUID());

        // 매핑 누락 fail-closed는 그대로 전파되고 캐시에 얼어붙지 않는다 — 다음 호출은 다시 조회한다.
        assertThatThrownBy(() -> cache.getRequired(USER_ID)).isInstanceOf(IllegalStateException.class);
        assertThat(cache.getRequired(USER_ID)).isNotNull();
        verify(subjectMappingService, times(2)).getRequired(USER_ID);
    }

    @Test
    void evict_forcesReloadOnNextCall() {
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(UUID.randomUUID());
        cache.getRequired(USER_ID);

        cache.evict(USER_ID);
        cache.getRequired(USER_ID);

        verify(subjectMappingService, times(2)).getRequired(USER_ID);
    }
}
