package com.laimory.server.appconfig;

import com.laimory.server.common.ApiUrls;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(ApiUrls.API_URL)
public class AppConfigController {

    private final AppConfigService appConfigService;

    @GetMapping("/intro")
    public ResponseEntity<AppConfigResponse> intro(@PathVariable String applicationVersion) {
        return ResponseEntity.ok(appConfigService.getAppConfig(applicationVersion));
    }
}
