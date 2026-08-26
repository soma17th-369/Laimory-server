package com.laimory.server.onboarding.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.onboarding.service.OnboardingService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 온보딩 완료 기록 API 구현. HTTP 문서·계약은 {@link OnboardingApi}.
 *
 * <p>subjectId는 클라이언트 값이 아니라 {@code @CurrentSubject}가 JWT principal을 해석한 결과다.
 */
@RestController
@RequiredArgsConstructor
public class OnboardingController implements OnboardingApi {

    private final OnboardingService onboardingService;

    @Override
    public ResponseEntity<ApiResponse<Void>> completeOnboarding(String applicationVersion, UUID subjectId) {
        onboardingService.completeOnboarding(applicationVersion, subjectId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
