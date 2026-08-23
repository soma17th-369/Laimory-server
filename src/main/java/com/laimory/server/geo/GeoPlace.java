package com.laimory.server.geo;

import java.util.List;

/**
 * 좌표 enrich 결과(주소·주변 장소명).
 *
 * <p>provider 조회 실패는 예외({@link MapPlaceLookupException})로 전달돼 여기 인코딩되지 않는다.
 * {@code address} null = noop이거나 주소 부재이거나 <b>허용된 실패 좌표의 fallback</b>.
 * {@code places}는 null=noop 미연동, 빈 배열=정상 조회했으나 주변 장소 없음 또는 허용 실패 fallback이다
 * (건물명은 별도 필드 없이 places에 합류). 허용 실패 fallback({@code (null, [])})은 정상 "주소 없음"과
 * 같은 값이며 내부 구분은 {@link GeoLookupOutcome}·metric이 담당한다(wire 실패 marker 없음).
 */
public record GeoPlace(String address, List<String> places) {

    public static final GeoPlace EMPTY = new GeoPlace(null, null);
}
