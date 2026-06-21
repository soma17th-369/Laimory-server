package com.laimory.server.timeline.payload;

/**
 * 타입별 payload의 공통 sealed 인터페이스. DB에는 타입 정보 없는 JSON으로 저장하고,
 * 타입은 timeline_items.item_type 컬럼이 권위다(payload 밖). item_type 값은 클라이언트가 보낸 itemType 디스크리미네이터에서 온다.
 */
public sealed interface TimelineItemPayload
        permits PhotoPayload, CalendarPayload, LocationPayload, MovementPayload {
}
