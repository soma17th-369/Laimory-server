package com.laimory.server.common.error;

import lombok.Getter;

/**
 * 의도된 비즈니스 에러. 서비스는 상황에 맞는 {@link ErrorCode}만 골라 던지고,
 * HTTP status·응답 shape·메시지 로캘은 전부 {@link GlobalExceptionHandler}가 결정한다.
 *
 * <p>{@code args}는 메시지 번들의 {@code {0}} 파라미터로 들어간다 — 응답/로그에 노출되므로
 * taskId 같은 <b>서버 생성 값만</b> 넣고 사용자 입력은 넣지 않는다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object[] args;

    public BusinessException(ErrorCode errorCode, Object... args) {
        super(errorCode.name()); // 로그·테스트에서 null 메시지 방지
        this.errorCode = errorCode;
        this.args = args;
    }
}
