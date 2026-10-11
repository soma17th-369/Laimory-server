package com.laimory.server.appconfig;

/**
 * 앱 구동 설정 응답. 공유 Redis 캐시 값이기도 하다(#491) — record라 캐시 JSON을 canonical 생성자로 그대로
 * 역직렬화한다. 필드·클래스 이름을 바꾸면 캐시 값 shape 변경이므로 캐시 이름을 바꾼다(persistence.md).
 */
public record AppConfigResponse(
        Long minAppVersion,
        Long recommendAppVersion,
        String debugTestMessage
) {

    public static AppConfigResponse from(AppConfig config) {
        return new AppConfigResponse(
                config.getMinAppVersion(),
                config.getRecommendAppVersion(),
                config.getDebugTestMessage()
        );
    }
}
