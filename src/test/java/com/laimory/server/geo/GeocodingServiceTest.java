package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 지오코딩 domain 위임 검증 — provider에 그대로 위임하고 실패를 강등 없이 전파한다.
 * 어떤 provider가 배선되는지(mode 스위치·noop/kakao 선택·오타 fail-fast)는 {@link GeoWiringTest}가,
 * 실 카카오 계약은 {@link KakaoMapPlaceProviderTest}가, noop 동작은 {@link NoOpMapPlaceProviderTest}가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class GeocodingServiceTest {

    private static final double LATITUDE = 37.5340;
    private static final double LONGITUDE = 126.9668;

    @Mock
    private MapPlaceProvider mapPlaceProvider;

    @Test
    void lookup_delegatesToProvider() {
        GeoPlace expected = new GeoPlace("서울 용산구 청파로20길 95", List.of("서울드래곤시티"));
        when(mapPlaceProvider.lookup(LATITUDE, LONGITUDE)).thenReturn(expected);

        GeocodingService service = new GeocodingService(mapPlaceProvider);

        assertThat(service.lookup(LATITUDE, LONGITUDE)).isSameAs(expected);
        verify(mapPlaceProvider).lookup(LATITUDE, LONGITUDE);
    }

    @Test
    void lookup_propagatesLookupException_withoutDegrading() {
        // 재시도는 provider 내부에서 이미 소진됨 — domain은 그대로 전파한다(조용한 EMPTY 강등 없음).
        when(mapPlaceProvider.lookup(LATITUDE, LONGITUDE))
                .thenThrow(new MapPlaceLookupException("coord2address http 500", true, null));

        GeocodingService service = new GeocodingService(mapPlaceProvider);

        assertThatThrownBy(() -> service.lookup(LATITUDE, LONGITUDE))
                .isInstanceOf(MapPlaceLookupException.class);
    }
}
