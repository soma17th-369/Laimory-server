package com.laimory.server.geo;

import java.util.List;

/**
 * 좌표 enrich 결과(주소·주변 장소명).
 *
 * <p>{@code address} null = 미시도/실패/해당 정보 부재. {@code places}는 null=조회 미시도/실패,
 * 빈 배열=정상 조회했으나 주변 장소 없음으로 구분한다(건물명은 별도 필드 없이 places에 합류).
 */
public record GeoPlace(String address, List<String> places) {

    public static final GeoPlace EMPTY = new GeoPlace(null, null);
}
