package com.laimory.server.initializer.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.initializer.dto.InitializerResponse;
import com.laimory.server.initializer.service.AppInitializerService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 초기화 조회 API 구현. HTTP 문서·계약은 {@link AppInitializerApi}.
 *
 * <p>subjectId는 클라이언트 값이 아니라 {@code @CurrentSubject}가 JWT principal을 해석한 결과다.
 */
@RestController
@RequiredArgsConstructor
public class AppInitializerController implements AppInitializerApi {

    private final AppInitializerService appInitializerService;

    @Override
    public ResponseEntity<ApiResponse<InitializerResponse>> getInitializer(
            String applicationVersion, UUID subjectId) {
        return ResponseEntity.ok(ApiResponse.success(
                appInitializerService.getInitialState(applicationVersion, subjectId)));
    }
}
