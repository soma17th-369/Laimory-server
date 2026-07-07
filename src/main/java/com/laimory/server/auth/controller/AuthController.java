package com.laimory.server.auth.controller;

import com.laimory.server.auth.dto.LogoutRequest;
import com.laimory.server.auth.dto.TokenIssueRequest;
import com.laimory.server.auth.dto.TokenRefreshRequest;
import com.laimory.server.auth.dto.TokenResponse;
import com.laimory.server.auth.service.AuthTokenService;
import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자체 토큰 발급/갱신/로그아웃 API. 인증 전 단계라 공개 prefix({@code /api})에 둔다 —
 * 보호는 인증 대신 일회용 app_code(+verifier)·refresh 원문 소지로 한다.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "Auth", description = "자체 토큰(access/refresh) 발급·갱신·로그아웃")
@RestController
@RequiredArgsConstructor
@RequestMapping(ApiUrls.API_URL + "/auth")
public class AuthController {

    private final AuthTokenService authTokenService;

    @Operation(summary = "app_code → 토큰 교환",
            description = "로그인 딥링크로 받은 일회용 appCode와 로그인 시작 시 생성해 둔 appVerifier를 제시해 "
                    + "access/refresh 토큰 쌍을 발급받는다. appCode는 이 호출에서 소비된다(재사용 불가).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "발급 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`ERROR_2002` — appCode 무효/만료/이미 소비/verifier 불일치(재로그인 필요)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping("/token")
    public ResponseEntity<ApiResponse<TokenResponse>> issueTokens(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @RequestBody TokenIssueRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                authTokenService.issueTokens(applicationVersion, request.appCode(), request.appVerifier())));
    }

    @Operation(summary = "토큰 갱신(회전)",
            description = "refresh 토큰으로 새 access/refresh 쌍을 발급받는다. 제시한 refresh는 즉시 무효화되므로 "
                    + "응답의 새 refresh로 교체 보관해야 한다. **동시 호출 금지 — 반드시 single-flight로 직렬화**"
                    + "(동시 refresh는 재사용 탐지로 전체 로그아웃될 수 있음).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "갱신 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`ERROR_2003` — refresh 무효/만료/철회/재사용 탐지(재로그인 필요)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @RequestBody TokenRefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                authTokenService.refresh(applicationVersion, request.refreshToken())));
    }

    @Operation(summary = "로그아웃",
            description = "해당 refresh 토큰을 폐기한다. 멱등 — 이미 폐기됐거나 모르는 토큰이어도 200. "
                    + "access는 서버에 저장되지 않으므로 만료(~15분)로 자연 소멸한다.")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @RequestBody LogoutRequest request) {
        authTokenService.logout(applicationVersion, request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
