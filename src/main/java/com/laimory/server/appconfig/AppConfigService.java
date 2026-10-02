package com.laimory.server.appconfig;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppConfigService {

    private final AppConfigRepository appConfigRepository;

    public AppConfigResponse getAppConfig(String applicationVersion) {
        // applicationVersion: 버전별 config 분기 지점(현재 단일 버전이라 분기 없음).
        return AppConfigResponse.from(requireSingleConfig());
    }

    /** 관리자 쓰기 — 결과는 반환하지 않는다. 관리자 웹이 저장 직후 다시 조회한다(조회가 단일 원천, #528). */
    @Transactional
    public void updateVersions(Long minimum, Long recommended) {
        requireSingleConfig().updateVersions(minimum, recommended);
    }

    private AppConfig requireSingleConfig() {
        List<AppConfig> rows = appConfigRepository.findTop2ByOrderByAppConfigIdAsc();
        if (rows.size() != 1) {
            throw new IllegalStateException("AppConfig must contain exactly one row");
        }
        return rows.getFirst();
    }
}
