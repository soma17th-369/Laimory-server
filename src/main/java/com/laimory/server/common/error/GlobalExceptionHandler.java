package com.laimory.server.common.error;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.logging.RequestLogAttributes;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 전역 예외 → {@code ApiResponse} envelope 변환의 단일 지점.
 *
 * <p>{@link ResponseEntityExceptionHandler}를 상속해 Spring MVC 표준 예외
 * (404 {@code NoResourceFound}, 405, 415, 깨진 JSON, 타입 미스매치, 향후 {@code @Valid}까지)는
 * 베이스 클래스가 status/headers를 정해 {@link #handleExceptionInternal}로 모이고,
 * 여기서 envelope body만 만든다 — 예외 타입 열거 누락으로 4xx가 500으로 강등되는 문제를 구조적으로 방지한다.
 *
 * <p>로깅 정책: 4xx WARN / 예상 못한 예외만 ERROR+stacktrace. 에러 코드는
 * {@link RequestLogAttributes#ERROR_CODE}로 심어 access 로그({@code TransactionIdFilter})에 합류시킨다.
 * 5xx의 원인 상세는 로그에만 남기고 클라이언트 메시지는 제네릭 문구로 제한한다(내부 정보 비노출).
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    /** envelope 조립 단일 지점: errorCode attribute + 로캘 메시지. */
    private ApiResponse<Void> errorEnvelope(ErrorCode code, Object[] args, HttpServletRequest request) {
        request.setAttribute(RequestLogAttributes.ERROR_CODE, code.code());
        String message = messageSource.getMessage(code.code(), args, LocaleContextHolder.getLocale());
        return ApiResponse.error(code.code(), message);
    }

    // ── (A) Spring MVC 표준 예외 전부: 베이스가 status/headers를 정해 여기로 모인다 ──
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object ignoredBody, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        log.warn("mvc exception: type={} status={}", ex.getClass().getSimpleName(), statusCode.value());
        return ResponseEntity.status(statusCode).headers(headers) // Allow 등 표준 헤더 보존
                .body(errorEnvelope(ErrorCode.fromStatus(statusCode), null, servletRequest));
    }

    // ── (B) 도메인: 서비스가 던진 의도된 에러 ──
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> onBusiness(BusinessException e, HttpServletRequest request) {
        ErrorCode code = e.getErrorCode();
        log.warn("business error: code={}", code.code());
        return ResponseEntity.status(code.status()).body(errorEnvelope(code, e.getArgs(), request));
    }

    // ── (C) 프로그램적 검증 실패 → 400 (메시지는 로그만, 클라이언트엔 i18n 제네릭 문구) ──
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> onIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        log.warn("validation error: {}", e.getMessage());
        return ResponseEntity.badRequest().body(errorEnvelope(ErrorCode.ERROR_0400, null, request));
    }

    // ── (D) RSE 브리지: 도메인 이관 후 src/main엔 던지는 곳 없음 — 순수 안전망. raw status 보존 ──, 나중에 http status로 직접 에러 던질 때 받아줌
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResponse<Void>> onResponseStatus(ResponseStatusException e, HttpServletRequest request) {
        log.warn("response status bridge: status={}", e.getStatusCode().value());
        return ResponseEntity.status(e.getStatusCode())
                .body(errorEnvelope(ErrorCode.fromStatus(e.getStatusCode()), null, request));
    }

    // ── (E) catch-all: 예상 못한 예외만. 유일하게 stacktrace를 남긴다 ──
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> onUnexpected(Exception e, HttpServletRequest request) {
        log.error("unexpected error: type={}", e.getClass().getName(), e);
        return ResponseEntity.internalServerError().body(errorEnvelope(ErrorCode.ERROR_0500, null, request));
    }
}
