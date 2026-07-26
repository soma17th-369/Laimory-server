package com.laimory.server.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.laimory.server.auth.service.SocialLoginService;
import com.laimory.server.user.Provider;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
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

    private final Logger logger = (Logger) LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attachLogger() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogger() {
        logger.detachAppender(logs);
        logs.stop();
    }

    private OAuth2AuthenticationToken googleToken(OidcUser oidcUser) {
        return new OAuth2AuthenticationToken(
                oidcUser, List.of(new SimpleGrantedAuthority("OIDC_USER")), "google");
    }

    private OAuth2AuthenticationToken kakaoToken(OidcUser oidcUser) {
        return new OAuth2AuthenticationToken(
                oidcUser, List.of(new SimpleGrantedAuthority("OIDC_USER")), "kakao");
    }

    private OidcUser oidcUser() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("t")
                .claim("sub", "google-sub")
                .claim("email", "e@x.com")
                .claim("name", "이름")
                .build();
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken);
    }

    private OidcUser kakaoOidcUser(Object nicknameClaim) {
        OidcIdToken.Builder idToken = OidcIdToken.withTokenValue("t").claim("sub", "kakao-sub");
        if (nicknameClaim != null) {
            idToken.claim("nickname", nicknameClaim);
        }
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken.build());
    }

    private MockHttpServletRequest requestWithChallenge() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AppChallengeFilter.APP_CHALLENGE_SESSION_ATTRIBUTE, "challenge-43");
        request.setSession(session);
        return request;
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
    void success_kakao_passesIdTokenNicknameAndNullEmail() throws Exception {
        MockHttpServletRequest request = requestWithChallenge();
        MockHttpServletResponse response = new MockHttpServletResponse();
        // 예상 밖 email claim이 있어도 Kakao email 인자는 null이어야 한다(이메일 미수집 계약).
        OidcIdToken idToken = OidcIdToken.withTokenValue("t")
                .claim("sub", "kakao-sub")
                .claim("nickname", "라이머")
                .claim("email", "unexpected@x.com")
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken);
        when(socialLoginService.completeLogin(Provider.KAKAO, "kakao-sub", null, "라이머", "challenge-43"))
                .thenReturn("raw-app-code");

        new OAuth2LoginSuccessHandler(socialLoginService)
                .onAuthenticationSuccess(request, response, kakaoToken(oidcUser));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?code=raw-app-code");
    }

    @Test
    void success_kakao_missingOrBlankOrNonStringNickname_passesNull() throws Exception {
        when(socialLoginService.completeLogin(Provider.KAKAO, "kakao-sub", null, null, "challenge-43"))
                .thenReturn("raw-app-code");

        for (Object claim : new Object[] {null, " ", 42}) {
            MockHttpServletRequest request = requestWithChallenge();
            MockHttpServletResponse response = new MockHttpServletResponse();

            new OAuth2LoginSuccessHandler(socialLoginService)
                    .onAuthenticationSuccess(request, response, kakaoToken(kakaoOidcUser(claim)));

            assertThat(response.getRedirectedUrl())
                    .as("nickname claim=%s", claim)
                    .isEqualTo("http://localhost/auth/app?code=raw-app-code");
        }
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

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?error=-2004");
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel().toString()).isEqualTo(Level.ERROR.toString());
        assertThat(logs.list.get(0).getThrowableProxy()).isNotNull();
    }

    @Test
    void success_withoutChallengeInSession_redirectsToErrorHandoff() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession()); // challenge 미보관(비정상 진입)
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuth2LoginSuccessHandler(socialLoginService)
                .onAuthenticationSuccess(request, response, googleToken(oidcUser()));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?error=-2004");
        verify(socialLoginService, never()).completeLogin(any(), any(), any(), any(), any());
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel().toString()).isEqualTo(Level.WARN.toString());
        assertThat(logs.list.get(0).getFormattedMessage())
                .contains("oauth2 login success without handoff context");
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

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?error=-2004");
        verify(socialLoginService, never()).completeLogin(any(), any(), any(), any(), any());
    }
}
