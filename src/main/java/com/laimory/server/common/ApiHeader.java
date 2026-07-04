package com.laimory.server.common;

/**
 * 성공 응답 공통 헤더: 결과 코드 + 메시지 + 요청 추적 식별자. 성공은 항상 COMMON_0000.
 *
 * <p>{@code transactionId}는 요청 MDC의 transactionId(서버가 요청마다 발급)이며 클라이언트 노출의
 * 유일한 채널이다 — 에러 화면 "문의 코드" 등으로 노출해 서버 로그를 특정하는 데 쓴다.
 */
public record ApiHeader(String code, String message, String transactionId) {
}
