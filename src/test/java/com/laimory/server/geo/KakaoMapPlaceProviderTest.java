package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 카카오 로컬 API {@link MapPlaceProvider} 계약 검증(MockRestServiceServer — 실 HTTP 없음).
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
 * {@link MapPlaceLookupException}을 던진다. 전이적(5xx) 실패는 콜 단위로 재시도하고(앞 성공 콜은 재실행 안 됨),
 * 영구적(4xx·파싱·shape) 실패는 즉시 던진다. 빈 결과({@code {"documents":[]}})는 실패가 아니다.
 */
class KakaoMapPlaceProviderTest {

    private static final double LATITUDE = 37.5340;
    private static final double LONGITUDE = 126.9668;
    private static final String COORD2ADDRESS_URI_TEMPLATE =
            "https://dapi.kakao.com/v2/local/geo/coord2address.json?x={x}&y={y}";
    private static final String KEYWORD_URI_TEMPLATE =
            "https://dapi.kakao.com/v2/local/search/keyword.json"
                    + "?query={query}&x={x}&y={y}&radius=50&sort=distance";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private KakaoMapPlaceProvider provider() {
        return new KakaoMapPlaceProvider("test-key", builder);
    }

    // ── 정상 계약 ──

    @Test
    void lookup_sendsLongitudeAsX_andLatitudeAsY_withKakaoAkHeader() {
        // 좌표 순서 회귀 방지: 두 콜 모두 x=경도, y=위도.
        server.expect(requestToUriTemplate(COORD2ADDRESS_URI_TEMPLATE, LONGITUDE, LATITUDE))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess(coord2addressBody("서울 용산구 청파로20길 95", "서울드래곤시티"),
                        MediaType.APPLICATION_JSON));
        server.expect(requestToUriTemplate(KEYWORD_URI_TEMPLATE, "서울 용산구 청파로20길 95", LONGITUDE, LATITUDE))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andExpect(queryParam("x", String.valueOf(LONGITUDE)))
                .andExpect(queryParam("y", String.valueOf(LATITUDE)))
                .andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isEqualTo("서울 용산구 청파로20길 95");
        // 건물명은 별도 필드 없이 places 맨 앞에 합류한다.
        assertThat(geo.places()).containsExactly("서울드래곤시티");
        server.verify();
    }

    @Test
    void lookup_usesRoadAddressAsKeywordQuery_whenBothAddressesPresent() {
        // 도로명·지번이 둘 다 있으면 질의어는 도로명 — 한 도로명에 지번 여러 개가 매핑될 때 도로명 결과가 더 넓다.
        expectCoord2address("""
                {"documents":[{
                  "road_address":{"address_name":"경기 하남시 미사대로 750","building_name":""},
                  "address":{"address_name":"경기 하남시 신장동 616"}
                }]}""");
        expectKeywordSearch("경기 하남시 미사대로 750", "{\"documents\":[]}");

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isEqualTo("경기 하남시 미사대로 750");
        server.verify();
    }

    @Test
    void lookup_fallsBackToLotAddress_asAddressAndKeywordQuery_whenRoadAddressMissing() {
        // 도로명주소는 건물에만 부여돼 도로 위 좌표(교차로 등)엔 없다 → 주소도 keyword 질의어도 지번으로 fallback.
        expectCoord2address("""
                {"documents":[{"road_address":null,"address":{"address_name":"서울 강남구 역삼동 858"}}]}""");
        expectKeywordSearch("서울 강남구 역삼동 858", "{\"documents\":[]}");

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isEqualTo("서울 강남구 역삼동 858");
        server.verify();
    }

    @Test
    void lookup_skipsKeywordSearch_whenAddressAbsent() {
        // {"documents":[]}는 정상 빈 결과(주소 부재) — 실패가 아니고, 질의어가 없으니 keyword 콜을 생략한다(좌표당 1콜).
        // keyword 기대를 등록하지 않았으므로 콜이 나갔다면 매칭 실패로 터진다 — verify가 미호출을 고정.
        expectCoord2address("{\"documents\":[]}");

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isNull();
        assertThat(geo.places()).isEmpty();
        server.verify();
    }

    @Test
    void lookup_normalizesBlankFieldsToNull_andEmptyPlacesStayEmpty() {
        // 카카오가 building_name=""로 주면 blank → null 정규화(합류 없음). 정상 조회 + 등록 장소 없음 = 빈 배열.
        expectCoord2address(coord2addressBody("서울 용산구 청파로20길 95", ""));
        expectKeywordSearch("서울 용산구 청파로20길 95", "{\"documents\":[]}");

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isEqualTo("서울 용산구 청파로20길 95");
        assertThat(geo.places()).isEmpty();
    }

    @Test
    void lookup_keepsApiOrder_skipsBlankNames_andPrependsBuildingName() {
        expectCoord2address(coord2addressBody("서울 용산구 청파로20길 95", "서울드래곤시티"));
        expectKeywordSearch("서울 용산구 청파로20길 95", """
                {"documents":[
                  {"id":"1","place_name":"가까운 카페"},
                  {"id":"2","place_name":""},
                  {"id":"3","place_name":"그랑씨엘"}
                ]}""");

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        // 단일 콜이라 응답 순서(sort=distance)를 그대로 신뢰한다. blank 이름은 제외, 건물명은 맨 앞.
        assertThat(geo.places()).containsExactly("서울드래곤시티", "가까운 카페", "그랑씨엘");
    }

    @Test
    void lookup_doesNotDuplicateBuildingName_whenAlreadyInPlaces() {
        // 건물 자체가 장소로도 등록된 경우(복합몰 등) 건물명이 두 번 들어가면 안 된다.
        expectCoord2address(coord2addressBody("경기 하남시 미사대로 750", "스타필드 하남"));
        expectKeywordSearch("경기 하남시 미사대로 750", """
                {"documents":[{"id":"1","place_name":"스타필드 하남"}]}""");

        assertThat(provider().lookup(LATITUDE, LONGITUDE).places()).containsExactly("스타필드 하남");
    }

    @Test
    void lookup_limitsPlacesToTen() {
        expectCoord2address(coord2addressBody("서울 용산구 청파로20길 95", null));
        StringBuilder documents = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0) {
                documents.append(',');
            }
            documents.append("{\"id\":\"").append(i).append("\",\"place_name\":\"장소").append(i).append("\"}");
        }
        expectKeywordSearch("서울 용산구 청파로20길 95", "{\"documents\":[" + documents + "]}");

        assertThat(provider().lookup(LATITUDE, LONGITUDE).places()).hasSize(10);
    }

    // ── 실패 계약: 전이적(5xx) → 재시도 후 실패 ──

    @Test
    void lookup_throwsRetryable_whenCoord2addressPersists5xx_afterRetries() {
        // 전이적 실패는 콜 단위로 MAX_ATTEMPTS(2)회 시도한다(최초 1 + 재시도 1). 끝내 실패하면 retryable=true로 던진다.
        // coord2address가 먼저라 keyword는 호출되지 않는다(strict short-circuit).
        server.expect(ExpectedCount.times(2), requestToUriTemplate(COORD2ADDRESS_URI_TEMPLATE, LONGITUDE, LATITUDE))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> provider().lookup(LATITUDE, LONGITUDE))
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isTrue());
        server.verify();
    }

    @Test
    void lookup_retriesFailedCallOnly_notEarlierSucceededCalls() {
        // 콜별 재시도 증명: coord2address 성공 후 keyword가 1회 실패→성공. 앞 성공 콜은 재실행되지 않는다
        // (coord2address는 1회만 기대 — 재시도가 lookup 전체가 아니라 실패한 콜에만 걸린다).
        expectCoord2address(coord2addressBody("서울 용산구 청파로20길 95", null));
        // keyword: 첫 요청 5xx(전이) → 재시도 → 두 번째 성공(순서 매칭이라 연속 2건).
        server.expect(requestToUriTemplate(KEYWORD_URI_TEMPLATE, "서울 용산구 청파로20길 95", LONGITUDE, LATITUDE))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        expectKeywordSearch("서울 용산구 청파로20길 95", """
                {"documents":[{"id":"3","place_name":"가까운 카페"}]}""");

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        // 재시도로 keyword 결과가 살아남는다.
        assertThat(geo.places()).containsExactly("가까운 카페");
        // 앞 성공 콜(coord2address)이 재실행되지 않았음을 once() 기대의 verify가 고정한다.
        server.verify();
    }

    // ── 실패 계약: 영구적(4xx) → 즉시 실패, 재시도 없음 ──

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429})
    void lookup_throwsNonRetryable_andDoesNotRetry_onPermanent4xx(int status) {
        // 영구적 실패(키·권한·쿼터)는 모든 콜에 걸리므로 재시도가 무의미 — 즉시 retryable=false로 던진다.
        // coord2address URI를 once()로 기대 → 재시도했다면 두 번째 요청이 매칭 실패해 verify가 깨진다.
        server.expect(ExpectedCount.once(), requestToUriTemplate(COORD2ADDRESS_URI_TEMPLATE, LONGITUDE, LATITUDE))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> provider().lookup(LATITUDE, LONGITUDE))
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isFalse());
        server.verify();
    }

    // ── 실패 계약: 비-HTTP 실패(파싱·null body·shape) → non-retryable로 감싸짐(raw 누수 아님) ──

    @Test
    void lookup_throwsNonRetryable_whenBodyIsNull() {
        // null body는 raw NPE로 새지 않고 non-retryable MapPlaceLookupException으로 감싸진다(→502).
        server.expect(ExpectedCount.once(), requestToUriTemplate(COORD2ADDRESS_URI_TEMPLATE, LONGITUDE, LATITUDE))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> provider().lookup(LATITUDE, LONGITUDE))
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isFalse());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{broken",                       // 파싱 실패(malformed JSON)
            "{\"foo\":1}",                   // documents 필드 누락
            "{\"documents\":\"nope\"}"       // documents가 배열 아님
    })
    void lookup_throwsNonRetryable_whenResponseShapeBroken(String body) {
        // 파싱 실패·shape 오류는 정상 빈 결과와 달리 깨진 응답 → non-retryable로 감싸 던진다(catch-all 500 방지).
        server.expect(ExpectedCount.once(), requestToUriTemplate(COORD2ADDRESS_URI_TEMPLATE, LONGITUDE, LATITUDE))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider().lookup(LATITUDE, LONGITUDE))
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isFalse());
        server.verify();
    }

    @Test
    void lookup_throwsNonRetryable_whenKeywordResponseShapeBroken() {
        // shape 검증은 콜 단위로 걸린다 — 두 번째 콜(keyword)의 깨진 응답도 같은 계약으로 던진다.
        expectCoord2address(coord2addressBody("서울 용산구 청파로20길 95", null));
        server.expect(ExpectedCount.once(),
                        requestToUriTemplate(KEYWORD_URI_TEMPLATE, "서울 용산구 청파로20길 95", LONGITUDE, LATITUDE))
                .andRespond(withSuccess("{\"foo\":1}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider().lookup(LATITUDE, LONGITUDE))
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isFalse());
        server.verify();
    }

    // ── helpers ──

    private void expectCoord2address(String body) {
        server.expect(requestToUriTemplate(COORD2ADDRESS_URI_TEMPLATE, LONGITUDE, LATITUDE))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectKeywordSearch(String query, String body) {
        server.expect(requestToUriTemplate(KEYWORD_URI_TEMPLATE, query, LONGITUDE, LATITUDE))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String coord2addressBody(String addressName, String buildingName) {
        String address = (addressName == null) ? "\"\"" : "\"" + addressName + "\"";
        String building = (buildingName == null) ? "\"\"" : "\"" + buildingName + "\"";
        return "{\"documents\":[{\"road_address\":{\"address_name\":" + address
                + ",\"building_name\":" + building + "}}]}";
    }
}
