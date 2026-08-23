package com.laimory.server.geo;

/**
 * 지오코딩 조회 키(위도·경도). enrich의 좌표 dedupe와 {@link GeocodingService#lookupAll} 입력에 쓰인다 —
 * record 동등성이 "같은 좌표는 요청 내 1회만 조회" 계약의 기반이다.
 */
public record Coordinate(double latitude, double longitude) {
}
