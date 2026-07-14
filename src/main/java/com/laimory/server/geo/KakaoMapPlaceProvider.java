package com.laimory.server.geo;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.codec.CodecException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.util.retry.Retry;

/**
 * 카카오 로컬 API {@link MapPlaceProvider} 구현 — 좌표당 정상 2콜(coord2address 1 + 주소 keyword 검색 1,
 * 전이 재시도 포함 시 최대 4회 요청)로 주소·주변 장소를 조회한다. 주소가 없는 좌표(도로 위·공터 등)는
 * keyword 질의어가 없으므로 1콜로 끝난다.
 *
 * <p>장소 검색은 좌표 반경 category 검색이 아니라 <b>주소를 질의어로 한 keyword 검색</b>이다 — 주소 keyword와
 * 좌표 반경({@code radius})에 매칭된 장소를 카테고리 제한 없이 가져온다. "같은 주소(건물)의 입주 장소"는
 * 카카오가 보장하는 계약이 아니라 실측 관찰 기반 휴리스틱이다(공식 문서는 질의어 매칭·반경 제한까지만 설명).
 * 카카오는 무필터 좌표 반경 검색을 제공하지 않고 category 그룹 코드 순회는 코드 밖 업종을 놓치므로 이 방식을
 * 쓰며, 광역 지번 질의에서 원거리 POI(테마거리 등)가 섞이는 것은 {@code radius}가 걸러낸다.
 *
 * <p>카카오 요청 파라미터는 {@code x}=경도, {@code y}=위도로 순서가 뒤집힌다.
 * transport는 reactive({@link WebClient})다 — 좌표 간 병렬 조회를 위해 {@link Mono}를 반환하고, blocking
 * 경계는 {@link GeocodingService}가 전담한다. 타임아웃은 {@code spring.http.reactiveclient.*} 프로퍼티가
 * SSOT다 — 코드에서 {@code ClientHttpConnector}를 직접 만들지 않는다. base URL은
 * {@code app.geo.kakao-base-url}로 주입받는다(운영 endpoint 변경용이 아니라 MockWebServer용 test seam).
 *
 * <p><b>실패 처리(strict)</b>: 2콜 중 하나라도 최종 실패하면 {@link MapPlaceLookupException}을 error 신호로
 * 전달한다(조용한 null degrade 없음 — 저품질 타임라인을 굽지 않는다). 재시도는 <b>단일 HTTP 콜 단위</b>로 건다 —
 * lookup 전체에 걸면 늦은 콜(keyword)의 실패가 성공한 앞 콜(coord2address)까지 재실행하므로.
 * 전이적 실패(5xx·타임아웃)는 단일 콜을 최대 {@link #MAX_ATTEMPTS}회 시도하고, 영구적 실패(429·401·403·4xx·파싱)는 즉시 던진다.
 *
 * <p>외부 호출·응답 해석 실패는 <b>전부</b> {@link MapPlaceLookupException}으로 감싼다 — HTTP 에러뿐 아니라
 * JSON 파싱 실패·빈 body·예상 밖 응답 shape(4xx 포함)까지. 안 그러면 raw RuntimeException이 새서 catch-all 500이 된다.
 *
 * <p>{@code app.geo.mode=kakao}일 때만 빈으로 등록된다({@code @ConditionalOnProperty}). {@code noop}이거나
 * 미설정이면 {@link NoOpMapPlaceProvider}가 대신 선택되고, 그 외 값(오타)이면 어느 provider도 매칭되지 않아
 * 컨텍스트가 기동 실패한다(암시적 fail-fast). 이 클래스가 kakao 모드에서만 생성되므로 생성자에서 키를 자기검증한다
 * (키가 비면 기동 실패, fail-fast).
 *
 * <p>⚠️ 좌표는 위치 민감정보다 — 로그엔 endpoint 종류·retryable·시도 횟수·분류 사유만 남기고 좌표·요청 URL·응답
 * 본문은 금지한다. signal 로그는 이벤트루프 스레드에서 실행되므로 tx는 {@link TxContextLogging}으로 복원한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.geo.mode", havingValue = "kakao")
public class KakaoMapPlaceProvider implements MapPlaceProvider {

    /**
     * 목적이 "주변 추천"이 아니라 동일 건물 내 장소 보강이라 반경을 좁게 고정한다.
     * 광역 지번 질의에서 원거리 POI(테마거리 등)가 섞이는 것을 걸러내는 안전핀이기도 하다.
     */
    private static final int PLACES_RADIUS_METERS = 50;
    private static final int PLACES_MAX_COUNT = 10;

    private static final String ENDPOINT_COORD2ADDRESS = "coord2address";
    private static final String ENDPOINT_KEYWORD = "keyword";

    /** 전이적 실패 시 단일 콜 최대 시도 횟수(최초 1 + 재시도 1). 영구적 실패는 재시도하지 않는다. */
    private static final int MAX_ATTEMPTS = 2;
    private static final Duration BACKOFF = Duration.ofMillis(200);

    private final WebClient webClient;

    public KakaoMapPlaceProvider(
            @Value("${app.geo.kakao-rest-api-key}") String kakaoRestApiKey,
            @Value("${app.geo.kakao-base-url:https://dapi.kakao.com}") String kakaoBaseUrl,
            WebClient.Builder webClientBuilder) {
        if (kakaoRestApiKey == null || kakaoRestApiKey.isBlank()) {
            throw new IllegalStateException("KAKAO_REST_API_KEY is required when app.geo.mode=kakao");
        }
        this.webClient = webClientBuilder
                .baseUrl(kakaoBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakaoRestApiKey)
                .build();
    }

    /**
     * coord2address의 건물명은 별도 필드 없이 places 맨 앞에 합류한다(규격에 건물명 필드 없음) —
     * keyword 검색에 등록 장소가 없는 건물(오피스 등)의 이름을 잃지 않기 위함.
     */
    @Override
    public Mono<GeoPlace> lookup(double latitude, double longitude) {
        return Mono.defer(() -> {
            long startNanos = System.nanoTime();
            AtomicInteger calls = new AtomicInteger();
            return fetchAddress(latitude, longitude, calls)
                    .flatMap(address -> fetchPlaces(address, latitude, longitude, calls)
                            .map(places -> new GeoPlace(
                                    address.address(), mergeBuildingName(address.buildingName(), places))))
                    .doOnEach(signal -> logLookupSuccess(signal, calls, startNanos));
        });
    }

    private Mono<KakaoAddress> fetchAddress(double latitude, double longitude, AtomicInteger calls) {
        return fetchDocuments(ENDPOINT_COORD2ADDRESS, calls,
                uriBuilder -> uriBuilder.path("/v2/local/geo/coord2address.json")
                        .queryParam("x", longitude)
                        .queryParam("y", latitude)
                        .build())
                .map(documents -> {
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
                });
    }

    private Mono<List<String>> fetchPlaces(KakaoAddress address, double latitude, double longitude,
            AtomicInteger calls) {
        // 주소가 없으면(도로 위·공터 등) keyword 질의어가 없으므로 장소 검색을 생략한다 — 정상 빈 결과.
        return address.address() == null
                ? Mono.just(List.of())
                : fetchNearbyPlaceNames(address.address(), latitude, longitude, calls);
    }

    /**
     * 주소를 질의어로 keyword 검색 1콜 — 주소 keyword와 반경에 매칭된 장소를 좌표 기준 거리순으로 가져온다.
     * 단일 콜이라 응답의 {@code sort=distance} 순서를 그대로 신뢰한다(전역 병합 정렬·id dedupe 불필요).
     */
    private Mono<List<String>> fetchNearbyPlaceNames(String query, double latitude, double longitude,
            AtomicInteger calls) {
        return fetchDocuments(ENDPOINT_KEYWORD, calls,
                uriBuilder -> uriBuilder.path("/v2/local/search/keyword.json")
                        .queryParam("query", query)
                        .queryParam("x", longitude)
                        .queryParam("y", latitude)
                        .queryParam("radius", PLACES_RADIUS_METERS)
                        .queryParam("sort", "distance")
                        .build())
                .map(documents -> {
                    List<String> places = new ArrayList<>();
                    for (JsonNode document : documents) {
                        String name = blankToNull(document.path("place_name").asText(null));
                        if (name == null) {
                            continue;
                        }
                        places.add(name);
                        if (places.size() == PLACES_MAX_COUNT) {
                            break;
                        }
                    }
                    return places;
                });
    }

    /**
     * 단일 카카오 HTTP 콜 + 응답 shape 검증을 재시도로 감싼다. 전이적 실패(5xx·타임아웃)는 최대 {@link #MAX_ATTEMPTS}회
     * 시도하고, 영구적 실패(4xx·파싱·shape)는 즉시 error 신호로 전달한다. 외부 호출·해석 실패는 전부
     * {@link MapPlaceLookupException}으로 감싼다.
     */
    private Mono<JsonNode> fetchDocuments(String endpoint, AtomicInteger calls, Function<UriBuilder, URI> uriFunction) {
        AtomicInteger attempts = new AtomicInteger();
        return Mono.defer(() -> {
                    calls.incrementAndGet();
                    attempts.incrementAndGet();
                    return webClient.get().uri(uriFunction).retrieve().bodyToMono(JsonNode.class);
                })
                // WebClient는 빈 body를 null이 아니라 empty 신호로 준다 → 기존 null-body 계약(영구 실패)으로 변환.
                .switchIfEmpty(Mono.error(() -> new MapPlaceLookupException(endpoint + " null body", false, null)))
                .map(body -> requireDocuments(body, endpoint))
                .onErrorMap(e -> classify(e, endpoint))
                .retryWhen(Retry.fixedDelay(MAX_ATTEMPTS - 1, BACKOFF)
                        .filter(e -> e instanceof MapPlaceLookupException failure && failure.isRetryable())
                        // 재시도 소진 시 RetryExhaustedException 래핑 대신 마지막 원본 예외를 그대로 전달한다.
                        .onRetryExhaustedThrow((spec, retrySignal) -> retrySignal.failure()))
                .doOnEach(signal -> logCallFailure(signal, endpoint, attempts));
    }

    /**
     * WebClient 예외를 {@link MapPlaceLookupException}으로 분류한다. 이미 분류된 예외(requireDocuments의
     * shape·switchIfEmpty의 null body)는 그대로 통과시켜 endpoint별 메시지·영구 분류를 보존한다(재래핑 금지).
     */
    private static MapPlaceLookupException classify(Throwable e, String endpoint) {
        if (e instanceof MapPlaceLookupException already) {
            return already;
        }
        if (e instanceof WebClientResponseException http) {
            // HTTP status를 받은 4xx/5xx. 5xx만 전이적으로 본다(429·401·403·기타 4xx는 영구).
            boolean retryable = http.getStatusCode().is5xxServerError();
            return new MapPlaceLookupException(endpoint + " http " + http.getStatusCode().value(), retryable, e);
        }
        if (e instanceof WebClientRequestException || e instanceof ReadTimeoutException) {
            // I/O(연결 실패·읽기 타임아웃 등) — 전이적. 읽기 타임아웃은 응답 단계라 netty 예외가 그대로 올 수 있다.
            return new MapPlaceLookupException(endpoint + " io error", true, e);
        }
        if (e instanceof CodecException) {
            // 응답 디코딩/파싱 실패(HTTP status 없음) — 영구적.
            return new MapPlaceLookupException(endpoint + " parse error", false, e);
        }
        // 그 외 전부 감싸 raw RuntimeException이 새는 것을 막는다(catch-all 500 방지) — 영구적.
        return new MapPlaceLookupException(endpoint + " unexpected error", false, e);
    }

    /**
     * 응답에서 {@code documents} 배열을 꺼낸다. {@code {"documents":[]}}(정상 빈 결과)는 통과시키되,
     * {@code documents} 필드 누락·배열 아님은 깨진 응답으로 보고 영구적 {@link MapPlaceLookupException}을 던진다.
     */
    private static JsonNode requireDocuments(JsonNode body, String endpoint) {
        JsonNode documents = body.get("documents");
        if (documents == null || !documents.isArray()) {
            throw new MapPlaceLookupException(endpoint + " missing documents array", false, null);
        }
        return documents;
    }

    private void logLookupSuccess(Signal<GeoPlace> signal, AtomicInteger calls, long startNanos) {
        if (!signal.isOnNext()) {
            return;
        }
        // 좌표값은 로그 금지(위치 민감정보) — 좌표당 실제 HTTP 호출 수(재시도 포함)·소요시간만.
        TxContextLogging.runWithTx(signal.getContextView(), () ->
                log.info("kakao geocoding lookup: calls={} tookMs={}",
                        calls.get(), (System.nanoTime() - startNanos) / 1_000_000));
    }

    private void logCallFailure(Signal<JsonNode> signal, String endpoint, AtomicInteger attempts) {
        if (!signal.isOnError() || !(signal.getThrowable() instanceof MapPlaceLookupException failure)) {
            return;
        }
        // retryWhen 뒤라 최종 실패만 1회 기록된다(재시도 중간 실패는 warn 없음).
        // 좌표·URL·응답 body 금지 — endpoint 종류·retryable·시도 횟수·분류 사유(메시지)만.
        TxContextLogging.runWithTx(signal.getContextView(), () ->
                log.warn("kakao geocoding call failed: endpoint={} retryable={} attempts={} reason={}",
                        endpoint, failure.isRetryable(), attempts.get(), failure.getMessage()));
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
