package com.laimory.server.timeline.service;

/**
 * AI 접수의 <b>미접수 확정</b> 신호 — AI가 이 요청을 접수하지 않았음이 확실한 경우에만 던진다:
 * 비202 응답을 실제로 수신했거나(계약상 접수는 202로만 신호), connect 단계 실패로 요청이 전송조차
 * 되지 않은 경우. 호출부는 이 예외에서만 task를 FAILED로 종결하고 guard를 해제해도 안전하다.
 *
 * <p>이 타입이 아닌 예외(read timeout·2xx 계약 불일치 등)는 <b>접수 여부 불명(UNKNOWN)</b>이다 —
 * AI가 이미 접수해 final write를 진행 중일 수 있으므로 FAILED 확정·guard 해제를 하면 안 된다
 * (결과 불일치 + AI write와 새 draft/삭제가 겹치는 경로). 호출부는 PROCESSING과 guard를 유지하고
 * AI callback 또는 TTL 만료가 종결하게 한다.
 */
public class TimelineAiDispatchRejectedException extends RuntimeException {

    public TimelineAiDispatchRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
