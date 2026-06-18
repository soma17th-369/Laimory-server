package com.laimory.server.common;

/**
 * API 경로 prefix 상수. 호출 주체별 prefix + 버전 세그먼트(정규식 제약)로 구성한다.
 *
 * <p>컨트롤러 클래스 레벨 {@code @RequestMapping}에 사용한다. 버전은 컨트롤러가 {@code @PathVariable}로 받아
 * Service에 그대로 넘기고, 버전별 동작 분기는 Service 계층이 해결한다(CLAUDE.md API 버저닝 규칙).
 */
public final class ApiUrls {

    private ApiUrls() {
    }

    /** 버전 path variable 세그먼트(정규식 제약). 다른 버전 형식을 쓰려면 여기 한 곳만 바꾼다. */
    public static final String VERSION = "{applicationVersion:v\\d+}";

    /** 일반(공개) 요청. */
    public static final String API_URL = "/api/" + VERSION;

    /** 서버간 통신(공유 secret 헤더로 보호). */
    public static final String SERVER_API_URL = "/s/api/" + VERSION;

    /** 사용자 인증이 필요한 요청(사용자 도입 시 사용). */
    public static final String AUTHENTICATED_API_URL = "/a/api/" + VERSION;
}
