package com.laimory.server.geo;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * 카카오 로컬 API {@link MapPlaceProvider} 구현 — 좌표당 6콜(coord2address 1 + 카테고리 5)로 주소·주변 장소를 조회한다.
 *
 * <p>카카오 요청 파라미터는 {@code x}=경도, {@code y}=위도로 순서가 뒤집힌다.
 * 타임아웃은 {@code spring.http.client.*} 프로퍼티가 담당한다 — 코드에서 requestFactory를 지정하면
 * 테스트의 {@code MockRestServiceServer.bindTo(builder)}와 충돌하므로 금지.
 *
 * <p><b>실패 처리(strict)</b>: 6콜 중 하나라도 최종 실패하면 {@link MapPlaceLookupException}을 던진다
 * (조용한 null degrade 없음 — 저품질 타임라인을 굽지 않는다). 재시도는 <b>단일 HTTP 콜 단위</b>로 건다 —
 * lookup 전체에 걸면 늦은 콜의 실패가 성공한 앞 콜까지 재실행해 좌표당 최대 2×6=12콜이 되므로.
 * 전이적 실패(5xx·타임아웃)는 단일 콜을 최대 {@link #MAX_ATTEMPTS}회 시도하고, 영구적 실패(429·401·403·4xx·파싱)는 즉시 던진다.
 *
 * <p>외부 호출·응답 해석 실패는 <b>전부</b> {@link MapPlaceLookupException}으로 감싼다 — HTTP 에러뿐 아니라
 * JSON 파싱 실패·null body·예상 밖 응답 shape(4xx 포함)까지. 안 그러면 raw RuntimeException이 새서 catch-all 500이 된다.
 *
 * <p>{@code app.geo.mode=kakao}일 때만 빈으로 등록된다({@code @ConditionalOnProperty}). {@code noop}이거나
 * 미설정이면 {@link NoOpMapPlaceProvider}가 대신 선택되고, 그 외 값(오타)이면 어느 provider도 매칭되지 않아
 * 컨텍스트가 기동 실패한다(암시적 fail-fast). 이 클래스가 kakao 모드에서만 생성되므로 생성자에서 키를 자기검증한다
 * (키가 비면 기동 실패, fail-fast).
 *
 * <p>⚠️ 좌표는 위치 민감정보다 — 로그엔 endpoint 종류·status·retryable·attempts만 남기고 좌표·요청 URL·응답 본문은 금지한다.
 */
@Component
@ConditionalOnProperty(name = "app.geo.mode", havingValue = "kakao")
public class KakaoMapPlaceProvider implements MapPlaceProvider {

    private static final Logger log = LoggerFactory.getLogger(KakaoMapPlaceProvider.class);

    private static final String KAKAO_BASE_URL = "https://dapi.kakao.com";
    /** 목적이 "주변 추천"이 아니라 동일·인접 건물 내 장소 보강이라 반경을 좁게 고정한다. */
    private static final int PLACES_RADIUS_METERS = 50;
    private static final int PLACES_MAX_COUNT = 10;
    /** 음식점/카페/문화시설/관광명소/숙박 — 건물 입주 장소로 유의미한 카테고리 그룹. */
    private static final List<String> PLACE_CATEGORY_GROUP_CODES = List.of("FD6", "CE7", "CT1", "AT4", "AD5");

    private static final String ENDPOINT_COORD2ADDRESS = "coord2address";
    private static final String ENDPOINT_CATEGORY = "category";

    /** 전이적 실패 시 단일 콜 최대 시도 횟수(최초 1 + 재시도 1). 영구적 실패는 재시도하지 않는다. */
    private static final int MAX_ATTEMPTS = 2;
    private static final long BACKOFF_MILLIS = 200;

    private final RestClient restClient;

    public KakaoMapPlaceProvider(
            @Value("${app.geo.kakao-rest-api-key}") String kakaoRestApiKey,
            RestClient.Builder restClientBuilder) {
        if (kakaoRestApiKey == null || kakaoRestApiKey.isBlank()) {
            throw new IllegalStateException("KAKAO_REST_API_KEY is required when app.geo.mode=kakao");
        }
        this.restClient = restClientBuilder
                .baseUrl(KAKAO_BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakaoRestApiKey)
                .build();
    }

    /**
     * coord2address의 건물명은 별도 필드 없이 places 맨 앞에 합류한다(규격에 건물명 필드 없음) —
     * 카테고리 검색에 안 걸리는 건물(오피스 등)의 이름을 잃지 않기 위함.
     */
    @Override
    public GeoPlace lookup(double latitude, double longitude) {
        long startNanos = System.nanoTime();
        int[] calls = {0};
        KakaoAddress address = fetchAddress(latitude, longitude, calls);
        List<String> places = fetchNearbyPlaceNames(latitude, longitude, calls);
        // 좌표값은 로그 금지(위치 민감정보) — 좌표당 실제 HTTP 호출 수(재시도 포함)·소요시간만(tx는 MDC 자동).
        log.info("kakao geocoding lookup: calls={} tookMs={}",
                calls[0], (System.nanoTime() - startNanos) / 1_000_000);
        return new GeoPlace(address.address(), mergeBuildingName(address.buildingName(), places));
    }

    private KakaoAddress fetchAddress(double latitude, double longitude, int[] calls) {
        JsonNode documents = fetchDocuments(ENDPOINT_COORD2ADDRESS, calls,
                uriBuilder -> uriBuilder.path("/v2/local/geo/coord2address.json")
                        .queryParam("x", longitude)
                        .queryParam("y", latitude)
                        .build());
        // 정상 빈 결과({"documents":[]})면 document는 MissingNode → 모든 필드 null(주소 부재, 실패 아님).
        JsonNode document = documents.path(0);
        JsonNode road = document.path("road_address");
        // 카카오는 부재 필드를 null이 아니라 빈 문자열로 주는 경우가 있다 → blank는 null로 정규화.
        String roadAddress = blankToNull(road.path("address_name").asText(null));
        // 도로명주소는 건물에만 부여돼 도로 위·공터 좌표엔 없다 → 지번 주소(address)로 fallback.
        String lotAddress = blankToNull(document.path("address").path("address_name").asText(null));
        return new KakaoAddress(
                roadAddress != null ? roadAddress : lotAddress,
                blankToNull(road.path("building_name").asText(null)));
    }

    private List<String> fetchNearbyPlaceNames(double latitude, double longitude, int[] calls) {
        record Place(String id, String name, int distance) {
        }
        List<Place> found = new ArrayList<>();
        for (String categoryGroupCode : PLACE_CATEGORY_GROUP_CODES) {
            JsonNode documents = fetchDocuments(ENDPOINT_CATEGORY, calls,
                    uriBuilder -> uriBuilder.path("/v2/local/search/category.json")
                            .queryParam("category_group_code", categoryGroupCode)
                            .queryParam("x", longitude)
                            .queryParam("y", latitude)
                            .queryParam("radius", PLACES_RADIUS_METERS)
                            .queryParam("sort", "distance")
                            .build());
            for (JsonNode document : documents) {
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
    }

    /**
     * 단일 카카오 HTTP 콜 + 응답 shape 검증을 재시도로 감싼다. 전이적 실패(5xx·타임아웃)는 최대 {@link #MAX_ATTEMPTS}회
     * 시도하고, 영구적 실패(4xx·파싱·shape)는 즉시 던진다. 외부 호출·해석 실패는 전부 {@link MapPlaceLookupException}으로 감싼다.
     */
    private JsonNode fetchDocuments(String endpoint, int[] calls, Function<UriBuilder, URI> uriFunction) {
        int attempt = 0;
        while (true) {
            attempt++;
            calls[0]++;
            boolean retryable;
            String statusText;
            MapPlaceLookupException failure;
            try {
                JsonNode body = restClient.get().uri(uriFunction).retrieve().body(JsonNode.class);
                return requireDocuments(body, endpoint);
            } catch (MapPlaceLookupException e) {
                // requireDocuments가 던진 shape/null-body 오류 — 영구적.
                retryable = false;
                statusText = "malformed";
                failure = e;
            } catch (RestClientResponseException e) {
                // HTTP status를 받은 4xx/5xx. 5xx만 전이적으로 본다(429·401·403·기타 4xx는 영구).
                retryable = e.getStatusCode().is5xxServerError();
                statusText = String.valueOf(e.getStatusCode().value());
                failure = new MapPlaceLookupException(endpoint + " http " + statusText, retryable, e);
            } catch (ResourceAccessException e) {
                // I/O(연결·읽기 타임아웃 등) — 전이적.
                retryable = true;
                statusText = "io";
                failure = new MapPlaceLookupException(endpoint + " io error", true, e);
            } catch (RestClientException e) {
                // 응답 디코딩/파싱 실패(HTTP status 없음) — 영구적.
                retryable = false;
                statusText = "parse";
                failure = new MapPlaceLookupException(endpoint + " parse error", false, e);
            }
            if (retryable && attempt < MAX_ATTEMPTS) {
                backoff();
                continue;
            }
            // 좌표·URL·응답 body 금지 — endpoint 종류·status·retryable·attempts만.
            log.warn("kakao geocoding call failed: endpoint={} status={} retryable={} attempts={}",
                    endpoint, statusText, retryable, attempt);
            throw failure;
        }
    }

    /**
     * 응답에서 {@code documents} 배열을 꺼낸다. {@code {"documents":[]}}(정상 빈 결과)는 통과시키되,
     * null body·{@code documents} 필드 누락·배열 아님은 깨진 응답으로 보고 영구적 {@link MapPlaceLookupException}을 던진다.
     */
    private static JsonNode requireDocuments(JsonNode body, String endpoint) {
        if (body == null) {
            throw new MapPlaceLookupException(endpoint + " null body", false, null);
        }
        JsonNode documents = body.get("documents");
        if (documents == null || !documents.isArray()) {
            throw new MapPlaceLookupException(endpoint + " missing documents array", false, null);
        }
        return documents;
    }

    private static void backoff() {
        try {
            Thread.sleep(BACKOFF_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MapPlaceLookupException("geocoding retry interrupted", false, e);
        }
    }

    /** 건물명은 좌표 위치 그 자체라 places 맨 앞에 둔다. */
    private static List<String> mergeBuildingName(String buildingName, List<String> places) {
        if (buildingName == null) {
            return places;
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
    }
}
