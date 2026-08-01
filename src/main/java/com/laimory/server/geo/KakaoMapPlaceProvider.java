package com.laimory.server.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.common.http.RetryHelper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.netty.handler.timeout.ReadTimeoutException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.codec.CodecException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
// Reactor Netty는 reactor-pool을 shaded로 내장한다 — 전용 pool의 acquire 거절·timeout이 실제로 던지는
// runtime 타입이 이 shaded 클래스라 문자열 매칭 대신 직접 참조한다(버전 업그레이드 시 경로 재확인).
import reactor.netty.internal.shaded.reactor.pool.PoolAcquirePendingLimitException;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquireTimeoutException;

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
 * 경계는 {@link GeocodingService}가 전담한다. 전용 pool·connect/response timeout·logical deadline은
 * {@code app.geo.http.*}({@link KakaoGeoHttpConfiguration})가 SSOT다. base URL은 config의
 * {@code app.geo.kakao-base-url}이 주입한다(운영 endpoint 변경용이 아니라 MockWebServer용 test seam).
 *
 * <p><b>단일 HTTP 콜의 실행 순서(안쪽→바깥)</b>: wire 호출 + 2xx/body shape 분류 → attempt circuit
 * ({@code kakao-local} — remote outcome만 계수) → {@link RetryHelper}(전이 실패만 backoff retry) →
 * logical deadline 분류. circuit open이면 wire 구독 전에 차단되고 retry 대상이 아니다. 재시도는
 * <b>단일 HTTP 콜 단위</b>로 건다 — lookup 전체에 걸면 늦은 콜(keyword)의 실패가 성공한 앞 콜
 * (coord2address)까지 재실행하므로.
 *
 * <p><b>실패 처리</b>: 콜이 최종 실패하면 {@link MapPlaceLookupException}을 error 신호로 전달한다(조용한
 * null degrade 없음). 부분 실패 허용·거절은 상위 정책(D1/D2)이 materialize된 좌표별 outcome으로 판정한다.
 *
 * <p>{@code app.geo.mode=kakao}일 때만 빈으로 등록된다({@code @ConditionalOnProperty}). {@code noop}이거나
 * 미설정이면 {@link NoOpMapPlaceProvider}가 대신 선택되고, 그 외 값(오타)이면 어느 provider도 매칭되지 않아
 * 컨텍스트가 기동 실패한다(암시적 fail-fast). API key 자기검증은 {@link KakaoGeoHttpConfiguration}이 수행한다.
 *
 * <p>⚠️ 좌표는 위치 민감정보다 — 로그엔 endpoint 종류·분류·시도 횟수·분류 사유만 남기고 좌표·요청 URL·응답
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

    static final String ENDPOINT_COORD2ADDRESS = "coord2address";
    static final String ENDPOINT_KEYWORD = "keyword";

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final GeoMetrics geoMetrics;
    private final RetryHelper.RetryPolicy retryPolicy;

    public KakaoMapPlaceProvider(
            @Qualifier("kakaoGeoWebClient") WebClient kakaoGeoWebClient,
            CircuitBreaker kakaoGeoCircuitBreaker,
            KakaoGeoProperties properties,
            GeoMetrics geoMetrics) {
        this.webClient = kakaoGeoWebClient;
        this.circuitBreaker = kakaoGeoCircuitBreaker;
        this.geoMetrics = geoMetrics;
        this.retryPolicy = new RetryHelper.RetryPolicy(
                properties.retry().maxAttempts(),
                properties.retry().firstBackoff(),
                properties.retry().maxBackoff(),
                properties.retry().jitter(),
                properties.http().logicalCallTimeout());
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
     * 단일 카카오 HTTP 콜 하나의 <b>logical call</b> — 분류된 wire attempt에 circuit을 적용하고 그 바깥에서
     * {@code retryThisCall} 실패만 제한적으로 재시도하며, 전체를 logical deadline으로 유계화한다.
     * per-call 상태(attempt 수·직전 실패)는 {@code Mono.defer} 안에서 만들어 재구독에 안전하다.
     */
    private Mono<JsonNode> fetchDocuments(String endpoint, AtomicInteger calls, Function<UriBuilder, URI> uriFunction) {
        return Mono.defer(() -> {
            long startNanos = System.nanoTime();
            AtomicInteger attempts = new AtomicInteger();
            return RetryHelper.callable(
                            "kakao-geo-" + endpoint,
                            () -> wireAttempt(endpoint, uriFunction, calls, attempts),
                            retryPolicy,
                            e -> e instanceof MapPlaceLookupException failure && failure.retryThisCall())
                    // helper deadline 만료(TimeoutException)를 typed transient outcome으로 분류한다. wire의
                    // pool acquire/response timeout은 이미 안쪽에서 분류돼 여기 도달하지 않는다.
                    .onErrorMap(TimeoutException.class, e ->
                            MapPlaceLookupException.logicalDeadline(endpoint + " logical deadline exceeded", e))
                    .doOnEach(signal -> recordLogicalCall(signal, endpoint, startNanos))
                    .doOnEach(signal -> logCallFailure(signal, endpoint, attempts));
        });
    }

    /**
     * 실제 wire attempt 하나 — 안쪽에서 바깥 순서로 (1) HTTP 호출·2xx/body shape 검증·오류 분류,
     * (2) attempt circuit(open이면 wire 구독 자체가 없음 — attempt 계수도 없음), (3) open 거절 분류.
     * attempt counter는 wire가 실제 구독될 때, retry counter는 직전 실패 뒤 retry가 schedule될 때 증가한다.
     */
    private Mono<JsonNode> wireAttempt(String endpoint, Function<UriBuilder, URI> uriFunction,
            AtomicInteger calls, AtomicInteger attempts) {
        return webClient.get().uri(uriFunction).retrieve()
                // retrieve 기본 오류 처리는 4xx/5xx만 포함한다. D8/D14의 "모든 non-2xx 실패" 계약을
                // 지키기 위해 3xx도 명시적으로 WebClientResponseException으로 변환한다.
                .onStatus(status -> !status.is2xxSuccessful(), response -> response.createException())
                .bodyToMono(JsonNode.class)
                // WebClient는 빈 body를 null이 아니라 empty 신호로 준다 → 기존 null-body 계약(영구 실패)으로 변환.
                .switchIfEmpty(Mono.error(() -> MapPlaceLookupException.remotePermanent(endpoint + " null body", null)))
                .map(body -> requireDocuments(body, endpoint))
                // transport/응답 계약 실패만 typed outcome으로 바꾼다. 예상 밖 RuntimeException은
                // circuit 통계에서도 ignore된 채 원본 그대로 전파해 상위 catch-all 500이 되게 한다(D4).
                .onErrorMap(KakaoMapPlaceProvider::isExpectedFailure, e -> classify(e, endpoint))
                .doOnSubscribe(subscription -> {
                    int attempt = attempts.incrementAndGet();
                    calls.incrementAndGet();
                    geoMetrics.countAttempt(endpoint, attempt == 1);
                })
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(CallNotPermittedException.class, e ->
                        MapPlaceLookupException.notPermitted(endpoint + " circuit open", e))
                // retry가 실제 schedule되는 시점(직전 attempt 실패 직후)에 1회. logical deadline이
                // backoff 중 만료돼 다음 wire가 시작되지 않아도 scheduled retry는 관측된다.
                .doOnError(MapPlaceLookupException.class, failure -> {
                    if (failure.retryThisCall() && attempts.get() < retryPolicy.maxAttempts()) {
                        geoMetrics.countRetry(endpoint, GeoMetrics.failureKind(failure));
                    }
                });
    }

    /** provider 계약상 분류할 수 있는 transport/응답 실패인지 — 그 밖의 예외는 programming error다. */
    private static boolean isExpectedFailure(Throwable e) {
        return e instanceof MapPlaceLookupException
                || isPoolAcquireFailure(e)
                || e instanceof WebClientResponseException
                || e instanceof WebClientRequestException
                || e instanceof ReadTimeoutException
                || e instanceof IOException
                || e instanceof CodecException;
    }

    /**
     * WebClient 예외를 {@link MapPlaceLookupException}으로 분류한다. 이미 분류된 예외(requireDocuments의
     * shape·switchIfEmpty의 null body)는 그대로 통과시켜 endpoint별 메시지·분류를 보존한다(재래핑 금지).
     */
    private static MapPlaceLookupException classify(Throwable e, String endpoint) {
        if (e instanceof MapPlaceLookupException already) {
            return already;
        }
        if (isPoolAcquireFailure(e)) {
            // 전용 pool의 acquire 거절(pending 초과)·timeout — remote에 도달하지 않은 local 압력.
            return MapPlaceLookupException.localRejected(endpoint + " pool acquire rejected", e);
        }
        if (e instanceof WebClientResponseException http) {
            // 2xx headers 뒤 body read timeout/disconnect는 WebClient가 원인을 cause로 둔
            // WebClientResponseException(2xx)으로 감쌀 수 있다. status만 보면 영구 응답 오류로
            // 오분류되므로 이 경우에는 실제 I/O 원인을 우선한다. non-2xx는 받은 status가 권위다.
            if (http.getStatusCode().is2xxSuccessful() && hasRemoteIoCause(http)) {
                return MapPlaceLookupException.remoteTransient(endpoint + " io error", e);
            }
            // HTTP status를 받은 4xx/5xx. 5xx만 전이적으로 본다(429·401·403·기타 4xx는 영구).
            boolean transientStatus = http.getStatusCode().is5xxServerError();
            String message = endpoint + " http " + http.getStatusCode().value();
            return transientStatus
                    ? MapPlaceLookupException.remoteTransient(message, e)
                    : MapPlaceLookupException.remotePermanent(message, e);
        }
        if (e instanceof WebClientRequestException || e instanceof ReadTimeoutException || e instanceof IOException) {
            // I/O(연결 실패·connect timeout·DNS·disconnect·읽기 타임아웃 등) — 전이적. 응답 단계 오류는
            // netty/reactor 예외(ReadTimeoutException·PrematureCloseException 등)가 그대로 올 수 있다.
            return MapPlaceLookupException.remoteTransient(endpoint + " io error", e);
        }
        if (e instanceof CodecException) {
            // 응답 디코딩/파싱 실패(HTTP status 없음) — 영구적.
            return MapPlaceLookupException.remotePermanent(endpoint + " parse error", e);
        }
        // 호출자는 isExpectedFailure로 거른다. 이 분기는 새 expected failure 타입을 predicate에만 추가하는
        // 실수를 조용히 영구 실패로 만들지 않도록 programming error로 남긴다.
        throw new IllegalArgumentException("unclassified kakao failure", e);
    }

    /** 2xx 응답 body 단계에서 WebClientResponseException 안에 감싸진 remote I/O 원인이 있는지. */
    private static boolean hasRemoteIoCause(Throwable e) {
        for (Throwable current = e.getCause(); current != null; current = current.getCause()) {
            if (current instanceof WebClientRequestException
                    || current instanceof ReadTimeoutException
                    || current instanceof IOException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /** cause 체인에 전용 pool acquire 거절(pending 초과)·timeout이 있는지 — WebClient 래핑과 무관하게 탐지. */
    private static boolean isPoolAcquireFailure(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof PoolAcquireTimeoutException || current instanceof PoolAcquirePendingLimitException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /**
     * 응답에서 {@code documents} 배열을 꺼낸다. {@code {"documents":[]}}(정상 빈 결과)는 통과시키되,
     * {@code documents} 필드 누락·배열 아님은 깨진 응답으로 보고 영구적 {@link MapPlaceLookupException}을 던진다.
     */
    private static JsonNode requireDocuments(JsonNode body, String endpoint) {
        JsonNode documents = body.get("documents");
        if (documents == null || !documents.isArray()) {
            throw MapPlaceLookupException.remotePermanent(endpoint + " missing documents array", null);
        }
        return documents;
    }

    private void recordLogicalCall(Signal<JsonNode> signal, String endpoint, long startNanos) {
        // Mono는 onNext 뒤 onComplete가 또 오므로 onNext/onError에서만 기록해 정확히 1회를 보장한다.
        Duration took = Duration.ofNanos(System.nanoTime() - startNanos);
        if (signal.isOnNext()) {
            geoMetrics.recordLogicalCall(endpoint, "success", "none", took);
            return;
        }
        if (signal.isOnError() && signal.getThrowable() instanceof MapPlaceLookupException failure) {
            geoMetrics.recordLogicalCall(endpoint,
                    GeoMetrics.logicalOutcome(failure), GeoMetrics.failureKind(failure), took);
        }
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
        // retry 소진 뒤라 최종 실패만 1회 기록된다(재시도 중간 실패는 warn 없음).
        // 좌표·URL·응답 body 금지 — endpoint 종류·분류·시도 횟수·분류 사유(메시지)만.
        TxContextLogging.runWithTx(signal.getContextView(), () ->
                log.warn("kakao geocoding call failed: endpoint={} category={} retryThisCall={} "
                                + "clientMayRetryLater={} attempts={} reason={}",
                        endpoint, failure.category(), failure.retryThisCall(),
                        failure.clientMayRetryLater(), attempts.get(), failure.getMessage()));
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
