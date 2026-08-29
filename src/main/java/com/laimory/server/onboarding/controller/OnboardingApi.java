package com.laimory.server.onboarding.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.terms.LoginTermsExempt;
import com.laimory.server.user.CurrentSubject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 앱 온보딩 완료 기록 API의 문서·계약(구현은 {@link OnboardingController}).
 *
 * <p>{@code GET /initializer}가 읽는 값을 바꾸는 유일한 client-facing 경로다. 완료는 단방향이라
 * {@code false}로 되돌리는 짝을 두지 않으며, 상태의 owner는 {@code @CurrentSubject}가 JWT principal에서
 * 해석한 subject이고 클라이언트 입력이 아니다.
 *
 * <p>{@link LoginTermsExempt}를 붙인다 — 앱 온보딩은 약관 동의와 독립된 절차라 미동의 상태에서도 완료를
 * 기록할 수 있어야 한다. bearer 인증과 {@code ACTIVE} 회원 검사는 그대로 요구한다.
 */
@Tag(name = "Onboarding", description = "앱 온보딩 완료 기록")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/onboarding")
public interface OnboardingApi {

    @Operation(summary = "앱 온보딩 완료 기록",
            description = "인증 subject의 온보딩 완료 여부를 `true`로 기록한다. request body는 없다 — "
                    + "대상은 언제나 인증 subject 자신이고 바꿀 값도 하나뿐이다. 이미 완료한 subject의 "
                    + "반복 호출도 같은 200으로 멱등 성공한다(재시도 안전). `false`로 되돌리는 API는 "
                    + "제공하지 않으며, 약관 동의 이력은 이 값을 바꾸지 않는다. 설정 행이 없는 사용자는 "
                    + "행을 만들지 않고 500으로 실패한다(운영 신호 — 조회와 같은 정책).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "기록 성공(body 없음)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @PostMapping("/complete")
    @LoginTermsExempt
    ResponseEntity<ApiResponse<Void>> completeOnboarding(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId);
}
