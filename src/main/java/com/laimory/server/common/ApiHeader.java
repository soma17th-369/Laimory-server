package com.laimory.server.common;

/**
 * 응답 envelope 공통 헤더: 결과 코드 + 메시지. 성공은 항상 COMMON_0000.
 *
 * <p>요청 추적 식별자(에러 화면 "문의 코드")는 envelope가 아니라 HTTP 응답 헤더
 * {@code Transaction-Id}로 노출된다 — {@link com.laimory.server.common.logging.TransactionIdFilter} 참고.
 */
public record ApiHeader(String code, String message) {
}
