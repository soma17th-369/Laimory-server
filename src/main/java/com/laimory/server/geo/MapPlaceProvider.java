package com.laimory.server.geo;

import reactor.core.publisher.Mono;

/**
 * 좌표 → 주소·주변 장소명 조회의 transport 경계. 지도 API(카카오·구글 등) 하나를 감싼다.
 *
 * <p>{@link GeocodingService}(domain)는 이 인터페이스에만 의존해 특정 provider의 HTTP·인증·응답 shape를
 * 알지 못한다. provider는 base URL·인증 헤더·좌표 파라미터 순서·재시도·응답 파싱·예외 분류를 전담한다.
 *
 * <p>반환은 {@link Mono} — 좌표 간 병렬 조회(fan-out)를 위해 transport 계층만 reactive이고,
 * blocking 경계는 {@link GeocodingService}가 전담한다(도메인 밖으로 Reactor가 새지 않는다).
 *
 * <p><b>실패 계약</b>: 외부 호출이나 응답 해석에 실패하면 {@link MapPlaceLookupException}을 <b>Mono error
 * 신호</b>로 전달한다(HTTP 에러·타임아웃뿐 아니라 JSON 파싱 실패·null body·예상 밖 응답 shape 포함).
 * 호출은 성공했으나 데이터가 없는 경우(주소 미부여·주변 POI 없음)는 <b>실패가 아니다</b> — null/빈 필드의
 * {@link GeoPlace}를 방출한다.
 */
public interface MapPlaceProvider {

    /**
     * 좌표를 주소·주변 장소명으로 조회한다. 실패는 {@link MapPlaceLookupException}을 error 신호로 전달한다
     * (재시도는 provider 내부에서 소진).
     */
    Mono<GeoPlace> lookup(double latitude, double longitude);
}
