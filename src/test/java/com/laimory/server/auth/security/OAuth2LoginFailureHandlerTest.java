package com.laimory.server.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;

/** 로그인 실패 훅 계약: 사유 무관 error 핸드오프 302 + 핸드셰이크 세션 소멸. */
class OAuth2LoginFailureHandlerTest {

    @Test
    void failure_redirectsToErrorHandoff_andInvalidatesSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuth2LoginFailureHandler()
                .onAuthenticationFailure(request, response, new BadCredentialsException("denied"));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?error=ERROR_2004");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void failure_withoutSession_stillRedirects() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuth2LoginFailureHandler()
                .onAuthenticationFailure(request, response, new BadCredentialsException("denied"));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?error=ERROR_2004");
    }
}
