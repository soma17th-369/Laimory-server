package com.laimory.server.auth.security;

import com.laimory.server.auth.service.SocialLoginService;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.user.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * OIDC 로그인 성공 훅. id_token 검증까지 끝난 사용자로 find-or-create 후 일회용 app_code를 발급해
 * 핸드오프 링크({@code /auth/app?code=})로 302한다.
 *
 * <p>세션은 여기서 소멸시킨다 — 서버측 로그인 상태(OAuth 인증 결과)를 세션에 남기지 않는다.
 * 이후 인증은 앱이 app_code를 교환해 받는 자체 토큰으로만 이뤄진다(stateless).
 */
@Slf4j
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final SocialLoginService socialLoginService;

    public OAuth2LoginSuccessHandler(SocialLoginService socialLoginService) {
        this.socialLoginService = socialLoginService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        HttpSession session = request.getSession(false);
        String appChallenge = session == null
                ? null
                : (String) session.getAttribute(AppChallengeFilter.APP_CHALLENGE_SESSION_ATTRIBUTE);
        if (session != null) {
            session.invalidate();
        }

        if (appChallenge == null
                || !(authentication instanceof OAuth2AuthenticationToken oauthToken)
                || !(oauthToken.getPrincipal() instanceof OidcUser oidcUser)) {
            // AppChallengeFilter가 보장하는 불변식(시작 시 challenge 보관 + openid scope → OidcUser)이 깨진
            // 비정상 진입(콜백 직접 호출 등) — 실패 핸드오프로 수렴시킨다.
            log.warn("oauth2 login success without handoff context: challengePresent={} principalType={}",
                    appChallenge != null, authentication.getPrincipal().getClass().getSimpleName());
            response.sendRedirect(HandoffRedirects.uri(request, "error", ErrorCode.ERROR_2004.code()));
            return;
        }

        String appCode;
        try {
            Provider provider = Provider.fromRegistrationId(oauthToken.getAuthorizedClientRegistrationId());
            ProviderProfile profile = ProviderProfile.from(provider, oidcUser);
            appCode = socialLoginService.completeLogin(
                    provider, oidcUser.getSubject(), profile.email(), profile.nickname(), appChallenge);
        } catch (RuntimeException e) {
            // 로그인 마무리 실패(DB 순단 등)도 raw 500 대신 다른 실패 경로와 같은 error 핸드오프로 수렴.
            // 필터 단계라 GlobalExceptionHandler 미도달 — 예상 못한 실패이므로 여기서만 stacktrace를 남긴다.
            log.error("social login completion failed: registrationId={}",
                    oauthToken.getAuthorizedClientRegistrationId(), e);
            response.sendRedirect(HandoffRedirects.uri(request, "error", ErrorCode.ERROR_2004.code()));
            return;
        }
        response.sendRedirect(HandoffRedirects.uri(request, "code", appCode));
    }

    /**
     * provider별 id_token claim → 우리 프로필 계약 매핑. 유일성은 (provider, sub)가 담당하므로 둘 다 null 허용.
     * provider가 늘면 switch가 컴파일 단계에서 매핑 누락을 강제한다.
     */
    private record ProviderProfile(String email, String nickname) {

        static ProviderProfile from(Provider provider, OidcUser oidcUser) {
            return switch (provider) {
                // Kakao: email 미수집(콘솔 권한 없음), 닉네임은 검증된 id_token의 nickname claim만 사용.
                case KAKAO -> new ProviderProfile(null, kakaoNickname(oidcUser));
                case GOOGLE -> new ProviderProfile(oidcUser.getEmail(), oidcUser.getFullName());
            };
        }

        /** Kakao id_token의 선택 claim {@code nickname} — 미동의·미제공이거나 문자열이 아니거나 blank면 null. */
        private static String kakaoNickname(OidcUser oidcUser) {
            return oidcUser.getClaims().get("nickname") instanceof String s && !s.isBlank() ? s : null;
        }
    }
}
