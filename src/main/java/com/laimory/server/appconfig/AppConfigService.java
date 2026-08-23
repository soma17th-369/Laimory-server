package com.laimory.server.appconfig;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppConfigService {

    private final AppConfigRepository appConfigRepository;

    public AppConfigResponse getAppConfig(String applicationVersion) {
        // applicationVersion: 버전별 config 분기 지점(현재 단일 버전이라 분기 없음).
        AppConfig config = appConfigRepository.findFirstBy()
                .orElseThrow(() -> new IllegalStateException("AppConfig not found"));
        return AppConfigResponse.from(config);
    }
}
