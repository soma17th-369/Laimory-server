package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
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

/**
 * 카카오 로컬 API {@link MapPlaceProvider} 계약 검증(MockWebServer — 실 HTTP 루프백. WebClient는
 * MockRestServiceServer에 바인딩되지 않으므로 base URL을 test seam({@code app.geo.kakao-base-url} 생성자
 * 파라미터)으로 주입해 Reactor Netty 커넥터·인코딩 실경로까지 검증한다).
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
 * <p>실패 계약(loud fail A): 조용한 degrade 없음 — 2콜 중 하나라도 최종 실패하면
 * {@link MapPlaceLookupException}이 error 신호로 온다(block이 원본 그대로 재던짐). 전이적(5xx·IO) 실패는
 * 콜 단위로 재시도하고(앞 성공 콜은 재실행 안 됨), 영구적(4xx·파싱·shape) 실패는 즉시 던진다.
 * 빈 결과({@code {"documents":[]}})는 실패가 아니다.
 *
 * <p>한 lookup 안의 콜은 순차(coord2address → keyword, 재시도 포함)라 enqueue 순서 매칭으로 충분하다.
 */
class KakaoMapPlaceProviderTest {

    private static final double LATITUDE = 37.5340;
    private static final double LONGITUDE = 126.9668;
    private static final String COORD2ADDRESS_PATH = "/v2/local/geo/coord2address.json";
    private static final String KEYWORD_PATH = "/v2/local/search/keyword.json";

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private KakaoMapPlaceProvider provider() {
        String baseUrl = server.url("/").toString();
        return new KakaoMapPlaceProvider(
                "test-key", baseUrl.substring(0, baseUrl.length() - 1), WebClient.builder());
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

    // ── 실패 계약: 전이적(5xx·IO) → 재시도 후 실패 ──

    @Test
    void lookup_throwsRetryable_whenCoord2addressPersists5xx_afterRetries() {
        // 전이적 실패는 콜 단위로 MAX_ATTEMPTS(2)회 시도한다(최초 1 + 재시도 1). 끝내 실패하면 retryable=true로 던진다.
        // coord2address가 먼저라 keyword는 호출되지 않는다(strict short-circuit).
        enqueueStatus(500);
        enqueueStatus(500);

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isTrue());
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void lookup_retriesFailedCallOnly_notEarlierSucceededCalls() throws InterruptedException {
        // 콜별 재시도 증명: coord2address 성공 후 keyword가 1회 실패→성공. 앞 성공 콜은 재실행되지 않는다
        // (총 3요청 중 coord2address는 1요청뿐 — 재시도가 lookup 전체가 아니라 실패한 콜에만 걸린다).
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
    }

    @Test
    void lookup_retriesIoFailure_andRecovers() {
        // 연결이 응답 없이 끊기는 IO 실패는 전이적 — 재시도로 회복한다. (WebClient 전환으로 추가된 실경로 검증.)
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        enqueueJson(coord2addressBody("서울 용산구 청파로20길 95", null));
        enqueueJson("{\"documents\":[]}");

        GeoPlace geo = lookup();

        assertThat(geo.address()).isEqualTo("서울 용산구 청파로20길 95");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void lookup_throwsRetryable_whenIoFailurePersists() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isTrue());
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    // ── 실패 계약: 영구적(4xx) → 즉시 실패, 재시도 없음 ──

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429})
    void lookup_throwsNonRetryable_andDoesNotRetry_onPermanent4xx(int status) {
        // 영구적 실패(키·권한·쿼터)는 모든 콜에 걸리므로 재시도가 무의미 — 즉시 retryable=false로 던진다.
        enqueueStatus(status);

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isFalse());
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // ── 실패 계약: 비-HTTP 실패(파싱·빈 body·shape) → non-retryable로 감싸짐(raw 누수 아님) ──

    @Test
    void lookup_throwsNonRetryable_whenBodyIsEmpty() {
        // 빈 body는 raw 예외로 새지 않고 non-retryable MapPlaceLookupException으로 감싸진다(→502).
        server.enqueue(new MockResponse());

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isFalse());
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{broken",                       // 파싱 실패(malformed JSON)
            "{\"foo\":1}",                   // documents 필드 누락
            "{\"documents\":\"nope\"}"       // documents가 배열 아님
    })
    void lookup_throwsNonRetryable_whenResponseShapeBroken(String body) {
        // 파싱 실패·shape 오류는 정상 빈 결과와 달리 깨진 응답 → non-retryable로 감싸 던진다(catch-all 500 방지).
        enqueueJson(body);

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isFalse());
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void lookup_throwsNonRetryable_whenKeywordResponseShapeBroken() {
        // shape 검증은 콜 단위로 걸린다 — 두 번째 콜(keyword)의 깨진 응답도 같은 계약으로 던진다.
        enqueueJson(coord2addressBody("서울 용산구 청파로20길 95", null));
        enqueueJson("{\"foo\":1}");

        assertThatThrownBy(this::lookup)
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isFalse());
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    // ── helpers ──

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
