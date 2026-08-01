package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;

/**
 * 카카오 로컬 API {@link MapPlaceProvider} 계약 검증(MockWebServer — 실 HTTP 루프백). WebClient·pool·
 * circuit은 {@link KakaoGeoHttpConfiguration}의 <b>production 배선 그대로</b> 조립해 Reactor Netty 커넥터·
 * 분류·retry·circuit 실경로까지 검증한다(base URL은 {@code app.geo.kakao-base-url} test seam).
 *
 * <p>정상 계약:
 * <ul>
 *   <li>좌표 순서: 요청 파라미터는 x=경도, y=위도(내부 표현과 뒤집힘).
 *   <li>blank 정규화: 카카오는 부재 필드를 빈 문자열로 주는 경우가 있어 null로 정규화한다.
 *   <li>places: coord2address가 준 주소(도로명 우선, 지번 fallback)를 질의어로 keyword 검색 1콜.
 *       단일 콜이라 응답 순서(sort=distance)를 그대로 신뢰하고, coord2address의 건물명은 places 맨 앞에 합류한다.
 *   <li>주소가 없으면 keyword 질의어가 없으므로 콜 자체를 생략한다(정상 빈 결과).
 * </ul>
 *
 * <p>실패 계약: 콜 하나가 최종 실패하면 {@link MapPlaceLookupException}이 error 신호로 온다. 전이적
 * ({@code retryThisCall=true} — 5xx·IO·timeout) 실패만 콜 단위로 재시도하고(앞 성공 콜은 재실행 안 됨),
 * 영구적(4xx·파싱·shape) 실패는 즉시 던진다. 빈 결과({@code {"documents":[]}})는 실패가 아니다.
 * circuit open이면 wire 구독 없이 즉시 {@code NOT_PERMITTED}로 끝난다. 실제 wire 시도 수는 항상 서버가
 * 수락한 요청 수와 일치해야 한다(Netty 숨은 retry 비활성화 — T35).
 *
 * <p>한 lookup 안의 콜은 순차(coord2address → keyword, 재시도 포함)라 enqueue 순서 매칭으로 충분하다.
 */
class KakaoMapPlaceProviderTest {

    private static final double LATITUDE = 37.5340;
    private static final double LONGITUDE = 126.9668;
    private static final String COORD2ADDRESS_PATH = "/v2/local/geo/coord2address.json";
    private static final String KEYWORD_PATH = "/v2/local/search/keyword.json";

    private final KakaoGeoHttpConfiguration configuration = new KakaoGeoHttpConfiguration();

    private MockWebServer server;
    private SimpleMeterRegistry meterRegistry;
    private ObservationRegistry observationRegistry;
    private GeoMetrics geoMetrics;
    private ConnectionProvider pool;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        meterRegistry = new SimpleMeterRegistry();
        geoMetrics = new GeoMetrics(meterRegistry);
        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (pool != null) {
            pool.dispose();
        }
        server.shutdown();
    }

    /** 빠른 결정적 실행용 기본 설정 — jitter 0, 짧은 backoff. 구조는 production 기본값과 동일하다. */
    private GeoProperties properties(Duration responseTimeout) {
        return new GeoProperties(20,
                new GeoProperties.Http(Duration.ofSeconds(1), responseTimeout, Duration.ofSeconds(9),
                        new GeoProperties.Http.Pool(20, 20, Duration.ofSeconds(1),
                                Duration.ofSeconds(20), Duration.ofMinutes(5), Duration.ofSeconds(10))),
                new GeoProperties.Retry(2, Duration.ofMillis(50), Duration.ofMillis(100), 0.0),
                new GeoProperties.Circuit(20, 10, 50, Duration.ofSeconds(30), 3));
    }

    /** production 배선({@link KakaoGeoHttpConfiguration}) 그대로 provider를 조립한다. */
    private KakaoMapPlaceProvider provider(GeoProperties properties) {
        String baseUrl = server.url("/").toString();
        pool = configuration.kakaoGeoConnectionProvider(properties);
        circuitBreaker = configuration.kakaoGeoCircuitBreaker(properties, meterRegistry, geoMetrics);
        WebClient webClient = configuration.kakaoGeoWebClient(
                WebClient.builder().observationRegistry(observationRegistry), pool, properties,
                "test-key", baseUrl.substring(0, baseUrl.length() - 1));
        return new KakaoMapPlaceProvider(webClient, circuitBreaker, properties, geoMetrics);
    }

    private KakaoMapPlaceProvider provider() {
        return provider(properties(Duration.ofSeconds(2)));
    }

    private GeoPlace lookup() {
        return provider().lookup(LATITUDE, LONGITUDE).block();
    }

    // ── 정상 계약 ──

    @Test
    void lookup_sendsLongitudeAsX_andLatitudeAsY_withKakaoAkHeader() throws InterruptedException {
        // 좌표 순서 회귀 방지: 두 콜 모두 x=경도, y=위도.
        enqueueJson(coord2addressBody("서울 용산구 청파로20길 95", "서울드래곤시티"));
        enqueueJson("{\"documents\":[]}");

        GeoPlace geo = lookup();

        assertThat(geo.address()).isEqualTo("서울 용산구 청파로20길 95");
        // 건물명은 별도 필드 없이 places 맨 앞에 합류한다.
        assertThat(geo.places()).containsExactly("서울드래곤시티");

        RecordedRequest coord2address = takeRequest();
        assertThat(coord2address.getRequestUrl().encodedPath()).isEqualTo(COORD2ADDRESS_PATH);
        assertThat(coord2address.getHeader("Authorization")).isEqualTo("KakaoAK test-key");
        assertThat(coord2address.getRequestUrl().queryParameter("x")).isEqualTo(String.valueOf(LONGITUDE));
        assertThat(coord2address.getRequestUrl().queryParameter("y")).isEqualTo(String.valueOf(LATITUDE));

        RecordedRequest keyword = takeRequest();
        assertThat(keyword.getRequestUrl().encodedPath()).isEqualTo(KEYWORD_PATH);
        assertThat(keyword.getHeader("Authorization")).isEqualTo("KakaoAK test-key");
        assertThat(keyword.getRequestUrl().queryParameter("query")).isEqualTo("서울 용산구 청파로20길 95");
        assertThat(keyword.getRequestUrl().queryParameter("x")).isEqualTo(String.valueOf(LONGITUDE));
        assertThat(keyword.getRequestUrl().queryParameter("y")).isEqualTo(String.valueOf(LATITUDE));
        assertThat(keyword.getRequestUrl().queryParameter("radius")).isEqualTo("50");
        assertThat(keyword.getRequestUrl().queryParameter("sort")).isEqualTo("distance");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void lookup_usesRoadAddressAsKeywordQuery_whenBothAddressesPresent() throws InterruptedException {
        // 도로명·지번이 둘 다 있으면 질의어는 도로명 — 한 도로명에 지번 여러 개가 매핑될 때 도로명 결과가 더 넓다.
        enqueueJson("""
                {"documents":[{
                  "road_address":{"address_name":"경기 하남시 미사대로 750","building_name":""},
                  "address":{"address_name":"경기 하남시 신장동 616"}
                }]}""");
        enqueueJson("{\"documents\":[]}");

        GeoPlace geo = lookup();

        assertThat(geo.address()).isEqualTo("경기 하남시 미사대로 750");
        takeRequest();
        assertThat(takeRequest().getRequestUrl().queryParameter("query")).isEqualTo("경기 하남시 미사대로 750");
    }

    @Test
    void lookup_fallsBackToLotAddress_asAddressAndKeywordQuery_whenRoadAddressMissing() throws InterruptedException {
        // 도로명주소는 건물에만 부여돼 도로 위 좌표(교차로 등)엔 없다 → 주소도 keyword 질의어도 지번으로 fallback.
        enqueueJson("""
                {"documents":[{"road_address":null,"address":{"address_name":"서울 강남구 역삼동 858"}}]}""");
        enqueueJson("{\"documents\":[]}");

        GeoPlace geo = lookup();

        assertThat(geo.address()).isEqualTo("서울 강남구 역삼동 858");
        takeRequest();
        assertThat(takeRequest().getRequestUrl().queryParameter("query")).isEqualTo("서울 강남구 역삼동 858");
    }

    @Test
    void lookup_skipsKeywordSearch_whenAddressAbsent() {
        // {"documents":[]}는 정상 빈 결과(주소 부재) — 실패가 아니고, 질의어가 없으니 keyword 콜을 생략한다(좌표당 1콜).
        enqueueJson("{\"documents\":[]}");

        GeoPlace geo = lookup();

        assertThat(geo.address()).isNull();
        assertThat(geo.places()).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void lookup_normalizesBlankFieldsToNull_andEmptyPlacesStayEmpty() {
        // 카카오가 building_name=""로 주면 blank → null 정규화(합류 없음). 정상 조회 + 등록 장소 없음 = 빈 배열.
        enqueueJson(coord2addressBody("서울 용산구 청파로20길 95", ""));
        enqueueJson("{\"documents\":[]}");

        GeoPlace geo = lookup();

        assertThat(geo.address()).isEqualTo("서울 용산구 청파로20길 95");
        assertThat(geo.places()).isEmpty();
    }

    @Test
    void lookup_keepsApiOrder_skipsBlankNames_andPrependsBuildingName() {
        enqueueJson(coord2addressBody("서울 용산구 청파로20길 95", "서울드래곤시티"));
        enqueueJson("""
                {"documents":[
                  {"id":"1","place_name":"가까운 카페"},
                  {"id":"2","place_name":""},
                  {"id":"3","place_name":"그랑씨엘"}
                ]}""");

        GeoPlace geo = lookup();

        // 단일 콜이라 응답 순서(sort=distance)를 그대로 신뢰한다. blank 이름은 제외, 건물명은 맨 앞.
        assertThat(geo.places()).containsExactly("서울드래곤시티", "가까운 카페", "그랑씨엘");
    }

    @Test
    void lookup_doesNotDuplicateBuildingName_whenAlreadyInPlaces() {
        // 건물 자체가 장소로도 등록된 경우(복합몰 등) 건물명이 두 번 들어가면 안 된다.
        enqueueJson(coord2addressBody("경기 하남시 미사대로 750", "스타필드 하남"));
        enqueueJson("""
                {"documents":[{"id":"1","place_name":"스타필드 하남"}]}""");

        assertThat(lookup().places()).containsExactly("스타필드 하남");
    }

    @Test
    void lookup_limitsPlacesToTen() {
        enqueueJson(coord2addressBody("서울 용산구 청파로20길 95", null));
        StringBuilder documents = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0) {
                documents.append(',');
            }
            documents.append("{\"id\":\"").append(i).append("\",\"place_name\":\"장소").append(i).append("\"}");
        }
        enqueueJson("{\"documents\":[" + documents + "]}");

        assertThat(lookup().places()).hasSize(10);
    }

    // ── T29 일부: metric tag에 좌표·주소·query 비노출 ──

    @Test
    void lookup_metricsDoNotUseCoordinatesOrQueryAsTags() {
        String address = "좌표와 함께 metric tag에 들어가면 안 되는 주소";
        enqueueJson(coord2addressBody(address, null));
        enqueueJson("{\"documents\":[]}");

        lookup();

        assertThat(meterRegistry.getMeters())
                .isNotEmpty()
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getValue())
                .noneMatch(value -> value.contains(String.valueOf(LATITUDE))
                        || value.contains(String.valueOf(LONGITUDE))
                        || value.contains(address));
    }

    // ── 실패 계약: 전이적(5xx·IO) → 재시도 후 실패 ──

    @Test
    void lookup_throwsTransient_whenCoord2addressPersists5xx_afterRetries() {
        // 전이적 실패는 콜 단위로 max-attempts(2)회 시도한다(최초 1 + 재시도 1). 끝내 실패하면 REMOTE·
        // clientMayRetryLater=true로 던진다. coord2address가 먼저라 keyword는 호출되지 않는다(short-circuit).
        enqueueStatus(500);
        enqueueStatus(500);

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> {
                    assertThat(e.category()).isEqualTo(MapPlaceLookupException.Category.REMOTE);
                    assertThat(e.clientMayRetryLater()).isTrue();
                });
        // T35: 애플리케이션 attempt 수 == 서버가 수락한 요청 수(숨은 retry 없음).
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(attemptCount("coord2address", "first")).isEqualTo(1);
        assertThat(attemptCount("coord2address", "retry")).isEqualTo(1);
        assertThat(retryCount("coord2address", "transient")).isEqualTo(1);
    }

    @Test
    void lookup_retriesFailedCallOnly_notEarlierSucceededCalls() throws InterruptedException {
        // T21/T36: 콜별 재시도 증명 — coord2address 성공 후 keyword가 1회 실패→성공. 앞 성공 콜은 재실행되지
        // 않는다(총 3요청 중 coord2address는 1요청뿐 — 재시도가 lookup 전체가 아니라 실패한 콜에만 걸린다).
        enqueueJson(coord2addressBody("서울 용산구 청파로20길 95", null));
        enqueueStatus(502);
        enqueueJson("""
                {"documents":[{"id":"3","place_name":"가까운 카페"}]}""");

        GeoPlace geo = lookup();

        // 재시도로 keyword 결과가 살아남는다.
        assertThat(geo.places()).containsExactly("가까운 카페");
        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(takeRequest().getRequestUrl().encodedPath()).isEqualTo(COORD2ADDRESS_PATH);
        assertThat(takeRequest().getRequestUrl().encodedPath()).isEqualTo(KEYWORD_PATH);
        assertThat(takeRequest().getRequestUrl().encodedPath()).isEqualTo(KEYWORD_PATH);
        assertThat(attemptCount("coord2address", "first")).isEqualTo(1);
        assertThat(attemptCount("coord2address", "retry")).isZero();
        assertThat(attemptCount("keyword", "retry")).isEqualTo(1);
        assertThat(retryCount("keyword", "transient")).isEqualTo(1);
    }

    @Test
    void lookup_retriesIoFailure_andRecovers() {
        // 연결이 응답 없이 끊기는 IO 실패는 전이적 — 재시도로 회복한다.
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        enqueueJson(coord2addressBody("서울 용산구 청파로20길 95", null));
        enqueueJson("{\"documents\":[]}");

        GeoPlace geo = lookup();

        assertThat(geo.address()).isEqualTo("서울 용산구 청파로20길 95");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void lookup_throwsTransient_whenIoFailurePersists() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> {
                    assertThat(e.category()).isEqualTo(MapPlaceLookupException.Category.REMOTE);
                    assertThat(e.clientMayRetryLater()).isTrue();
                });
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void lookup_throwsTransient_whenResponseStallsBeyondResponseTimeout() {
        // T26/R4: 응답 지연(response-timeout 초과)은 전이(io) 실패 — 콜 단위 재시도 후 REMOTE transient.
        // 전용 connector의 responseTimeout이 실제로 배선돼 있지 않으면 지연 응답이 그냥 성공해 깨진다.
        server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS));
        server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS));
        KakaoMapPlaceProvider provider = provider(properties(Duration.ofMillis(250)));

        assertThatThrownBy(() -> provider.lookup(LATITUDE, LONGITUDE).block())
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> {
                    assertThat(e.category()).isEqualTo(MapPlaceLookupException.Category.REMOTE);
                    assertThat(e.clientMayRetryLater()).isTrue();
                });
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void lookup_retriesAsTransient_whenBodyStallsAfterSuccessfulHeaders() {
        // 2xx headers 뒤 body read timeout은 WebClientResponseException(200)으로 감싸질 수 있다.
        // outer status만 보고 영구 실패로 오분류하지 않고 cause의 I/O를 따라 전이 실패로 재시도해야 한다.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"documents\":[]}")
                .setBodyDelay(1, TimeUnit.SECONDS));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"documents\":[]}")
                .setBodyDelay(1, TimeUnit.SECONDS));
        KakaoMapPlaceProvider provider = provider(properties(Duration.ofMillis(250)));

        assertThatThrownBy(() -> provider.lookup(LATITUDE, LONGITUDE).block())
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> {
                    assertThat(e.category()).isEqualTo(MapPlaceLookupException.Category.REMOTE);
                    assertThat(e.retryThisCall()).isTrue();
                    assertThat(e.clientMayRetryLater()).isTrue();
                });
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(attemptCount("coord2address", "first")).isEqualTo(1);
        assertThat(attemptCount("coord2address", "retry")).isEqualTo(1);
    }

    @Test
    void lookup_failsFastAsTransient_whenConnectionRefused_withoutHiddenLongWait() throws IOException {
        // T26: 연결 거절은 45s 숨은 대기 없이 빠르게 전이 실패로 분류돼야 한다(전용 pool acquire 45s 기본 제거).
        server.shutdown();
        long startMillis = System.currentTimeMillis();

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> {
                    assertThat(e.category()).isEqualTo(MapPlaceLookupException.Category.REMOTE);
                    assertThat(e.clientMayRetryLater()).isTrue();
                });
        assertThat(System.currentTimeMillis() - startMillis).isLessThan(5_000);
    }

    // ── 실패 계약: 영구적(4xx) → 즉시 실패, 재시도 없음 ──

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429})
    void lookup_throwsPermanent_andDoesNotRetry_onPermanent4xx(int status) {
        // 영구적 실패(키·권한·쿼터)는 즉시 재시도가 무의미 — clientMayRetryLater=false로 즉시 던진다(retry 0).
        enqueueStatus(status);

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> {
                    assertThat(e.category()).isEqualTo(MapPlaceLookupException.Category.REMOTE);
                    assertThat(e.retryThisCall()).isFalse();
                    assertThat(e.clientMayRetryLater()).isFalse();
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void lookup_treatsThreeHundredResponseAsPermanent_evenWhenBodyLooksValid() {
        // retrieve의 기본 오류 처리는 4xx/5xx뿐이라, 별도 non-2xx guard가 없으면 유효해 보이는 3xx body를
        // 성공으로 잘못 셀 수 있다. D8/D14에 따라 모든 non-2xx는 remote permanent다.
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"documents\":[]}"));

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> {
                    assertThat(e.category()).isEqualTo(MapPlaceLookupException.Category.REMOTE);
                    assertThat(e.retryThisCall()).isFalse();
                    assertThat(e.clientMayRetryLater()).isFalse();
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // ── 실패 계약: 비-HTTP 실패(파싱·빈 body·shape) → 영구로 감싸짐(raw 누수 아님) ──

    @Test
    void lookup_throwsPermanent_whenBodyIsEmpty() {
        // 빈 body는 raw 예외로 새지 않고 영구 MapPlaceLookupException으로 감싸진다(→502).
        server.enqueue(new MockResponse());

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class,
                        e -> assertThat(e.clientMayRetryLater()).isFalse());
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{broken",                       // 파싱 실패(malformed JSON)
            "{\"foo\":1}",                   // documents 필드 누락
            "{\"documents\":\"nope\"}"       // documents가 배열 아님
    })
    void lookup_throwsPermanent_whenResponseShapeBroken(String body) {
        // 파싱 실패·shape 오류는 정상 빈 결과와 달리 깨진 응답 → 영구로 감싸 던진다(catch-all 500 방지).
        enqueueJson(body);

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class,
                        e -> assertThat(e.clientMayRetryLater()).isFalse());
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void lookup_throwsPermanent_whenKeywordResponseShapeBroken() {
        // shape 검증은 콜 단위로 걸린다 — 두 번째 콜(keyword)의 깨진 응답도 같은 계약으로 던진다.
        enqueueJson(coord2addressBody("서울 용산구 청파로20길 95", null));
        enqueueJson("{\"foo\":1}");

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class,
                        e -> assertThat(e.clientMayRetryLater()).isFalse());
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void lookup_propagatesUnexpectedProgrammingError_asIs_withoutCircuitFailure() {
        // D4: transport/response 계약 밖의 코드 오류는 permanent geo 실패로 감싸 partial/502로 숨기지 않는다.
        IllegalStateException bug = new IllegalStateException("exchange implementation bug");
        WebClient buggyClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(bug))
                .build();
        GeoProperties bugProperties = properties(Duration.ofSeconds(2));
        // production ignoreException 정책까지 포함해 조립한다. ofDefaults는 모든 RuntimeException을
        // failure로 세므로 D14 계약을 검증할 수 없다.
        CircuitBreaker bugCircuit = configuration.kakaoGeoCircuitBreaker(
                bugProperties, meterRegistry, geoMetrics);
        KakaoMapPlaceProvider buggyProvider = new KakaoMapPlaceProvider(
                buggyClient, bugCircuit, bugProperties, geoMetrics);

        assertThatThrownBy(() -> buggyProvider.lookup(LATITUDE, LONGITUDE).block())
                .isSameAs(bug);
        // ignoreException 정책도 raw programming error를 remote 건강도 실패로 세지 않는다.
        assertThat(bugCircuit.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    // ── T22/D14: circuit 계수 — 유효 2xx(documents=[] 포함)는 성공, remote 실패만 failure ──

    @Test
    void circuitBreaker_recordsEmptyDocumentsAsSuccess_andRemoteFailuresAsFailure() {
        KakaoMapPlaceProvider provider = provider();

        enqueueJson("{\"documents\":[]}");
        provider.lookup(LATITUDE, LONGITUDE).block();
        // 정상 빈 결과는 circuit 성공으로 계수된다.
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();

        enqueueStatus(429);
        assertThatThrownBy(() -> provider.lookup(LATITUDE, LONGITUDE).block())
                .isInstanceOf(MapPlaceLookupException.class);
        // 429 포함 non-2xx remote 실패는 failure로 계수된다.
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    // ── helpers ──

    private double attemptCount(String endpoint, String attempt) {
        var counter = meterRegistry.find("laimory.geo.http.attempts")
                .tag("endpoint", endpoint).tag("attempt", attempt).counter();
        return counter == null ? 0 : counter.count();
    }

    private double retryCount(String endpoint, String failureKind) {
        var counter = meterRegistry.find("laimory.geo.http.retries")
                .tag("endpoint", endpoint).tag("failure_kind", failureKind).counter();
        return counter == null ? 0 : counter.count();
    }

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private void enqueueStatus(int status) {
        server.enqueue(new MockResponse().setResponseCode(status));
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        return request;
    }

    private static String coord2addressBody(String addressName, String buildingName) {
        String address = (addressName == null) ? "\"\"" : "\"" + addressName + "\"";
        String building = (buildingName == null) ? "\"\"" : "\"" + buildingName + "\"";
        return "{\"documents\":[{\"road_address\":{\"address_name\":" + address
                + ",\"building_name\":" + building + "}}]}";
    }
}
