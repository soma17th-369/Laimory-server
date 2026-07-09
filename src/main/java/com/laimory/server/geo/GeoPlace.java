package com.laimory.server.geo;

import java.util.List;

/**
 * 좌표 enrich 결과(주소·주변 장소명).
 *
 * <p>실패는 예외({@link MapPlaceLookupException})로 처리돼 null로 인코딩되지 않는다 → null의 의미가
 * "noop 미연동 또는 정상 조회했으나 정보 부재"로 좁아진다. {@code address} null = noop이거나 주소 부재.
 * {@code places}는 null=noop 미연동, 빈 배열=정상 조회했으나 주변 장소 없음으로 구분한다
 * (건물명은 별도 필드 없이 places에 합류).
 */
public record GeoPlace(String address, List<String> places) {

    public static final GeoPlace EMPTY = new GeoPlace(null, null);
}
