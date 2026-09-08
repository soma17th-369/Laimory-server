package com.laimory.server.onboarding.temporary;

import com.laimory.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 인증 없는 임시 테스트 구현. 이 패키지만 삭제하면 API와 DB writer가 함께 제거된다. */
@RestController
@RequiredArgsConstructor
public class TemporaryOnboardingResetController implements TemporaryOnboardingResetApi {

    private final TemporaryOnboardingResetService temporaryOnboardingResetService;

    @Override
    public ResponseEntity<ApiResponse<Void>> resetOnboarding(String applicationVersion, long userId) {
        temporaryOnboardingResetService.resetOnboarding(applicationVersion, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
