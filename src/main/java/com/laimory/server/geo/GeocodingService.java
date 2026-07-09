package com.laimory.server.geo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 좌표 → 주소·주변 장소명 변환의 domain 진입점. transport(지도 API HTTP·재시도·응답 파싱)는 알지 못하고
 * {@link MapPlaceProvider}에 위임한다. repository 없는 leaf 서비스.
 *
 * <p>구현 선택은 {@code app.geo.mode}: {@code noop}(기본 — 미연동, 항상 빈 결과) / {@code kakao}.
 * 그 외 값이거나 kakao인데 키가 비면 기동 실패(fail-fast — 조용한 무동작·오타 방지). 키는 검증용으로만 읽고
 * 실제 사용은 {@link KakaoMapPlaceProvider}가 한다.
 *
 * <p><b>실패 전파</b>: kakao 모드에서 provider가 {@link MapPlaceLookupException}을 던지면(재시도는 이미
 * provider 내부에서 소진됨) 그대로 전파한다 — 조용히 빈 결과로 강등하지 않는다(저품질 타임라인 방지).
 * 상위 계층이 이 예외를 draft 생성 실패(502)로 매핑한다.
 */
@Service
public class GeocodingService {

    private static final String MODE_NOOP = "noop";
    private static final String MODE_KAKAO = "kakao";

    private final String mode;
    private final MapPlaceProvider mapPlaceProvider;

    public GeocodingService(
            @Value("${app.geo.mode}") String mode,
            @Value("${app.geo.kakao-rest-api-key}") String kakaoRestApiKey,
            MapPlaceProvider mapPlaceProvider) {
        if (!MODE_NOOP.equals(mode) && !MODE_KAKAO.equals(mode)) {
            throw new IllegalStateException("app.geo.mode must be noop or kakao: " + mode);
        }
        if (MODE_KAKAO.equals(mode) && (kakaoRestApiKey == null || kakaoRestApiKey.isBlank())) {
            throw new IllegalStateException("KAKAO_REST_API_KEY is required when app.geo.mode=kakao");
        }
        this.mode = mode;
        this.mapPlaceProvider = mapPlaceProvider;
    }

    /**
     * 좌표를 enrich 결과로 변환한다. noop 모드면 즉시 {@link GeoPlace#EMPTY}, 아니면 provider에 위임하고
     * {@link MapPlaceLookupException}은 그대로 전파한다.
     */
    public GeoPlace lookup(double latitude, double longitude) {
        if (!MODE_KAKAO.equals(mode)) {
            return GeoPlace.EMPTY;
        }
        return mapPlaceProvider.lookup(latitude, longitude);
    }
}
