package com.laimory.server.appconfig;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "App Config", description = "앱 구동 설정(공개)")
@RestController
@RequiredArgsConstructor
@RequestMapping(ApiUrls.API_URL)
public class AppConfigController {

    private final AppConfigService appConfigService;

    @Operation(summary = "인트로 설정 조회", description = "앱 시작 시 필요한 설정을 반환한다.")
    @GetMapping("/intro")
    public ResponseEntity<ApiResponse<AppConfigResponse>> intro(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion) {
        return ResponseEntity.ok(ApiResponse.success(appConfigService.getAppConfig(applicationVersion)));
    }
}
