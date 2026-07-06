package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 이동 아이템 payload. 출발/도착은 {@link MovementEndpoint} 중첩 객체로 받는다(좌표 필수 — 지오코딩 enrich 전제).
 *
 * <p>{@code transports}는 이동수단 배열(개행 연결 문자열 금지). 저장 전 정규화된다 —
 * 원소 trim·blank 제거 후 비면 null(키 생략). {@code durationSec} 등 startAt/endAt에서
 * 파생 가능한 값은 받지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MovementPayload(
        MovementEndpoint from,
        MovementEndpoint to,
        List<String> transports,
        String lineName,
        Double distanceMeters
) implements TimelineItemPayload {
}
