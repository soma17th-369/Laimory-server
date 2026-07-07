package com.laimory.server.auth.service;

import com.laimory.server.auth.dto.TokenResponse;
import com.laimory.server.auth.token.JwtTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 토큰 발급/갱신/로그아웃 오케스트레이터. Repository를 직접 주입하지 않고
 * {@link AppCodeService}·{@link RefreshTokenService}·{@link JwtTokens}를 합성한다(1:1 규칙).
 *
 * <p>트랜잭션을 걸지 않는다 — 회전의 커밋 semantics는 {@link RefreshTokenService#rotate} 주석 참고.
 */
@Service
@RequiredArgsConstructor
public class AuthTokenService {

    private final AppCodeService appCodeService;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokens jwtTokens;

    /** app_code + verifier를 검증해 토큰 쌍을 발급한다(app_code는 이 시점에 일회 소비). */
    public TokenResponse issueTokens(String applicationVersion, String appCode, String appVerifier) {
        // applicationVersion: 버전별 분기 지점(현재 단일 버전이라 분기 없음).
        long userId = appCodeService.consume(appCode, appVerifier);
        return new TokenResponse(jwtTokens.issueAccessToken(userId), refreshTokenService.issue(userId));
    }

    /** refresh를 회전하고 새 토큰 쌍을 반환한다. 이전 refresh는 이 시점에 무효화된다. */
    public TokenResponse refresh(String applicationVersion, String refreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken);
        return new TokenResponse(jwtTokens.issueAccessToken(rotation.userId()), rotation.refreshToken());
    }

    /** 제시된 refresh만 폐기한다(access는 만료로 자연 소멸 — 서버 미저장). 멱등. */
    public void logout(String applicationVersion, String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }
}
