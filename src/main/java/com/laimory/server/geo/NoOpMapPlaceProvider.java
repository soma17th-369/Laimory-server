package com.laimory.server.geo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * no-op {@link MapPlaceProvider}(기본 구현) — 지오코딩 미연동 상태에서 항상 {@link GeoPlace#EMPTY}를 반환한다.
 *
 * <p>{@code matchIfMissing = true}라 {@code app.geo.mode} 미설정 환경(로컬·CI 통합테스트 등)에서 항상 이 구현이
 * 선택된다. 이 빈이 없으면 noop 모드 컨텍스트에서 {@link GeocodingService}가 주입받을 {@link MapPlaceProvider}가
 * 없어 기동하지 못한다.
 *
 * <p>{@link GeoPlace#EMPTY}는 두 필드가 모두 null이다 — {@code places}의 null="미연동"과 빈 배열="조회했으나
 * 주변 장소 없음"의 의미론 구분을 보존한다({@link GeoPlace} 계약).
 */
@Component
@ConditionalOnProperty(name = "app.geo.mode", havingValue = "noop", matchIfMissing = true)
class NoOpMapPlaceProvider implements MapPlaceProvider {

    @Override
    public Mono<GeoPlace> lookup(double latitude, double longitude) {
        return Mono.just(GeoPlace.EMPTY);
    }
}
