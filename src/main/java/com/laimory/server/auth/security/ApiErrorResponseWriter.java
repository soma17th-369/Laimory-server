package com.laimory.server.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.logging.RequestLogAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.context.MessageSource;

/**
 * Security filter 단계의 {@code ApiResponse} error envelope 직접 작성기(#305에서
 * {@link ApiAuthenticationEntryPoint}의 작성 로직을 분리). filter 단계는
 * {@code GlobalExceptionHandler}에 도달하지 않으므로 envelope·access 로그 attribute를 여기서 만든다 —
 * 401 EntryPoint와 {@link JwtAuthenticationFilter}의 fail-closed 500이 같은 계약을 공유한다.
 *
 * <p>access 로그의 errorCode·레벨은 {@code EXCEPTION_TYPE} attribute에서 파생된다. 필터 단계는 MVC
 * {@code LocaleResolver}(spring.web.locale=ko) 미적용이라 Accept-Language 없으면 한국어로 직접 폴백한다.
 */
public class ApiErrorResponseWriter {

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    public ApiErrorResponseWriter(MessageSource messageSource, ObjectMapper objectMapper) {
        this.messageSource = messageSource;
        this.objectMapper = objectMapper;
    }

    /** type의 status·code·로캘 메시지로 envelope를 쓴다. 추가 헤더는 호출 전에 response에 설정해 둔다. */
    public void write(HttpServletRequest request, HttpServletResponse response, ExceptionType type)
            throws IOException {
        request.setAttribute(RequestLogAttributes.EXCEPTION_TYPE, type);
        Locale locale = request.getHeader("Accept-Language") == null ? Locale.KOREAN : request.getLocale();
        String message = messageSource.getMessage(type.messageKey(), null, locale);
        response.setStatus(type.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(type.code(), message));
    }
}
