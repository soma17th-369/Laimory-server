package com.laimory.server.common;

/**
 * API 경로 prefix 상수. 호출 주체별 prefix + 버전 세그먼트(정규식 제약)로 구성한다.
 *
 * <p>{@code *_URL} 상수는 컨트롤러 클래스 레벨 {@code @RequestMapping}용이다(버전이 정규식 path variable).
 * 버전은 컨트롤러가 {@code @PathVariable}로 받아 Service에 넘기고, 버전별 동작 분기는 Service 계층이 해결한다.
 */
public final class ApiUrls {

    private ApiUrls() {
    }

    private static final String API = "/api";
    private static final String SERVER_API = "/s/api";

    /**
     * 사용자 인증 영역 prefix. SecurityConfig matcher와 JWT 필터가 재사용한다 —
     * 보호 대상은 정확히 이 prefix와 하위 경로({@code /**})뿐이다({@code /a/apiary} 같은 문자열 prefix 미매칭).
     */
    public static final String AUTHENTICATED_API_PREFIX = "/a/api";

    /** 버전 path variable 세그먼트(정규식 제약). 다른 버전 형식을 쓰려면 여기 한 곳만 바꾼다. */
    public static final String VERSION = "{applicationVersion:v\\d+}";

    /** 일반(공개) 요청. */
    public static final String API_URL = API + "/" + VERSION;

    /** 서버간 통신(엔드포인트별 자체 인증; 예: AI 콜백은 task별 one-time Callback-Token 검증). */
    public static final String SERVER_API_URL = SERVER_API + "/" + VERSION;

    /** 사용자 인증이 필요한 요청 — 유효한 자체 access JWT(Bearer) 없이는 401 {@code ERROR_2001}로 거절된다. */
    public static final String AUTHENTICATED_API_URL = AUTHENTICATED_API_PREFIX + "/" + VERSION;
}
