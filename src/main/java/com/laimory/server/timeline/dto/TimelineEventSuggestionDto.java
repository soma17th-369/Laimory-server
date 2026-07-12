package com.laimory.server.timeline.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI staging 출력을 서버가 조립한 내부 타임라인 이벤트 제안.
 * {@code itemIds}는 staging association에서 조립한 source item PK 목록이다
 * (timeline_draft_source_item_id, 요청범위 인덱스나 AI callback 필드가 아님).
 */
public record TimelineEventSuggestionDto(
        String title,
        String subtitle,
        LocalDateTime startAt,
        LocalDateTime endAt,
        List<Long> itemIds
) {
}
