package com.laimory.server.geo;

import org.springframework.stereotype.Service;

/**
 * 좌표 → 주소·주변 장소명 변환의 domain 진입점. transport(지도 API HTTP·재시도·응답 파싱)와 어떤 provider를
 * 쓰는지는 알지 못하고 {@link MapPlaceProvider}에 위임한다. repository 없는 leaf 서비스.
 *
 * <p>구현 선택(noop/kakao)은 {@code app.geo.mode}로 각 {@link MapPlaceProvider} 빈이
 * {@code @ConditionalOnProperty}로 자체 배선한다 — domain은 이 스위치를 알지 못한다. mode 오타로 매칭되는
 * provider 빈이 없으면 이 서비스가 주입받을 빈이 없어 기동 실패한다(암시적 fail-fast).
 *
 * <p><b>실패 전파</b>: provider가 {@link MapPlaceLookupException}을 던지면(재시도는 이미 provider 내부에서
 * 소진됨) 그대로 전파한다 — 조용히 빈 결과로 강등하지 않는다(저품질 타임라인 방지). 상위 계층이 이 예외를
 * draft 생성 실패(502)로 매핑한다.
 */
@Service
public class GeocodingService {

    private final MapPlaceProvider mapPlaceProvider;

    public GeocodingService(MapPlaceProvider mapPlaceProvider) {
        this.mapPlaceProvider = mapPlaceProvider;
    }

    /**
     * 좌표를 enrich 결과로 변환한다. 미연동(noop) 여부는 provider가 결정하고
     * ({@link MapPlaceProvider} 구현), {@link MapPlaceLookupException}은 그대로 전파한다.
     */
    public GeoPlace lookup(double latitude, double longitude) {
        return mapPlaceProvider.lookup(latitude, longitude);
    }
}
