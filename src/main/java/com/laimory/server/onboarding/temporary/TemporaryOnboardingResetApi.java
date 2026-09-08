package com.laimory.server.onboarding.temporary;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 임시 테스트 API. 제거할 때 main/test의 onboarding/temporary 패키지를 함께 삭제한다. */
@Tag(name = "Temporary Onboarding Test", description = "임시 온보딩 테스트")
@RequestMapping(ApiUrls.API_URL + "/onboarding")
public interface TemporaryOnboardingResetApi {

    @Operation(summary = "[임시 테스트] 온보딩 완료 상태 초기화",
            description = "인증 없이 쿼리스트링 userId에 해당하는 사용자의 onboardingCompleted를 false로 "
                    + "바꾼다. request body는 없고 이미 false여도 성공한다. 별도 활성화 설정 없이 노출된다. "
                    + "사용자 subject 매핑이나 설정 행이 없으면 500으로 실패한다.")
    @PostMapping("/reset")
    ResponseEntity<ApiResponse<Void>> resetOnboarding(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(description = "초기화할 사용자 ID", example = "123")
            @RequestParam("userId") @Positive long userId);
}
