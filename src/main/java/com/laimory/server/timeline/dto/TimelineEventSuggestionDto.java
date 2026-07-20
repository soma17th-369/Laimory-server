package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.TimelineEventType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI staging 출력을 서버가 조립한 내부 타임라인 이벤트 제안.
 * {@code eventType}은 staging raw String을 assembler가 변환한 값이라 여기서부터는 항상 허용 literal이다.
 * {@code itemIds}는 staging association에서 조립한 source item PK 목록이다
 * (timeline_draft_source_item_id, 요청범위 인덱스나 AI callback 필드가 아님).
 */
public record TimelineEventSuggestionDto(
        TimelineEventType eventType,
        String title,
        String subtitle,
        LocalDateTime startAt,
        LocalDateTime endAt,
        List<Long> itemIds
) {
}
