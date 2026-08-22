package com.laimory.server.timeline.dto;

/**
 * AI result 저장 성공 뒤 callback에 사용할 다음 task token.
 *
 * <p>신규 저장과 응답 유실 재시도가 같은 shape를 반환한다 — AI는 어느 쪽이든 받은 token으로 SUCCESS
 * callback을 보내면 되고 분기가 없다. 재시도마다 새로 발급되므로 <b>마지막으로 받은 응답의 token만</b>
 * 유효하다.
 */
public record AiTimelineResultResponse(String taskToken) {
}
