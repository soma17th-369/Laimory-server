package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * noop 구현 검증 — 항상 {@link GeoPlace#EMPTY}(두 필드 null)를 반환한다.
 * null(미연동) vs 빈 배열(조회했으나 없음)의 의미론 구분 회귀를 막는다.
 */
class NoOpMapPlaceProviderTest {

    @Test
    void lookup_returnsEmpty() {
        GeoPlace geo = new NoOpMapPlaceProvider().lookup(37.5340, 126.9668);

        assertThat(geo).isEqualTo(GeoPlace.EMPTY);
        assertThat(geo.address()).isNull();
        assertThat(geo.places()).isNull();
    }
}
