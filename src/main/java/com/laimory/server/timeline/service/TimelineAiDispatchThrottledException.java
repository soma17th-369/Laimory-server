package com.laimory.server.timeline.service;

/**
 * 접수 요청이 용량 한도(throttling·quota 초과)로 거절된 <b>미접수 확정</b>이다(#515). AgentCore가 runtime 호출 전에
 * 돌려준 {@code ThrottlingException}·{@code ThrottledException}·{@code ServiceQuotaExceededException}만 이 타입이다.
 *
 * <p>상위 타입의 하위 분류라 이 타입을 따로 잡지 않는 호출부(inline 접수)는 지금처럼 거절로 처리한다. 접수 대기열
 * worker와 User Memory 배치는 이 타입을 먼저 잡아 잠시 뒤 같은 token으로 다시 보낸다 — 접수되지 않았음이 확실해
 * 재호출해도 AI가 두 번 실행되지 않는다.
 */
public class TimelineAiDispatchThrottledException extends TimelineAiDispatchRejectedException {

    public TimelineAiDispatchThrottledException(String message, Throwable cause) {
        super(message, cause);
    }
}
