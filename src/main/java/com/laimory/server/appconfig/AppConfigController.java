package com.laimory.server.appconfig;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class AppConfigController {

    private final AppConfigService appConfigService;

    @GetMapping("/intro")
    public ResponseEntity<AppConfigResponse> intro() {
        return ResponseEntity.ok(appConfigService.getAppConfig());
    }
}
