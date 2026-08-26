package com.laimory.server.initializer.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.initializer.dto.InitializerResponse;
import com.laimory.server.terms.LoginTermsExempt;
import com.laimory.server.user.CurrentSubject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 앱 초기화 조회 API의 문서·계약(구현은 {@link AppInitializerController}).
 *
 * <p>앱이 시작할 때 인증 사용자별 초기 상태를 한 번에 받는 자리다. 상태의 owner는 {@code @CurrentSubject}가
 * JWT principal에서 해석한 subject이며 클라이언트 입력이 아니다.
 *
 * <p>{@link LoginTermsExempt}를 붙인다 — 약관에 아직 동의하지 않은 사용자도 이 값을 읽어 시작 화면을
 * 분기해야 한다. bearer 인증과 {@code ACTIVE} 회원 검사는 그대로 요구한다.
 */
@Tag(name = "App Initializer", description = "앱 시작 시 필요한 사용자별 초기 상태")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/initializer")
public interface AppInitializerApi {

    @Operation(summary = "앱 초기화 상태 조회",
            description = "인증 subject의 저장된 온보딩 완료 여부를 반환한다. 값의 단일 권위는 저장된 "
                    + "subject 설정이며 약관 동의 이력이나 기록 존재 여부로 계산하지 않는다. 조회는 값을 "
                    + "바꾸지 않는다(완료 전이는 온보딩 완료 API가 소유). 설정 행이 없는 사용자는 기본값으로 "
                    + "가리지 않고 500으로 실패한다(운영 신호 — 푸시 설정 조회와 같은 정책).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @GetMapping
    @LoginTermsExempt
    ResponseEntity<ApiResponse<InitializerResponse>> getInitializer(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId);
}
