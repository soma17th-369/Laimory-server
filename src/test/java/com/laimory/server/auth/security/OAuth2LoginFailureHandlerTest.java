package com.laimory.server.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;

/** 로그인 실패 훅 계약: 사유 무관 error 핸드오프 302 + 핸드셰이크 세션 소멸. */
class OAuth2LoginFailureHandlerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(OAuth2LoginFailureHandler.class);
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

    @Test
    void failure_redirectsToErrorHandoff_andInvalidatesSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuth2LoginFailureHandler()
                .onAuthenticationFailure(request, response, new BadCredentialsException("denied"));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?error=-2004");
        assertThat(session.isInvalid()).isTrue();
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel().toString()).isEqualTo(Level.WARN.toString());
        assertThat(logs.list.get(0).getFormattedMessage())
                .contains("type=BadCredentialsException", "message=denied");
    }

    @Test
    void failure_withoutSession_stillRedirects() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OAuth2LoginFailureHandler()
                .onAuthenticationFailure(request, response, new BadCredentialsException("denied"));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/auth/app?error=-2004");
    }
}
