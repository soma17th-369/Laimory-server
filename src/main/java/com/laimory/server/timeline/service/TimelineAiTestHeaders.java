package com.laimory.server.timeline.service;

/**
 * dev 전용 AI 동기 테스트 경로의 응답 헤더 이름. AI에서 받는 쪽(client)과 호출자에게 내보내는 쪽
 * (controller)이 같은 문자열을 써야 해서 한곳에서만 정의한다.
 */
public final class TimelineAiTestHeaders {

    /**
     * AI가 제한 시간 안에 <b>마지막 확정본</b>을 돌려줬다는 표시(실패가 아니다). AI 응답에서 받아 우리
     * 응답에 그대로 전달한다.
     */
    public static final String TIMED_OUT = "X-Timeline-Timed-Out";

    /**
     * AI가 낸 numeric errorCode. 502 envelope은 {@code body=null}이 계약이라 서로 다른 AI 실패
     * (구조화 출력 실패·환각·시간 초과 등)를 호출자가 구분할 자리가 없어 헤더로 내보낸다.
     * 자유 text {@code error}는 사용자 원문이 섞일 수 있어 <b>절대 싣지 않는다</b>.
     */
    public static final String AI_ERROR_CODE = "X-Ai-Error-Code";

    private TimelineAiTestHeaders() {
    }
}
