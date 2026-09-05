package com.laimory.server.auth.service;

import com.laimory.server.auth.dto.TokenResponse;
import com.laimory.server.auth.token.JwtTokens;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.user.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 토큰 발급/갱신/로그아웃 오케스트레이터. Repository를 직접 주입하지 않고
 * {@link AppCodeService}·{@link RefreshTokenService}·{@link JwtTokens}를 합성한다(1:1 규칙).
 *
 * <p>발급 경로는 발급 전에 회원의 일반 ACTIVE 조회를 수행한다(#305 §5.4) — 탈퇴/삭제 회원의 발급을
 * 각각 기존 credential 오류({@code -2002}/{@code -2003}, INFO)로 수렴시키고 탈퇴 상태를 노출하지
 * 않는다. 예상된 stale credential 분기라 별도 서비스 로그는 남기지 않는다(access 완료 로그 1건).
 * <b>이 검사는 캐시를 태우지 않고 {@link UserAccountService}로 DB 직행한다(#429)</b> — #367이 refresh
 * 행을 보존하므로 탈퇴자의 회전을 막는 유일한 장치가 이 검사이고, 여기에 캐시를 공유하면 stale 창
 * 안의 회전이 새 token을 낳아 회전 사슬 1회 종결 보장이 깨진다(경계는 arch test로 고정).
 * 검사 통과 직후 탈퇴와 겹친 in-flight 발급은 §5.1의 제한된 예외로, 그 결과 token도 {@code /a/api}
 * ACTIVE 검사(탈퇴 evict 뒤 miss부터)와 다음 회전 ACTIVE 검사에서 거절된다. DB 조회 장애는 여기서
 * 삼키지 않고 전파해 기존 500/ERROR 진단 경로를 유지한다.
 *
 * <p>트랜잭션을 걸지 않는다 — 회전의 커밋 semantics는 {@link RefreshTokenService#rotate} 주석 참고.
 */
@Service
@RequiredArgsConstructor
public class AuthTokenService {

    private final AppCodeService appCodeService;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokens jwtTokens;
    private final UserAccountService userAccountService;

    /** app_code + verifier를 검증해 토큰 쌍을 발급한다(app_code는 이 시점에 일회 소비). */
    public TokenResponse issueTokens(String applicationVersion, String appCode, String appVerifier) {
        // applicationVersion: 버전별 분기 지점(현재 단일 버전이라 분기 없음).
        long userId = appCodeService.consume(appCode, appVerifier);
        if (!userAccountService.isActive(userId)) {
            // app code 발급 후 탈퇴한 회원 — 신규 탈퇴 전용 code 없이 기존 -2002(INFO)로 수렴(code는 이미 소비됨).
            throw new BusinessException(ExceptionType.APP_CODE_INVALID);
        }
        return new TokenResponse(jwtTokens.issueAccessToken(userId), refreshTokenService.issue(userId));
    }

    /** refresh를 회전하고 새 토큰 쌍을 반환한다. 이전 refresh는 이 시점에 무효화된다. */
    public TokenResponse refresh(String applicationVersion, String refreshToken) {
        RefreshTokenService.Rotation rotation =
                refreshTokenService.rotate(refreshToken, userAccountService::isActive);
        return new TokenResponse(jwtTokens.issueAccessToken(rotation.userId()), rotation.refreshToken());
    }

    /** 제시된 refresh만 폐기한다(access는 만료로 자연 소멸 — 서버 미저장). 멱등. */
    public void logout(String applicationVersion, String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }
}
