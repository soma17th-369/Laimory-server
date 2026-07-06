package com.laimory.server.geo;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 좌표 → 주소·주변 장소명 변환(카카오 로컬 API). repository 없는 leaf 서비스.
 *
 * <p>구현 선택은 {@code app.geo.mode}: {@code noop}(기본 — 미연동, 항상 빈 결과) / {@code kakao}.
 * 그 외 값이거나 kakao인데 키가 비면 기동 실패(fail-fast — 조용한 무동작·오타 방지).
 *
 * <p>카카오 요청 파라미터는 {@code x}=경도, {@code y}=위도로 순서가 뒤집힌다.
 * 타임아웃은 {@code spring.http.client.*} 프로퍼티가 담당한다 — 코드에서 requestFactory를
 * 지정하면 테스트의 {@code MockRestServiceServer.bindTo(builder)}와 충돌하므로 금지.
 *
 * <p>⚠️ 좌표는 위치 민감정보다 — 예외 메시지에 요청 URL(좌표 포함)이 실릴 수 있어
 * 로그엔 예외 클래스명만 남기고 좌표·응답 본문은 남기지 않는다.
 */
@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    private static final String MODE_NOOP = "noop";
    private static final String MODE_KAKAO = "kakao";
    private static final String KAKAO_BASE_URL = "https://dapi.kakao.com";
    /** 목적이 "주변 추천"이 아니라 동일·인접 건물 내 장소 보강이라 반경을 좁게 고정한다. */
    private static final int PLACES_RADIUS_METERS = 50;
    private static final int PLACES_MAX_COUNT = 10;
    /** 음식점/카페/문화시설/관광명소/숙박 — 건물 입주 장소로 유의미한 카테고리 그룹. */
    private static final List<String> PLACE_CATEGORY_GROUP_CODES = List.of("FD6", "CE7", "CT1", "AT4", "AD5");

    private final String mode;
    private final RestClient restClient;

    public GeocodingService(
            @Value("${app.geo.mode}") String mode,
            @Value("${app.geo.kakao-rest-api-key}") String kakaoRestApiKey,
            RestClient.Builder restClientBuilder) {
        if (!MODE_NOOP.equals(mode) && !MODE_KAKAO.equals(mode)) {
            throw new IllegalStateException("app.geo.mode must be noop or kakao: " + mode);
        }
        if (MODE_KAKAO.equals(mode) && (kakaoRestApiKey == null || kakaoRestApiKey.isBlank())) {
            throw new IllegalStateException("KAKAO_REST_API_KEY is required when app.geo.mode=kakao");
        }
        this.mode = mode;
        this.restClient = restClientBuilder
                .baseUrl(KAKAO_BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakaoRestApiKey)
                .build();
    }

    /**
     * 좌표를 enrich 결과로 변환한다. noop 모드면 즉시 {@link GeoPlace#EMPTY}.
     * 주소/장소 조회는 서로 독립적으로 실패를 강등한다(부분 실패 시 해당 필드만 null) —
     * 외부 API 장애가 draft 생성 흐름을 죽이지 않는다.
     *
     * <p>coord2address의 건물명은 별도 필드 없이 places 맨 앞에 합류한다(규격에 건물명 필드 없음) —
     * 카테고리 검색에 안 걸리는 건물(오피스 등)의 이름을 잃지 않기 위함.
     */
    public GeoPlace lookup(double latitude, double longitude) {
        if (!MODE_KAKAO.equals(mode)) {
            return GeoPlace.EMPTY;
        }
        KakaoAddress address = fetchAddress(latitude, longitude);
        List<String> places = fetchNearbyPlaceNames(latitude, longitude);
        return new GeoPlace(address.address(), mergeBuildingName(address.buildingName(), places));
    }

    private KakaoAddress fetchAddress(double latitude, double longitude) {
        try {
            JsonNode body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v2/local/geo/coord2address.json")
                            .queryParam("x", longitude)
                            .queryParam("y", latitude)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode road = body.path("documents").path(0).path("road_address");
            // 카카오는 부재 필드를 null이 아니라 빈 문자열로 주는 경우가 있다 → blank는 null로 정규화.
            return new KakaoAddress(
                    blankToNull(road.path("address_name").asText(null)),
                    blankToNull(road.path("building_name").asText(null)));
        } catch (RuntimeException e) {
            log.warn("kakao coord2address failed: {}", e.getClass().getSimpleName());
            return KakaoAddress.EMPTY;
        }
    }

    private List<String> fetchNearbyPlaceNames(double latitude, double longitude) {
        record Place(String id, String name, int distance) {
        }
        try {
            List<Place> found = new ArrayList<>();
            for (String categoryGroupCode : PLACE_CATEGORY_GROUP_CODES) {
                JsonNode body = restClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/v2/local/search/category.json")
                                .queryParam("category_group_code", categoryGroupCode)
                                .queryParam("x", longitude)
                                .queryParam("y", latitude)
                                .queryParam("radius", PLACES_RADIUS_METERS)
                                .queryParam("sort", "distance")
                                .build())
                        .retrieve()
                        .body(JsonNode.class);
                for (JsonNode document : body.path("documents")) {
                    String name = blankToNull(document.path("place_name").asText(null));
                    if (name == null) {
                        continue;
                    }
                    int distance;
                    try {
                        distance = Integer.parseInt(document.path("distance").asText(""));
                    } catch (NumberFormatException ex) {
                        continue; // distance 파싱 실패/blank인 장소는 거리순 병합이 불가능하므로 제외.
                    }
                    found.add(new Place(blankToNull(document.path("id").asText(null)), name, distance));
                }
            }
            // 카테고리별 응답은 각자 거리순일 뿐이라 전역 병합 정렬이 따로 필요하다. 중복은 place id 기준 제거.
            Set<String> seenIds = new HashSet<>();
            return found.stream()
                    .sorted(Comparator.comparingInt(Place::distance))
                    .filter(place -> place.id() == null || seenIds.add(place.id()))
                    .map(Place::name)
                    .limit(PLACES_MAX_COUNT)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("kakao category search failed: {}", e.getClass().getSimpleName());
            return null; // places=null = 조회 실패(빈 배열 "주변 없음"과 구분).
        }
    }

    /** 건물명은 좌표 위치 그 자체라 places 맨 앞에 둔다. 카테고리 조회가 실패해도 건물명만은 살린다. */
    private static List<String> mergeBuildingName(String buildingName, List<String> places) {
        if (buildingName == null) {
            return places;
        }
        if (places == null) {
            return List.of(buildingName);
        }
        if (places.contains(buildingName)) {
            return places;
        }
        List<String> merged = new ArrayList<>();
        merged.add(buildingName);
        merged.addAll(places);
        return merged.stream().limit(PLACES_MAX_COUNT).toList();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private record KakaoAddress(String address, String buildingName) {
        private static final KakaoAddress EMPTY = new KakaoAddress(null, null);
    }
}
