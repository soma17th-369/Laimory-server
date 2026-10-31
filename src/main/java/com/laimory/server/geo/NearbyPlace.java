package com.laimory.server.geo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 좌표 주변 장소 하나(#620) — 이름과 그 장소의 좌표. 좌표는 항상 있다: keyword 검색 장소는 카카오가 준 장소 좌표,
 * coord2address 건물명은 조회한 관측 좌표다.
 */
public record NearbyPlace(
        @Schema(description = "장소명", example = "모수 성수")
        String name,
        @Schema(description = "장소의 위도", example = "37.5447")
        double latitude,
        @Schema(description = "장소의 경도", example = "127.0561")
        double longitude
) {
}
