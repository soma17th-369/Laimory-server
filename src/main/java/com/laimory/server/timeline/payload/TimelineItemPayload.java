package com.laimory.server.timeline.payload;

/**
 * 타입별 payload의 공통 sealed 인터페이스. DB에는 타입 정보 없는 JSON으로 저장하고,
 * 타입은 timeline_items.item_type 컬럼이 권위다(payload 밖). 구체 타입↔ItemType 매핑은 {@link ItemTypes}.
 */
public sealed interface TimelineItemPayload
        permits PhotoPayload, CalendarPayload, LocationPayload, MovementPayload {
}
