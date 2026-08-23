package com.laimory.server.common.logging;

/**
 * 필터·인증·예외 핸들러가 {@link TransactionIdFilter}의 완료 로그로 값을 넘기는 request attribute 키
 * 계약(매직 스트링 방지).
 *
 * <p>예외 처리 지점이 {@code ExceptionType}(과 선택적 상세)을 심어두면 요청 완료 로그가
 * {@code exceptionType}·{@code errorCode}·{@code errorDetail} 필드로 함께 남기고,
 * 로그 레벨도 {@code ExceptionType.logLevel()}에서 가져온다.
 *
 * <p>인증 필터는 {@link #USER_ID}를 심는다. 완료 로그는 security chain 바깥(필터 순서상 더 바깥)의
 * {@code finally}에서 찍혀 그 시점엔 {@code SecurityContextHolder}가 이미 비워져 있으므로,
 * 요청 스코프인 attribute가 인증 주체를 로그로 넘기는 유일한 경로다.
 */
public final class RequestLogAttributes {

    /** 요청을 실패시킨 내부 예외 타입 attribute 키 — {@code common.error.ExceptionType} enum을 담는다. */
    public static final String EXCEPTION_TYPE = RequestLogAttributes.class.getName() + ".exceptionType";

    /** 로그 전용 실패 상세(예외 클래스명·검증 메시지 등) attribute 키 — String을 담는다. */
    public static final String ERROR_DETAIL = RequestLogAttributes.class.getName() + ".errorDetail";

    /** 인증에 성공한 요청의 사용자 식별자 attribute 키 — {@code Long} userId를 담는다. */
    public static final String USER_ID = RequestLogAttributes.class.getName() + ".userId";

    private RequestLogAttributes() {
    }
}
