package com.laimory.server.appconfig;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 앱 구동 설정 응답. 공유 Redis 캐시 값이기도 하다(#491) — 캐시 JSON 역직렬화에 기본 생성자가 필요해 private으로
 * 둔다. 필드·클래스 이름을 바꾸면 캐시 값 shape 변경이므로 캐시 이름을 바꾼다(persistence.md).
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AppConfigResponse {
    private Long minAppVersion;
    private Long recommendAppVersion;
    private String debugTestMessage;

    public static AppConfigResponse from(AppConfig config) {
        return new AppConfigResponse(
                config.getMinAppVersion(),
                config.getRecommendAppVersion(),
                config.getDebugTestMessage()
        );
    }
}
