package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 이동 아이템 payload. 출발/도착은 {@link MovementEndpoint} 중첩 객체로 받는다(좌표 필수 — 지오코딩 enrich 전제).
 *
 * <p>{@code transports}는 이동수단 분류 값(단일 문자열, 예: {@code IN_VEHICLE}, {@code WALKING}).
 * {@code durationSec} 등 startAt/endAt에서 파생 가능한 값은 받지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MovementPayload(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "출발 지점(좌표 필수).")
        MovementEndpoint start,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "도착 지점(좌표 필수).")
        MovementEndpoint end,
        @Schema(example = "WALKING",
                description = "이동수단 분류(단일 문자열). 서버가 값을 제한하진 않는다 — 예: IN_VEHICLE, WALKING.")
        String transports,
        @Schema(example = "1200.0", description = "이동 거리(미터 단위). 선택 — 있으면 0 이상 유한값이어야 한다.")
        Double distanceMeters
) implements TimelineItemPayload {
}
