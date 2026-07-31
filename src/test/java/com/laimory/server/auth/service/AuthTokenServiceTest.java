package com.laimory.server.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.auth.dto.TokenResponse;
import com.laimory.server.auth.token.JwtTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 토큰 오케스트레이터 단위 검증: consume/rotate가 결정한 userId 하나가 access/refresh 발급 양쪽에
 * 흐르는지, 실패 시 후속 발급이 호출되지 않는지(short-circuit) 고정한다. App Code·Refresh 저장소의
 * 자체 계약(원자성·회전 커밋)은 각 leaf 테스트가 소유하므로 여기서 다시 검증하지 않는다. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class AuthTokenServiceTest {

    private static final String VERSION = "v1";
    private static final long USER_ID = 42L;

    @Mock
    private AppCodeService appCodeService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private JwtTokens jwtTokens;

    private AuthTokenService service;

    @BeforeEach
    void setUp() {
        service = new AuthTokenService(appCodeService, refreshTokenService, jwtTokens);
    }

    @Test
    void issueTokens_usesConsumedUserIdForBothTokens() {
        when(appCodeService.consume("app-code", "verifier")).thenReturn(USER_ID);
        when(jwtTokens.issueAccessToken(USER_ID)).thenReturn("access-42");
        when(refreshTokenService.issue(USER_ID)).thenReturn("refresh-42");

        TokenResponse response = service.issueTokens(VERSION, "app-code", "verifier");

        // 소비 시점에 확정된 userId 하나가 JWT/refresh 양쪽에 그대로 전달되고 두 결과가 한 쌍으로 묶인다.
        assertThat(response.accessToken()).isEqualTo("access-42");
        assertThat(response.refreshToken()).isEqualTo("refresh-42");
        verify(jwtTokens).issueAccessToken(USER_ID);
        verify(refreshTokenService).issue(USER_ID);
    }

    @Test
    void issueTokens_consumeFails_issuesNothing() {
        RuntimeException invalidCode = new RuntimeException("invalid app code");
        when(appCodeService.consume("app-code", "verifier")).thenThrow(invalidCode);

        assertThatThrownBy(() -> service.issueTokens(VERSION, "app-code", "verifier"))
                .isSameAs(invalidCode);

        // 소비 실패 뒤 토큰이 하나라도 발급되면 미인증 발급이다 — 후속 호출 자체가 없어야 한다.
        verify(jwtTokens, never()).issueAccessToken(anyLong());
        verify(refreshTokenService, never()).issue(anyLong());
    }

    @Test
    void refresh_usesRotationUserIdAndReturnsRotatedToken() {
        when(refreshTokenService.rotate("old-refresh"))
                .thenReturn(new RefreshTokenService.Rotation(USER_ID, "new-refresh"));
        when(jwtTokens.issueAccessToken(USER_ID)).thenReturn("access-42");

        TokenResponse response = service.refresh(VERSION, "old-refresh");

        // access는 회전이 확정한 userId로 발급하고, refresh는 회전 결과를 재발급 없이 그대로 반환한다.
        assertThat(response.accessToken()).isEqualTo("access-42");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        verify(jwtTokens).issueAccessToken(USER_ID);
        verify(refreshTokenService, never()).issue(anyLong());
    }

    @Test
    void refresh_rotateFails_issuesNoAccessToken() {
        RuntimeException invalidRefresh = new RuntimeException("invalid refresh token");
        when(refreshTokenService.rotate("old-refresh")).thenThrow(invalidRefresh);

        assertThatThrownBy(() -> service.refresh(VERSION, "old-refresh"))
                .isSameAs(invalidRefresh);

        verify(jwtTokens, never()).issueAccessToken(anyLong());
    }

    @Test
    void logout_revokesPresentedTokenOnly() {
        service.logout(VERSION, "refresh-42");

        // access는 서버에 저장되지 않아 만료로 소멸한다 — logout은 제시된 refresh 폐기만 수행한다.
        verify(refreshTokenService).revoke("refresh-42");
        verifyNoInteractions(jwtTokens, appCodeService);
    }
}
