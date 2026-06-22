package com.laimory.server.timeline.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 타임라인 이벤트 제안(AI가 source items를 보고 반환하는 이벤트 초안).
 * {@code itemIds}는 이 이벤트에 포함하겠다고 AI가 반환한 request item id 목록이다(요청범위 인덱스, DB id 아님).
 */
public record TimelineEventSuggestionDto(
        String title,
        String subtitle,
        LocalDateTime startAt,
        LocalDateTime endAt,
        List<Integer> itemIds
) {
}
