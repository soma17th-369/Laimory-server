package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 이동 아이템 payload. 출발/도착은 {@link MovementEndpoint} 중첩 객체로 받는다(좌표 필수 — 지오코딩 enrich 전제).
 *
 * <p>{@code transports}는 이동수단 분류 값(단일 문자열, 예: {@code IN_VEHICLE}, {@code WALKING}).
 * {@code durationSec} 등 startAt/endAt에서 파생 가능한 값은 받지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MovementPayload(
        MovementEndpoint start,
        MovementEndpoint end,
        String transports,
        Double distanceMeters
) implements TimelineItemPayload {
}
