package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.push.repository.PushRegistrationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * FID 등록·해제 서비스 단위 검증 — opaque 계약(무가공 전달)·validation 경계·빈 목록 no-op. 인프라 0.
 * FID 원문이 예외 메시지에 노출되지 않는 것도 여기서 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class PushRegistrationServiceTest {

    private static final long USER_ID = 7L;
    /** 고정 Clock — upsert freshness 값이 이 시각으로 전달돼야 한다. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-21T01:30:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime FIXED_NOW = LocalDateTime.now(FIXED_CLOCK);

    @Mock
    private PushRegistrationRepository pushRegistrationRepository;

    private PushRegistrationService service() {
        return new PushRegistrationService(pushRegistrationRepository, FIXED_CLOCK);
    }

    @Test
    void register_passesFidUnmodifiedWithFixedNow() {
        // opaque 계약: 앞뒤 공백·대소문자 포함 원문 그대로 repository에 전달한다(trim·정규화 금지).
        service().register("v1", USER_ID, " AbC-fid ");

        verify(pushRegistrationRepository).upsert(USER_ID, " AbC-fid ", FIXED_NOW);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void register_rejectsNullOrBlankFid(String fid) {
        assertThatThrownBy(() -> service().register("v1", USER_ID, fid))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pushRegistrationRepository);
    }

    @Test
    void register_rejectsOverlongFid_withoutEchoingRawValue() {
        String overlong = "f".repeat(256);

        assertThatThrownBy(() -> service().register("v1", USER_ID, overlong))
                .isInstanceOf(IllegalArgumentException.class)
                // FID 원문은 예외 메시지(→로그)에 노출하지 않는다 — 길이 계약만 언급.
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(overlong));
        verifyNoInteractions(pushRegistrationRepository);
    }

    @Test
    void register_acceptsMaxLengthFid() {
        String max = "f".repeat(255);

        service().register("v1", USER_ID, max);

        verify(pushRegistrationRepository).upsert(USER_ID, max, FIXED_NOW);
    }

    @Test
    void unregister_deletesWithOwnerCondition() {
        service().unregister("v1", USER_ID, "fid-1");

        // (owner, FID) 동시 일치 삭제 — 계정 전환 뒤 이전 사용자의 늦은 해제가 재결합 등록을 못 지운다.
        verify(pushRegistrationRepository).deleteByUserIdAndFirebaseInstallationId(USER_ID, "fid-1");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void unregister_rejectsNullOrBlankFid(String fid) {
        assertThatThrownBy(() -> service().unregister("v1", USER_ID, fid))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pushRegistrationRepository);
    }

    @Test
    void findFirebaseInstallationIds_delegatesToRepository() {
        when(pushRegistrationRepository.findAllFirebaseInstallationIdsByUserId(USER_ID))
                .thenReturn(List.of("fid-1", "fid-2"));

        assertThat(service().findFirebaseInstallationIds(USER_ID)).containsExactly("fid-1", "fid-2");
    }

    @Test
    void removeInvalidRegistrations_emptyList_isNoOpWithoutQuery() {
        service().removeInvalidRegistrations(List.of(), FIXED_NOW);

        verifyNoInteractions(pushRegistrationRepository);
    }

    @Test
    void removeInvalidRegistrations_deletesGivenFidsWithSnapshotGuard() {
        // snapshot 시각이 함께 전달돼야 한다 — 지연된 무효 응답이 그 이후의 재등록을 지우지 않는 조건부 삭제.
        service().removeInvalidRegistrations(List.of("gone-1", "gone-2"), FIXED_NOW);

        verify(pushRegistrationRepository).deleteInvalidRegistrations(List.of("gone-1", "gone-2"), FIXED_NOW);
    }
}
