package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 카카오 로컬 API 계약 검증(MockRestServiceServer — 실 HTTP 없음).
 * - 좌표 순서: 요청 파라미터는 x=경도, y=위도(내부 표현과 뒤집힘).
 * - blank 정규화: 카카오는 부재 필드를 빈 문자열로 주는 경우가 있어 null로 정규화한다.
 * - places 합산: 카테고리별 응답은 각자 거리순일 뿐이라 전역 병합 정렬 + id dedupe + top N.
 *   coord2address의 건물명은 places 맨 앞에 합류한다(별도 필드 없음).
 * - 실패 강등: 주소/장소 조회는 독립적으로 실패하고 해당 필드만 null(호출은 예외를 던지지 않는다).
 */
class GeocodingServiceTest {

    private static final double LATITUDE = 37.5340;
    private static final double LONGITUDE = 126.9668;
    private static final String[] CATEGORY_CODES = {"FD6", "CE7", "CT1", "AT4", "AD5"};

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private GeocodingService kakaoService() {
        return new GeocodingService("kakao", "test-key", builder);
    }

    @Test
    void rejectsUnknownMode_atConstruction() {
        // noop|kakao 외 값은 오타로 조용히 noop이 되는 것을 막기 위해 기동 실패.
        assertThatThrownBy(() -> new GeocodingService("kakaoo", "key", builder))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsKakaoModeWithoutKey_atConstruction() {
        assertThatThrownBy(() -> new GeocodingService("kakao", " ", builder))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noopMode_returnsEmptyWithoutHttpCall() {
        GeocodingService noop = new GeocodingService("noop", "", builder);
        server.expect(ExpectedCount.never(), requestToUriTemplate("{url}", "https://dapi.kakao.com"))
                .andRespond(withSuccess());

        assertThat(noop.lookup(LATITUDE, LONGITUDE)).isEqualTo(GeoPlace.EMPTY);
        server.verify();
    }

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

        GeoPlace geo = kakaoService().lookup(LATITUDE, LONGITUDE);

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

        GeoPlace geo = kakaoService().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isEqualTo("서울 용산구 청파로20길 95");
        assertThat(geo.places()).isEmpty();
    }

    @Test
    void lookup_fallsBackToLotAddress_whenRoadAddressMissing() {
        // 도로명주소는 건물에만 부여돼 도로 위 좌표(교차로 등)엔 없다 → 지번 주소(address)로 fallback.
        expectCoord2address("""
                {"documents":[{"road_address":null,"address":{"address_name":"서울 강남구 역삼동 858"}}]}""");
        expectAllCategorySearches("{\"documents\":[]}");

        GeoPlace geo = kakaoService().lookup(LATITUDE, LONGITUDE);

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

        GeoPlace geo = kakaoService().lookup(LATITUDE, LONGITUDE);

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

        assertThat(kakaoService().lookup(LATITUDE, LONGITUDE).places()).hasSize(10);
    }

    @Test
    void lookup_degradesAddressToNull_whenCoord2addressFails_butKeepsPlaces() {
        // 주소/장소 조회는 독립 실패 — coord2address 5xx여도 places는 살린다.
        server.expect(requestToUriTemplate("https://dapi.kakao.com/v2/local/geo/coord2address.json?x={x}&y={y}",
                LONGITUDE, LATITUDE)).andRespond(withServerError());
        expectCategorySearch("FD6", """
                {"documents":[{"id":"1","place_name":"그랑씨엘","distance":"40"}]}""");
        expectCategorySearch("CE7", "{\"documents\":[]}");
        expectCategorySearch("CT1", "{\"documents\":[]}");
        expectCategorySearch("AT4", "{\"documents\":[]}");
        expectCategorySearch("AD5", "{\"documents\":[]}");

        GeoPlace geo = kakaoService().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isNull();
        assertThat(geo.places()).containsExactly("그랑씨엘");
    }

    @Test
    void lookup_keepsBuildingNameOnly_whenCategorySearchFails() {
        expectCoord2address(coord2addressBody("서울 용산구 청파로20길 95", "서울드래곤시티"));
        // 카테고리 5콜 중 하나라도 실패하면 카테고리 결과는 버리지만, 건물명만은 살린다.
        expectCategorySearch("FD6", "{\"documents\":[]}");
        server.expect(requestToUriTemplate(
                        "https://dapi.kakao.com/v2/local/search/category.json"
                                + "?category_group_code={code}&x={x}&y={y}&radius=50&sort=distance",
                        "CE7", LONGITUDE, LATITUDE))
                .andRespond(withServerError());

        GeoPlace geo = kakaoService().lookup(LATITUDE, LONGITUDE);

        assertThat(geo.address()).isEqualTo("서울 용산구 청파로20길 95");
        assertThat(geo.places()).containsExactly("서울드래곤시티");
    }

    @Test
    void lookup_degradesPlacesToNull_whenCategorySearchFails_andNoBuildingName() {
        expectCoord2address(coord2addressBody("서울 용산구 청파로20길 95", null));
        expectCategorySearch("FD6", "{\"documents\":[]}");
        server.expect(requestToUriTemplate(
                        "https://dapi.kakao.com/v2/local/search/category.json"
                                + "?category_group_code={code}&x={x}&y={y}&radius=50&sort=distance",
                        "CE7", LONGITUDE, LATITUDE))
                .andRespond(withServerError());

        GeoPlace geo = kakaoService().lookup(LATITUDE, LONGITUDE);

        // 건물명도 없으면 places=null(조회 실패)로 남는다.
        assertThat(geo.places()).isNull();
    }

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
