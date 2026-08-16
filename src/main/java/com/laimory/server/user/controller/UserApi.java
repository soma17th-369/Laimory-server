package com.laimory.server.user.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.user.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 회원 정보 API의 문서·계약(구현은 {@link UserController}). 토큰 응답과 분리된 인증 회원 본인 조회다.
 *
 * <p>콘텐츠·push API와 달리 회원 account 도메인이라 subject 변환 없이 JWT 인증 principal인
 * raw {@code Long userId}를 그대로 받는다 — 클라이언트 입력이 아니므로 OpenAPI에는 노출하지 않는다.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "User", description = "회원 정보 — 인증 사용자 본인의 회원 정보 조회")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/users")
public interface UserApi {

    @Operation(summary = "내 회원 정보 조회",
            description = "인증 사용자 본인의 회원 정보를 조회한다. 현재 필드는 nickname 하나이며 nullable이다 — "
                    + "값이 없으면 key 생략 없이 JSON null로 반환한다. 다른 회원을 선택하는 parameter는 없다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공 — `body.nickname`은 nullable(값이 없으면 명시적 JSON null)",
                    useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료 — 유효 토큰의 회원 행 "
                            + "없음도 같은 응답으로 수렴해 존재 여부를 노출하지 않음)")
    })
    @GetMapping("/me")
    ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId);
}
