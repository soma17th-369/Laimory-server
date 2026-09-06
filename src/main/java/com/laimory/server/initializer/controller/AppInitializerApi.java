package com.laimory.server.initializer.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.initializer.dto.InitializerResponse;
import com.laimory.server.user.CurrentSubject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 앱 초기화 조회 API의 문서·계약(구현은 {@link AppInitializerController}).
 *
 * <p>앱이 시작할 때 인증 사용자별 초기 상태를 한 번에 받는 자리다. 온보딩 완료의 owner는
 * {@code @CurrentSubject}가 JWT principal에서 해석한 subject이고, 약관 동의의 owner는 인증 회원 raw
 * {@code userId}라 두 hidden principal을 함께 받는다(#434 — 보호 operation 중 유일한 예외). 둘 다
 * 클라이언트 입력이 아니다.
 */
@Tag(name = "App Initializer", description = "앱 시작 시 필요한 사용자별 초기 상태")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/initializer")
public interface AppInitializerApi {

    @Operation(summary = "앱 초기화 상태 조회",
            description = "인증 사용자의 앱 시작 초기 상태를 반환한다. onboardingCompleted는 저장된 "
                    + "subject 설정 그대로이며 약관 동의 이력이나 기록 존재 여부로 계산하지 않는다. "
                    + "terms.agreementRequired는 지금 현재 버전 동의가 없는 동의 대상 약관 목록이다 — "
                    + "빈 배열이면 동의 절차 없이 진행하고, 비어 있지 않으면 앱이 동의 등록을 마치기 전까지 "
                    + "진행을 차단한다(차단은 클라이언트 책임 — 서버는 이 결과로 다른 요청을 막지 않는다). "
                    + "최초 동의와 재동의를 구분하지 않으며, 현재 유효 문서가 없는 종류는 판정에서 빠진다. "
                    + "조회는 값을 바꾸지 않는다(완료 전이는 온보딩 완료 API, 동의 기록은 동의 등록 API가 "
                    + "소유). 설정 행이 없는 사용자는 기본값으로 가리지 않고 500으로 실패한다(운영 신호 — "
                    + "푸시 설정 조회와 같은 정책).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @GetMapping
    ResponseEntity<ApiResponse<InitializerResponse>> getInitializer(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId);
}
