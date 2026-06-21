package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineItem;
import java.time.LocalDateTime;

/**
 * 타임라인 아이템 응답 DTO.
 *
 * <p>최상위 {@code itemType}이 타입의 권위다(엔티티 item_type 컬럼에서 온다).
 * {@code payload}는 타입 정보 없는 raw JSON({@link JsonNode})이라 그 안엔 itemType이 없다.
 */
public record TimelineItemResponse(
        Long timelineItemId,
        ItemType itemType,
        LocalDateTime startAt,
        LocalDateTime endAt,
        JsonNode payload
) {

    public static TimelineItemResponse from(TimelineItem item) {
        return new TimelineItemResponse(
                item.getTimelineItemId(),
                item.getItemType(),
                item.getStartAt(),
                item.getEndAt(),
                item.getPayload()
        );
    }
}
