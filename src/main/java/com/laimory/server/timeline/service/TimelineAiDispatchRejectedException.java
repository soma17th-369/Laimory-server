package com.laimory.server.timeline.service;

/**
 * AI 접수의 <b>미접수 확정</b> 신호 — AI HTTP 계층이 4xx 응답으로 요청을 거절해 접수·background
 * 처리가 시작되지 않았음이 확실한 경우에만 던진다. 호출부는 이 예외에서만 task를 FAILED로 종결한다.
 *
 * <p>이 타입이 아닌 예외(read timeout·connect 실패·5xx·응답 계약 불일치)는
 * <b>접수 여부 불명(UNKNOWN)</b>이다 — AI가 이미 접수해 final write를 진행 중일 수 있으므로
 * 호출부는 FAILED로 확정하지 않고 PROCESSING을 유지해 AI callback 또는 TTL 만료가 종결하게 한다.
 */
public class TimelineAiDispatchRejectedException extends RuntimeException {

    public TimelineAiDispatchRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
