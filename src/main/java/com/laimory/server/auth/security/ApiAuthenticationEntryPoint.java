package com.laimory.server.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.logging.RequestLogAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * {@code /a/api} 인가 거절(무인증)의 401 {@code -2001} envelope 직접 작성.
 *
 * <p>Security filter 단계는 {@code GlobalExceptionHandler}에 도달하지 않으므로 공통 envelope을 여기서
 * 직접 쓴다({@link AppChallengeFilter}의 400 작성이 선례). Bearer 부재/무효/만료는 사유 구분 없이 같은
 * 응답으로 수렴하고({@code WWW-Authenticate: Bearer}, RFC 6750), token·헤더 원문·parse 실패 상세는
 * 응답과 로그 어디에도 남기지 않는다. {@code Transaction-Id} 헤더는 trusted-edge 경계 바로 다음의
 * 전역 {@code TransactionIdFilter}가 이미 심어 둔다.
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(MessageSource messageSource, ObjectMapper objectMapper) {
        this.messageSource = messageSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // access 로그의 errorCode=-2001·INFO 레벨은 이 attribute에서 파생된다(EXCEPTION_TYPE 계약).
        request.setAttribute(RequestLogAttributes.EXCEPTION_TYPE, ExceptionType.API_AUTHENTICATION_REQUIRED);
        // 필터 단계는 MVC LocaleResolver(spring.web.locale=ko) 미적용 — 헤더 없으면 한국어로 직접 폴백.
        Locale locale = request.getHeader("Accept-Language") == null ? Locale.KOREAN : request.getLocale();
        ExceptionType type = ExceptionType.API_AUTHENTICATION_REQUIRED;
        String message = messageSource.getMessage(type.messageKey(), null, locale);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(type.code(), message));
    }
}
