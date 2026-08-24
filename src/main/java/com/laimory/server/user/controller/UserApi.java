package com.laimory.server.user.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.terms.LoginTermsExempt;
import com.laimory.server.user.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 회원 정보 API의 문서·계약(구현은 {@link UserController}). 토큰 응답과 분리된 인증 회원 본인 조회·탈퇴다.
 *
 * <p>콘텐츠·push API와 달리 회원 account 도메인이라 subject 변환 없이 JWT 인증 principal인
 * raw {@code Long userId}를 그대로 받는다 — 클라이언트 입력이 아니므로 OpenAPI에는 노출하지 않는다.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "User", description = "회원 정보 — 인증 사용자 본인의 회원 정보 조회")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/user")
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
    @GetMapping
    // LOGIN 약관 gate exemption(#303): 계정 확인은 동의 전에도 가능해야 한다(bearer 인증은 그대로 요구).
    @LoginTermsExempt
    ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId);

    @Operation(summary = "회원 탈퇴",
            description = "인증 회원 본인의 탈퇴를 접수한다(#305). 202는 회원 비활성화(이후 이 회원의 모든 "
                    + "`/a/api` 접근과 token/refresh 발급 차단), 기존 refresh 전량 폐기, push 등록 삭제, "
                    + "개인정보 삭제 작업의 durable 접수가 commit됐다는 뜻이며 데이터 물리 삭제 완료를 뜻하지 "
                    + "않는다(후속 worker 책임). request body는 없다 — 유효한 bearer 인증이 본인 확인 수단이다. "
                    + "이미 인증을 통과한 동시 탈퇴 요청은 같은 202로 멱등 수렴하고, 접수 commit 뒤 같은 access "
                    + "token의 새 요청은 401이다(앱은 이를 이미 탈퇴 처리된 terminal 결과로 취급). 같은 소셜 "
                    + "계정의 다음 로그인은 과거 데이터·약관 동의와 연결되지 않는 완전히 새로운 가입이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
                    description = "탈퇴 접수 완료(body=null) — 논리 탈퇴·credential 차단·삭제 작업 접수가 "
                            + "commit됨(물리 삭제 완료 아님)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료 — 이미 탈퇴·최종 "
                            + "삭제된 회원도 같은 응답으로 수렴해 존재 여부를 노출하지 않음)")
    })
    @DeleteMapping
    // LOGIN 약관 gate exemption(#303): 미동의 사용자도 탈퇴할 수 있어야 한다(bearer 인증·ACTIVE 검사는 그대로).
    @LoginTermsExempt
    ResponseEntity<ApiResponse<Void>> withdraw(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId);
}
