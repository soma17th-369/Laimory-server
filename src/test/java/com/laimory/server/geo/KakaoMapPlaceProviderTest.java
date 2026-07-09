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
 * <p>정상 계약(GeocodingService에서 이관):
 * <ul>
 *   <li>좌표 순서: 요청 파라미터는 x=경도, y=위도(내부 표현과 뒤집힘).
 *   <li>blank 정규화: 카카오는 부재 필드를 빈 문자열로 주는 경우가 있어 null로 정규화한다.
 *   <li>places 합산: 카테고리별 응답은 각자 거리순일 뿐이라 전역 병합 정렬 + id dedupe + top N.
 *       coord2address의 건물명은 places 맨 앞에 합류한다(별도 필드 없음).
 * </ul>
 *
 * <p>실패 계약(loud fail A): 조용한 degrade 없음 — 6콜 중 하나라도 최종 실패하면
 * {@link MapPlaceLookupException}을 던진다. 전이적(5xx) 실패는 콜 단위로 재시도하고(앞 성공 콜은 재실행 안 됨),
 * 영구적(4xx·파싱·shape) 실패는 즉시 던진다. 빈 결과({@code {"documents":[]}})는 실패가 아니다.
 */
class KakaoMapPlaceProviderTest {

    private static final double LATITUDE = 37.5340;
    private static final double LONGITUDE = 126.9668;
    private static final String[] CATEGORY_CODES = {"FD6", "CE7", "CT1", "AT4", "AD5"};

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private KakaoMapPlaceProvider provider() {
        return new KakaoMapPlaceProvider("test-key", builder);
    }

    // ── 정상 계약(이관) ──

    @Test
    void lookup_sendsLongitudeAsX_andLatitudeAsY_withKakaoAkHeader() {
        // 좌표 순서 회귀 방지: x=경도, y=위도.
        server.expect(requestToUriTemplate("https://dapi.kakao.com/v2/local/geo/coord2address.json?x={x}&y={y}",
                        LONGITUDE, LATITUDE))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess(coord2addressBody("서울 용산구 청파로20길 95", "서울드래곤시티"),
                        MediaType.APPLICATION_JSON));
        for (String code : CATEGORY_CODES) {
            server.expect(requestToUriTemplate(
                            "https://dapi.kakao.com/v2/local/search/category.json"
                                    + "?category_group_code={code}&x={x}&y={y}&radius=50&sort=distance",
                            code, LONGITUDE, LATITUDE))
                    .andExpect(queryParam("x", String.valueOf(LONGITUDE)))
                    .andExpect(queryParam("y", String.valueOf(LATITUDE)))
                    .andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));
        }

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isEqualTo("서울 용산구 청파로20길 95");
        // 건물명은 별도 필드 없이 places 맨 앞에 합류한다.
        assertThat(geo.places()).containsExactly("서울드래곤시티");
        server.verify();
    }

    @Test
    void lookup_normalizesBlankFieldsToNull_andEmptyPlacesStayEmpty() {
        // 카카오가 building_name=""로 주면 blank → null 정규화(합류 없음). 정상 조회 + 주변 없음 = 빈 배열.
        expectCoord2address(coord2addressBody("서울 용산구 청파로20길 95", ""));
        expectAllCategorySearches("{\"documents\":[]}");

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isEqualTo("서울 용산구 청파로20길 95");
        assertThat(geo.places()).isEmpty();
    }

    @Test
    void lookup_returnsEmptyAddress_whenDocumentsEmpty_notFailure() {
        // {"documents":[]}는 정상 빈 결과(주소 부재) — 실패가 아니다. 좌표에 주소가 안 붙는 도로 위 등.
        expectCoord2address("{\"documents\":[]}");
        expectAllCategorySearches("{\"documents\":[]}");

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isNull();
        assertThat(geo.places()).isEmpty();
    }

    @Test
    void lookup_fallsBackToLotAddress_whenRoadAddressMissing() {
        // 도로명주소는 건물에만 부여돼 도로 위 좌표(교차로 등)엔 없다 → 지번 주소(address)로 fallback.
        expectCoord2address("""
                {"documents":[{"road_address":null,"address":{"address_name":"서울 강남구 역삼동 858"}}]}""");
        expectAllCategorySearches("{\"documents\":[]}");

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isEqualTo("서울 강남구 역삼동 858");
    }

    @Test
    void lookup_mergesPlacesAcrossCategories_sortsByDistance_dedupesById_andPrependsBuildingName() {
        expectCoord2address(coord2addressBody("서울 용산구 청파로20길 95", "서울드래곤시티"));
        // FD6: 먼 장소 + id 중복 대상 + distance 파싱 불가 장소.
        expectCategorySearch("FD6", """
                {"documents":[
                  {"id":"1","place_name":"그랑씨엘","distance":"40"},
                  {"id":"2","place_name":"먼 식당","distance":"48"},
                  {"id":"9","place_name":"거리없음","distance":""}
                ]}""");
        // CE7: 더 가까운 장소 — 전역 정렬이면 FD6 결과보다 앞서야 한다. id=1은 dedupe.
        expectCategorySearch("CE7", """
                {"documents":[
                  {"id":"3","place_name":"가까운 카페","distance":"5"},
                  {"id":"1","place_name":"그랑씨엘","distance":"40"}
                ]}""");
        expectCategorySearch("CT1", "{\"documents\":[]}");
        expectCategorySearch("AT4", "{\"documents\":[]}");
        expectCategorySearch("AD5", "{\"documents\":[]}");

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        // 건물명 선두 + 전역 거리순 + id dedupe + distance 파싱 불가 제외.
        assertThat(geo.places()).containsExactly("서울드래곤시티", "가까운 카페", "그랑씨엘", "먼 식당");
    }

    @Test
    void lookup_limitsPlacesToTen() {
        expectCoord2address(coord2addressBody(null, null));
        StringBuilder documents = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0) {
                documents.append(',');
            }
            documents.append("{\"id\":\"").append(i).append("\",\"place_name\":\"장소").append(i)
                    .append("\",\"distance\":\"").append(i).append("\"}");
        }
        expectCategorySearch("FD6", "{\"documents\":[" + documents + "]}");
        expectCategorySearch("CE7", "{\"documents\":[]}");
        expectCategorySearch("CT1", "{\"documents\":[]}");
        expectCategorySearch("AT4", "{\"documents\":[]}");
        expectCategorySearch("AD5", "{\"documents\":[]}");

        assertThat(provider().lookup(LATITUDE, LONGITUDE).places()).hasSize(10);
    }

    // ── 실패 계약: 전이적(5xx) → 재시도 후 실패 ──

    @Test
    void lookup_throwsRetryable_whenCoord2addressPersists5xx_afterRetries() {
        // 전이적 실패는 콜 단위로 MAX_ATTEMPTS(3)회 재시도한다. 끝내 실패하면 retryable=true로 던진다.
        // coord2address가 먼저라 카테고리는 호출되지 않는다(strict short-circuit).
        server.expect(ExpectedCount.times(3),
                        requestToUriTemplate("https://dapi.kakao.com/v2/local/geo/coord2address.json?x={x}&y={y}",
                                LONGITUDE, LATITUDE))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> provider().lookup(LATITUDE, LONGITUDE))
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isTrue());
        server.verify();
    }

    @Test
    void lookup_retriesFailedCallOnly_notEarlierSucceededCalls() {
        // [P1-a] 콜별 재시도 증명: coord2address·FD6 성공 후 CE7이 1회 실패→성공. 앞 성공 콜은 재실행되지 않는다
        // (coord2address·FD6는 각 1회만 기대 — 재시도가 lookup 전체가 아니라 실패한 콜에만 걸린다).
        expectCoord2address(coord2addressBody("서울 용산구 청파로20길 95", null));
        expectCategorySearch("FD6", """
                {"documents":[{"id":"1","place_name":"그랑씨엘","distance":"40"}]}""");
        // CE7: 첫 요청 5xx(전이) → 재시도 → 두 번째 성공(순서 매칭이라 연속 2건).
        server.expect(requestToUriTemplate(
                        "https://dapi.kakao.com/v2/local/search/category.json"
                                + "?category_group_code={code}&x={x}&y={y}&radius=50&sort=distance",
                        "CE7", LONGITUDE, LATITUDE))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        expectCategorySearch("CE7", """
                {"documents":[{"id":"3","place_name":"가까운 카페","distance":"5"}]}""");
        expectCategorySearch("CT1", "{\"documents\":[]}");
        expectCategorySearch("AT4", "{\"documents\":[]}");
        expectCategorySearch("AD5", "{\"documents\":[]}");

        GeoPlace geo = provider().lookup(LATITUDE, LONGITUDE);

        // 재시도로 CE7 결과가 살아 전역 거리순 병합된다.
        assertThat(geo.places()).containsExactly("가까운 카페", "그랑씨엘");
        // 앞 성공 콜(coord2address·FD6)이 재실행되지 않았음을 각 once() 기대의 verify가 고정한다.
        server.verify();
    }

    // ── 실패 계약: 영구적(4xx) → 즉시 실패, 재시도 없음 ──

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429})
    void lookup_throwsNonRetryable_andDoesNotRetry_onPermanent4xx(int status) {
        // 영구적 실패(키·권한·쿼터)는 모든 콜에 걸리므로 재시도가 무의미 — 즉시 retryable=false로 던진다.
        // coord2address URI를 once()로 기대 → 재시도했다면 두 번째 요청이 매칭 실패해 verify가 깨진다.
        server.expect(ExpectedCount.once(),
                        requestToUriTemplate("https://dapi.kakao.com/v2/local/geo/coord2address.json?x={x}&y={y}",
                                LONGITUDE, LATITUDE))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> provider().lookup(LATITUDE, LONGITUDE))
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isFalse());
        server.verify();
    }

    // ── 실패 계약: 비-HTTP 실패(파싱·null body·shape) → non-retryable로 감싸짐(raw 누수 아님) ──

    @Test
    void lookup_throwsNonRetryable_whenBodyIsNull() {
        // [P2-b] null body는 raw NPE로 새지 않고 non-retryable MapPlaceLookupException으로 감싸진다(→502).
        server.expect(ExpectedCount.once(),
                        requestToUriTemplate("https://dapi.kakao.com/v2/local/geo/coord2address.json?x={x}&y={y}",
                                LONGITUDE, LATITUDE))
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
        // [P2-b/신규] 파싱 실패·shape 오류는 정상 빈 결과와 달리 깨진 응답 → non-retryable로 감싸 던진다(catch-all 500 방지).
        server.expect(ExpectedCount.once(),
                        requestToUriTemplate("https://dapi.kakao.com/v2/local/geo/coord2address.json?x={x}&y={y}",
                                LONGITUDE, LATITUDE))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider().lookup(LATITUDE, LONGITUDE))
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> assertThat(e.isRetryable()).isFalse());
        server.verify();
    }

    // ── helpers (GeocodingServiceTest에서 이관) ──

    private void expectCoord2address(String body) {
        server.expect(requestToUriTemplate("https://dapi.kakao.com/v2/local/geo/coord2address.json?x={x}&y={y}",
                LONGITUDE, LATITUDE)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectCategorySearch(String categoryGroupCode, String body) {
        server.expect(requestToUriTemplate(
                        "https://dapi.kakao.com/v2/local/search/category.json"
                                + "?category_group_code={code}&x={x}&y={y}&radius=50&sort=distance",
                        categoryGroupCode, LONGITUDE, LATITUDE))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectAllCategorySearches(String body) {
        for (String code : CATEGORY_CODES) {
            expectCategorySearch(code, body);
        }
    }

    private static String coord2addressBody(String addressName, String buildingName) {
        if (addressName == null && buildingName == null) {
            return "{\"documents\":[{\"road_address\":null}]}";
        }
        String address = (addressName == null) ? "\"\"" : "\"" + addressName + "\"";
        String building = (buildingName == null) ? "\"\"" : "\"" + buildingName + "\"";
        return "{\"documents\":[{\"road_address\":{\"address_name\":" + address
                + ",\"building_name\":" + building + "}}]}";
    }
}
