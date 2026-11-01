package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 타임라인 Event 장소 지정(PUT) 요청(#612). Event {@code place}를 교체하고, 좌표를 함께 보내면 좌표도 교체한다(#616)
 * — title·subtitle·주소·장소 후보는 그대로다. 장소 후보 중 하나인지 검사하지 않는다(직접 입력 허용).
 *
 * <p>{@code place}는 필수다. 누락·null·공백뿐이면 400이고, 앞뒤 공백을 제거한 뒤 최대 255자다(초과 시 400).
 * 좌표는 선택이며 쌍이다 — 둘 다 누락·null이면 기존 좌표 유지, 한쪽만이거나 범위 밖이면 400.
 */
public record UpdateTimelineEventPlaceRequest(
        @Schema(example = "△△공원", description = "지정할 장소명. 필수, 앞뒤 공백 제거 후 최대 255자.")
        String place,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, example = "37.5445",
                description = "지정한 장소의 위도(-90~90). longitude와 둘 다 보내면 좌표를 교체하고, 둘 다 "
                        + "누락·null이면 유지한다. 한쪽만이거나 범위 밖이면 400.")
        Double latitude,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, example = "127.0559",
                description = "지정한 장소의 경도(-180~180). latitude와 쌍이다.")
        Double longitude
) {
}
