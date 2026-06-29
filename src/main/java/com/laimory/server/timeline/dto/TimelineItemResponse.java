package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.timeline.ItemType;
import java.time.LocalDateTime;

/**
 * 타임라인 아이템 응답 DTO.
 *
 * <p>최상위 {@code itemType}이 타입의 권위다(엔티티 item_type 컬럼에서 온다).
 * {@code payload}는 타입 정보 없는 raw JSON({@link JsonNode})이라 그 안엔 itemType이 없다.
 *
 * <p>엔티티→응답 변환은 {@code TimelineItemResponseMapper}가 담당한다(PHOTO는 filename→photoUrl 구성이
 * 필요해 사용자 id·서비스가 있어야 하므로, 정적 {@code from}이 아니라 빈 매퍼를 쓴다).
 */
public record TimelineItemResponse(
        Long timelineItemId,
        ItemType itemType,
        LocalDateTime startAt,
        LocalDateTime endAt,
        JsonNode payload
) {
}
