package com.laimory.server.common.logging;

/**
 * 필터↔예외 핸들러 간 request attribute 키 계약(매직 스트링 방지).
 *
 * <p>예외 처리 지점이 {@code ExceptionType}(과 선택적 상세)을 심어두면 {@link TransactionIdFilter}의
 * 요청 완료 로그가 {@code exceptionType}·{@code errorCode}·{@code errorDetail} 필드로 함께 남기고,
 * 로그 레벨도 {@code ExceptionType.logLevel()}에서 가져온다.
 */
public final class RequestLogAttributes {

    /** 요청을 실패시킨 내부 예외 타입 attribute 키 — {@code common.error.ExceptionType} enum을 담는다. */
    public static final String EXCEPTION_TYPE = RequestLogAttributes.class.getName() + ".exceptionType";

    /** 로그 전용 실패 상세(예외 클래스명·검증 메시지 등) attribute 키 — String을 담는다. */
    public static final String ERROR_DETAIL = RequestLogAttributes.class.getName() + ".errorDetail";

    private RequestLogAttributes() {
    }
}
