package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import java.util.List;

/**
 * AI 동기 테스트 응답의 내부 표현 — 검증을 통과한 Event 목록과 {@code X-Timeline-Timed-Out} 여부다.
 * wire DTO가 아니라 client → service 전달용이라 {@code dto} 패키지에 두지 않는다.
 */
record TimelineAiTestAiResult(List<AiTimelineResultRequest.Event> events, boolean timedOut) {
}
