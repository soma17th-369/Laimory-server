package com.laimory.server.appconfig;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppConfigService {

    private final AppConfigRepository appConfigRepository;

    public AppConfigResponse getAppConfig() {
        AppConfig config = appConfigRepository.findFirstBy()
                .orElseThrow(() -> new IllegalStateException("AppConfig not found"));
        return AppConfigResponse.from(config);
    }
}
