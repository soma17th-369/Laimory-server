package com.laimory.server.common.error;

import lombok.Getter;

/**
 * 의도된 비즈니스 에러. 서비스는 상황에 맞는 {@link ExceptionType}만 골라 던지고,
 * HTTP status·응답 shape·메시지 로캘은 {@link GlobalExceptionHandler}가, 로그 레벨은
 * access 로그 필터가 {@code ExceptionType.logLevel()}로 결정한다.
 *
 * <p>{@code args}는 메시지 번들의 {@code {0}} 파라미터로 들어간다 — 응답/로그에 노출되므로
 * taskId 같은 <b>서버 생성 값만</b> 넣고 사용자 입력은 넣지 않는다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ExceptionType exceptionType;
    private final transient Object[] args;

    public BusinessException(ExceptionType exceptionType, Object... args) {
        super(exceptionType.name()); // 로그·테스트에서 null 메시지 방지
        this.exceptionType = exceptionType;
        this.args = args;
    }

    /** 클라이언트 응답 계약 코드 — {@link ExceptionType}의 N:1 매핑을 따른다. */
    public ErrorCode getErrorCode() {
        return exceptionType.errorCode();
    }
}
