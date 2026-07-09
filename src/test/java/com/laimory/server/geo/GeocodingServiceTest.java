package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 지오코딩 domain 게이트 검증 — mode fail-fast·noop 단락·provider 위임·실패 전파.
 * transport(카카오 HTTP·재시도·파싱)는 {@link MapPlaceProvider}가 소유하므로 여기선 mock으로 대체한다
 * (실 카카오 계약은 {@link KakaoMapPlaceProviderTest}가 검증).
 */
@ExtendWith(MockitoExtension.class)
class GeocodingServiceTest {

    private static final double LATITUDE = 37.5340;
    private static final double LONGITUDE = 126.9668;

    @Mock
    private MapPlaceProvider mapPlaceProvider;

    @Test
    void rejectsUnknownMode_atConstruction() {
        // noop|kakao 외 값은 오타로 조용히 noop이 되는 것을 막기 위해 기동 실패.
        assertThatThrownBy(() -> new GeocodingService("kakaoo", "key", mapPlaceProvider))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsKakaoModeWithoutKey_atConstruction() {
        assertThatThrownBy(() -> new GeocodingService("kakao", " ", mapPlaceProvider))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noopMode_returnsEmpty_withoutCallingProvider() {
        GeocodingService noop = new GeocodingService("noop", "", mapPlaceProvider);

        assertThat(noop.lookup(LATITUDE, LONGITUDE)).isEqualTo(GeoPlace.EMPTY);
        verifyNoInteractions(mapPlaceProvider);
    }

    @Test
    void kakaoMode_delegatesToProvider() {
        GeoPlace expected = new GeoPlace("서울 용산구 청파로20길 95", List.of("서울드래곤시티"));
        when(mapPlaceProvider.lookup(LATITUDE, LONGITUDE)).thenReturn(expected);

        GeocodingService kakao = new GeocodingService("kakao", "test-key", mapPlaceProvider);

        assertThat(kakao.lookup(LATITUDE, LONGITUDE)).isSameAs(expected);
        verify(mapPlaceProvider).lookup(LATITUDE, LONGITUDE);
    }

    @Test
    void kakaoMode_propagatesLookupException_withoutDegrading() {
        // 재시도는 provider 내부에서 이미 소진됨 — domain은 그대로 전파한다(조용한 EMPTY 강등 없음).
        when(mapPlaceProvider.lookup(LATITUDE, LONGITUDE))
                .thenThrow(new MapPlaceLookupException("coord2address http 500", true, null));

        GeocodingService kakao = new GeocodingService("kakao", "test-key", mapPlaceProvider);

        assertThatThrownBy(() -> kakao.lookup(LATITUDE, LONGITUDE))
                .isInstanceOf(MapPlaceLookupException.class);
    }
}
