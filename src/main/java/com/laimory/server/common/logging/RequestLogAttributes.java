package com.laimory.server.common.logging;

/**
 * 필터↔예외 핸들러 간 request attribute 키 계약(매직 스트링 방지).
 *
 * <p>예외 핸들러가 에러 코드를 심어두면 {@link TransactionIdFilter}의 요청 완료 로그가
 * {@code errorCode} 필드로 함께 남긴다.
 */
public final class RequestLogAttributes {

    /** 요청을 실패시킨 에러 코드 attribute 키. */
    public static final String ERROR_CODE = RequestLogAttributes.class.getName() + ".errorCode";

    private RequestLogAttributes() {
    }
}
