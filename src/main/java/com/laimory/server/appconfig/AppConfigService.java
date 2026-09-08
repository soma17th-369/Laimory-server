package com.laimory.server.appconfig;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppConfigService {

    private final AppConfigRepository appConfigRepository;

    @Transactional(readOnly = true)
    public AppConfigResponse getAppConfig(String applicationVersion) {
        // applicationVersion: 버전별 config 분기 지점(현재 단일 버전이라 분기 없음).
        return AppConfigResponse.from(requireSingleConfig());
    }

    @Transactional
    public AppConfigResponse updateVersions(Long minimum, Long recommended) {
        AppConfig config = requireSingleConfig();
        config.updateVersions(minimum, recommended);
        return AppConfigResponse.from(config);
    }

    private AppConfig requireSingleConfig() {
        List<AppConfig> rows = appConfigRepository.findTop2ByOrderByAppConfigIdAsc();
        if (rows.size() != 1) {
            throw new IllegalStateException("AppConfig must contain exactly one row");
        }
        return rows.getFirst();
    }
}
