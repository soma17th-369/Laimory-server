package com.laimory.server.timeline.service;

/**
 * AI 접수의 <b>미접수 확정</b> 신호 — 접수 요청이 거절돼 접수·background 처리가 시작되지 않았음이
 * 확실한 경우에만 던진다. 호출부는 이 예외에서만 task를 FAILED로 종결한다.
 *
 * <p>transport별 발생 조건:
 * <ul>
 *   <li>{@code http} mode: AI HTTP 계층이 4xx 응답으로 요청을 거절(스키마 422 등).</li>
 *   <li>{@code agentcore} mode: ① 전송 전 자체 검증 실패(runtime session id 계약·요청 직렬화 —
 *       호출 자체를 하지 않았다), ② AgentCore service가 runtime 호출 전에 거절한 4xx
 *       (ValidationException·AccessDenied·ResourceNotFound·Throttling·ServiceQuotaExceeded 등),
 *       ③ AI runtime이 4xx {@code statusCode}로 돌려준 ack.</li>
 * </ul>
 *
 * <p>이 타입이 아닌 예외(read timeout·connect 실패·5xx·응답 계약 불일치, AgentCore의
 * RetryableConflict·RuntimeClientError·InternalServer)는 <b>접수 여부 불명(UNKNOWN)</b>이다 — AI가 이미
 * 접수해 final write를 진행 중일 수 있으므로 호출부는 FAILED로 확정하지 않고 PROCESSING을 유지해 AI
 * callback 또는 TTL 만료가 종결하게 한다.
 */
public class TimelineAiDispatchRejectedException extends RuntimeException {

    public TimelineAiDispatchRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
