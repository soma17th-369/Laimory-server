package com.laimory.server.appconfig;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
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
