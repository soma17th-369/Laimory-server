package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import java.util.List;

/**
 * AI 동기 테스트 결과 — client가 만들어 controller까지 그대로 올라간다.
 *
 * <p>{@code timedOut}은 응답 body가 아니라 {@code X-Timeline-Timed-Out} 헤더로만 나가므로 wire DTO
 * ({@code TimelineAiTestResponse})에 넣지 않고 여기 둔다. {@code taskId}는 요청에 실어 보낸 값 그대로라
 * 호출자 응답과 AI 로그가 같은 상관키를 갖는다.
 */
public record TimelineAiTestOutcome(
        String taskId,
        List<AiTimelineResultRequest.Event> events,
        boolean timedOut) {
}
