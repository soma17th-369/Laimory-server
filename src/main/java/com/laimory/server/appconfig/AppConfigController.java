package com.laimory.server.appconfig;

import com.laimory.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 구동 설정(공개) API 구현. HTTP 문서·계약은 {@link AppConfigApi}.
 */
@RestController
@RequiredArgsConstructor
public class AppConfigController implements AppConfigApi {

    private final AppConfigService appConfigService;

    @Override
    public ResponseEntity<ApiResponse<AppConfigResponse>> intro(String applicationVersion) {
        return ResponseEntity.ok(ApiResponse.success(appConfigService.getAppConfig(applicationVersion)));
    }
}
