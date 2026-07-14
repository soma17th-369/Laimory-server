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
import reactor.core.publisher.Mono;

/**
 * 지오코딩 domain 위임·blocking 경계 검증 — provider의 Mono를 block으로 흡수해 값/실패를 그대로 전달한다.
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
    void lookup_delegatesToProvider_andBlocksToValue() {
        GeoPlace expected = new GeoPlace("서울 용산구 청파로20길 95", List.of("서울드래곤시티"));
        when(mapPlaceProvider.lookup(LATITUDE, LONGITUDE)).thenReturn(Mono.just(expected));

        GeocodingService service = new GeocodingService(mapPlaceProvider);

        assertThat(service.lookup(LATITUDE, LONGITUDE)).isSameAs(expected);
        verify(mapPlaceProvider).lookup(LATITUDE, LONGITUDE);
    }

    @Test
    void lookup_propagatesOriginalLookupException_withoutDegrading() {
        // 재시도는 provider 내부에서 이미 소진됨 — block()이 error 신호의 RuntimeException을 원본 그대로
        // 재던진다(조용한 EMPTY 강등 없음, Reactor 래핑 없음 — retryable 분류가 502 코드 분기에 쓰인다).
        MapPlaceLookupException failure = new MapPlaceLookupException("coord2address http 500", true, null);
        when(mapPlaceProvider.lookup(LATITUDE, LONGITUDE)).thenReturn(Mono.error(failure));

        GeocodingService service = new GeocodingService(mapPlaceProvider);

        assertThatThrownBy(() -> service.lookup(LATITUDE, LONGITUDE)).isSameAs(failure);
    }
}
