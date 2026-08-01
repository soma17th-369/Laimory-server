package com.laimory.server.geo;

/**
 * unique 좌표 하나의 materialize된 최종 조회 outcome — 성공(실제 {@link GeoPlace}) 또는 provider retry
 * 소진 뒤의 실패. {@link GeocodingService#lookupAll}이 예상된 실패({@link MapPlaceLookupException})를
 * error 신호 대신 이 값으로 바꿔 나머지 좌표 조회를 계속한다(D4). programming error는 materialize하지
 * 않고 그대로 전파된다.
 */
public sealed interface GeoLookupOutcome {

    record Success(GeoPlace place) implements GeoLookupOutcome {
    }

    record Failure(MapPlaceLookupException failure) implements GeoLookupOutcome {
    }
}
