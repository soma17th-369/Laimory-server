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
 * 이 검사는 #441부터 필터와 같은 공유 Redis 캐시({@link UserAccountService#isActive})를 경유한다 —
 * 탈퇴 evict 뒤 시작된 발급·회전은 miss → DB로 결정적으로 거절되고, evict 유실·늦은 적재로 stale
 * 엔트리가 남은 창에서만 한시적으로 통과할 수 있다(#429 "보안 정책 개정"의 허용 범위. #367이
 * 보존한 refresh 행의 차단은 이 검사가 담당한다). 그렇게 발급된 token도 {@code /a/api} ACTIVE
 * 검사와 다음 회전 검사가 같은 캐시·DB로 재검사하므로 stale 창 너머로는 사용되지 않는다.
 * DB 조회 장애는 여기서 삼키지 않고 전파해 기존 500/ERROR 진단 경로를 유지한다(Redis 장애는
 * 캐시가 miss로 강등해 DB 직행 — fail-safe-to-DB).
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
