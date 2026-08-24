package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.TimelineItemPayload;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 타임라인 아이템 응답 DTO.
 *
 * <p>최상위 {@code itemType}이 타입의 권위다(엔티티 item_type 컬럼에서 온다).
 * {@code payload}는 타입 정보 없는 raw JSON({@link JsonNode})으로, 저장본을 그대로 통과시킨다 —
 * PHOTO의 {@code photoUrl}도 draft enrich 또는 수동 PHOTO 저장 시 주입된 값이 payload 안에 이미 있어
 * 읽기 시점 변환이 없다.
 *
 * <p>런타임 타입은 {@link JsonNode}지만, 문서에는 {@code implementation}으로 {@link TimelineItemPayload}(6종 oneOf)
 * 스키마를 노출한다 — 응답이므로 서버 파생 read-only 필드도 함께 보인다.
 */
public record TimelineItemResponse(
        Long timelineItemId,
        ItemType itemType,
        String rawId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        @Schema(implementation = TimelineItemPayload.class)
        JsonNode payload
) {

    public static TimelineItemResponse from(TimelineItem item) {
        return new TimelineItemResponse(
                item.getTimelineItemId(),
                item.getItemType(),
                item.getRawId(),
                item.getStartAt(),
                item.getEndAt(),
                item.getPayload());
    }
}
