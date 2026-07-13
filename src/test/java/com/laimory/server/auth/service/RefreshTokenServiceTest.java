package com.laimory.server.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.auth.entity.RefreshToken;
import com.laimory.server.auth.repository.RefreshTokenRepository;
import com.laimory.server.auth.token.AuthTokens;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.error.ExceptionType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/** refresh 발급·회전·폐기·재사용 탐지 계약: 조건부 claim으로 원자 회전, 재사용/만료/무효는 ERROR_2003, 재사용 시 전체 폐기. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final Instant NOW = Instant.parse("2026-07-07T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDateTime LOCAL_NOW = LocalDateTime.now(CLOCK);
    private static final long USER_ID = 99L;

    private RefreshTokenService newService() {
        // no-op tx manager: TransactionTemplate.execute가 콜백을 그대로 실행하고 값을 돌려준다(getTransaction/commit는 no-op).
        return new RefreshTokenService(
                refreshTokenRepository, mock(PlatformTransactionManager.class), REFRESH_TTL, CLOCK);
    }

    /** refreshTokenId는 @GeneratedValue라 단위 테스트에서 null인 ACTIVE·미만료 엔티티. */
    private RefreshToken activeToken() {
        return RefreshToken.issue(USER_ID, "hash", LOCAL_NOW.plus(REFRESH_TTL), null);
    }

    @Test
    void issue_savesEntityWithHashedTokenExpiryAndActiveStatus() {
        String raw = newService().issue(USER_ID);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getTokenHash()).isEqualTo(AuthTokens.sha256Hex(raw));
        assertThat(saved.getExpiresAt()).isEqualTo(LOCAL_NOW.plus(REFRESH_TTL));
        assertThat(saved.getParentId()).isNull();
        assertThat(saved.getStatus()).isEqualTo(com.laimory.server.auth.RefreshTokenStatus.ACTIVE);
    }

    @Test
    void rotate_activeToken_claimsAndIssuesNewToken() {
        String oldRaw = "old-refresh-raw";
        when(refreshTokenRepository.findByTokenHash(AuthTokens.sha256Hex(oldRaw)))
                .thenReturn(java.util.Optional.of(activeToken()));
        when(refreshTokenRepository.claimRotation(any())).thenReturn(1);

        RefreshTokenService.Rotation rotation = newService().rotate(oldRaw);

        assertThat(rotation.userId()).isEqualTo(USER_ID);
        assertThat(rotation.refreshToken()).isNotEqualTo(oldRaw);
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        // 새 발급 엔티티의 parentId는 회전된 토큰의 id(단위 테스트에선 @GeneratedValue null).
        assertThat(captor.getValue().getParentId()).isNull();
        assertThat(captor.getValue().getTokenHash())
                .isEqualTo(AuthTokens.sha256Hex(rotation.refreshToken()));
    }

    @Test
    void rotate_unknownToken_throwsError2003_withoutRevokeAll() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> newService().rotate("unknown-raw"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.REFRESH_TOKEN_INVALID);
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_2003);
                });
        verify(refreshTokenRepository, never()).revokeAllByUserId(any());
    }

    @Test
    void rotate_expiredToken_throwsError2003_withoutClaim() {
        RefreshToken expired = RefreshToken.issue(USER_ID, "hash", LOCAL_NOW.minusSeconds(1), null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(java.util.Optional.of(expired));

        assertThatThrownBy(() -> newService().rotate("expired-raw"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.REFRESH_TOKEN_INVALID);
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_2003);
                });
        verify(refreshTokenRepository, never()).claimRotation(any());
    }

    @Test
    void rotate_claimLost_revokesAllAndThrowsError2003() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(java.util.Optional.of(activeToken()));
        when(refreshTokenRepository.claimRotation(any())).thenReturn(0); // 이미 ROTATED/REVOKED = 재사용 신호

        assertThatThrownBy(() -> newService().rotate("reused-raw"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    // N:1 계약: 내부 타입은 재사용 탐지(WARN 대상)로 구분되지만 클라이언트 코드는 동일하다
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.REFRESH_TOKEN_REUSED);
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_2003);
                });
        verify(refreshTokenRepository).revokeAllByUserId(USER_ID);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rotateAndRevoke_blankToken_throwIllegalArgument() {
        RefreshTokenService service = newService();

        assertThatThrownBy(() -> service.rotate(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rotate(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.revoke(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.revoke(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revoke_callsRevokeByTokenHash_idempotentEvenWhenZeroRows() {
        String raw = "logout-raw";
        when(refreshTokenRepository.revokeByTokenHash(AuthTokens.sha256Hex(raw))).thenReturn(0);

        newService().revoke(raw); // 0행이어도 예외 없음

        verify(refreshTokenRepository).revokeByTokenHash(AuthTokens.sha256Hex(raw));
    }
}
