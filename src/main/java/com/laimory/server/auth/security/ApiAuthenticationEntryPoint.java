package com.laimory.server.auth.security;

import com.laimory.server.common.error.ExceptionType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * {@code /a/api} 인가 거절(무인증)의 401 {@code -2001} envelope 직접 작성.
 *
 * <p>Security filter 단계는 {@code GlobalExceptionHandler}에 도달하지 않으므로 공통 envelope을
 * {@link ApiErrorResponseWriter}로 직접 쓴다({@link AppChallengeFilter}의 400 작성이 선례). Bearer
 * 부재/무효/만료와 탈퇴·삭제 회원(#305 — 필터가 SecurityContext를 만들지 않음)은 사유 구분 없이 같은
 * 응답으로 수렴하고({@code WWW-Authenticate: Bearer}, RFC 6750), token·헤더 원문·parse 실패 상세는
 * 응답과 로그 어디에도 남기지 않는다. {@code Transaction-Id} 헤더는 trusted-edge 경계 바로 다음의
 * 전역 {@code TransactionIdFilter}가 이미 보장한다.
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorResponseWriter apiErrorResponseWriter;

    public ApiAuthenticationEntryPoint(ApiErrorResponseWriter apiErrorResponseWriter) {
        this.apiErrorResponseWriter = apiErrorResponseWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        apiErrorResponseWriter.write(request, response, ExceptionType.API_AUTHENTICATION_REQUIRED);
    }
}
