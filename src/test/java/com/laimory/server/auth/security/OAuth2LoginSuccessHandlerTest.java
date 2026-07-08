package com.laimory.server.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.auth.service.SocialLoginService;
import com.laimory.server.user.Provider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** 로그인 성공 훅 계약: 세션 challenge로 app_code 발급 후 핸드오프 302 + 세션 소멸, 비정상 진입은 error 핸드오프. */
@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    @Mock
    private SocialLoginService socialLoginService;

    private OAuth2AuthenticationToken googleToken(OidcUser oidcUser) {
        return new OAuth2AuthenticationToken(
                oidcUser, List.of(new SimpleGrantedAuthority("OIDC_USER")), "google");
    }

    private OidcUser oidcUser() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("t")
                .claim("sub", "google-sub")
                .claim("email", "e@x.com")
                .claim("name", "이름")
                .build();
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken);
    }

    @Test
    void success_issuesAppCodeAndRedirectsToHandoff_andInvalidatesSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AppChallengeFilter.APP_CHALLENGE_SESSION_ATTRIBUTE, "challenge-43");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(socialLoginService.completeLogin(Provider.GOOGLE, "google-sub", "e@x.com", "이름", "challenge-43"))
                .thenReturn("raw-app-code");

        new OAuth2LoginSuccessHandler(socialLoginService)
                .onAuthenticationSuccess(request, response, googleToken(oidcUser()));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?code=raw-app-code");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void success_whenCompleteLoginThrows_redirectsToErrorHandoffInsteadOf500() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AppChallengeFilter.APP_CHALLENGE_SESSION_ATTRIBUTE, "challenge-43");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(socialLoginService.completeLogin(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        new OAuth2LoginSuccessHandler(socialLoginService)
                .onAuthenticationSuccess(request, response, googleToken(oidcUser()));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?error=ERROR_2004");
    }

    @Test
    void success_withoutChallengeInSession_redirectsToErrorHandoff() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession()); // challenge 미보관(비정상 진입)
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuth2LoginSuccessHandler(socialLoginService)
                .onAuthenticationSuccess(request, response, googleToken(oidcUser()));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?error=ERROR_2004");
        verify(socialLoginService, never()).completeLogin(any(), any(), any(), any(), any());
    }

    @Test
    void success_withNonOidcPrincipal_redirectsToErrorHandoff() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AppChallengeFilter.APP_CHALLENGE_SESSION_ATTRIBUTE, "challenge-43");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2User plainUser = mock(OAuth2User.class); // OidcUser가 아님(openid scope 누락 등)
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
                plainUser, List.of(new SimpleGrantedAuthority("OAUTH2_USER")), "google");

        new OAuth2LoginSuccessHandler(socialLoginService).onAuthenticationSuccess(request, response, token);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?error=ERROR_2004");
        verify(socialLoginService, never()).completeLogin(any(), any(), any(), any(), any());
    }
}
