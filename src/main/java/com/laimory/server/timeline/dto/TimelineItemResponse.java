package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.payload.TimelineItemPayload;
import com.laimory.server.timeline.entity.TimelineItem;
import java.time.LocalDateTime;

/**
 * 타임라인 아이템 응답 DTO.
 *
 * <p>최상위 {@code itemType}은 문서화된 API 응답 형태와 일치하도록 의도적으로 둔다.
 * payload도 JSON 직렬화 시 자체 itemType discriminator를 내보내지만, 둘은 서로 다른 JSON 위치라 충돌하지 않는다.
 */
public record TimelineItemResponse(
        Long id,
        ItemType itemType,
        LocalDateTime startAt,
        LocalDateTime endAt,
        TimelineItemPayload payload
) {

    public static TimelineItemResponse from(TimelineItem item) {
        return new TimelineItemResponse(
                item.getId(),
                item.itemType(),
                item.getStartAt(),
                item.getEndAt(),
                item.getPayload()
        );
    }
}
