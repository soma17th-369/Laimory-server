package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 타임라인 Event 장소 지정(PUT) 요청(#612). Event {@code place}만 교체한다 — title·subtitle·좌표·주소·장소 후보는
 * 그대로다. 장소 후보 중 하나인지 검사하지 않는다(직접 입력 허용).
 *
 * <p>{@code place}는 필수다. 누락·null·공백뿐이면 400이고, 앞뒤 공백을 제거한 뒤 최대 255자다(초과 시 400).
 */
public record UpdateTimelineEventPlaceRequest(
        @Schema(example = "△△공원", description = "지정할 장소명. 필수, 앞뒤 공백 제거 후 최대 255자.")
        String place
) {
}
