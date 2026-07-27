package com.laimory.server.auth.security;

import com.laimory.server.common.error.ExceptionType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * OIDC 로그인 실패 훅(사용자 거부·state 불일치·id_token 검증 실패 등). 실패 사유는 서버 로그에만 남기고,
 * 앱에는 핸드오프 링크의 {@code ?error=-2004} 파라미터로만 알린다(사유 구분은 클라 행동을 바꾸지 않음 — 전부 재시도).
 */
@Slf4j
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate(); // 실패한 핸드셰이크의 중간상태(challenge·authorization request)를 남기지 않는다.
        }
        log.warn("oauth2 login failed: type={} message={}",
                exception.getClass().getSimpleName(), exception.getMessage());
        response.sendRedirect(HandoffRedirects.uri(
                request, "error", Integer.toString(ExceptionType.OAUTH_LOGIN_FAILED.code())));
    }
}
