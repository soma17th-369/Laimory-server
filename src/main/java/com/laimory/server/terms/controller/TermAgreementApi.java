package com.laimory.server.terms.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.terms.LoginTermsExempt;
import com.laimory.server.terms.dto.TermAgreementCreateRequest;
import com.laimory.server.terms.dto.TermAgreementHistoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 약관 동의 API의 문서·계약(구현은 {@link TermAgreementController}). 공개 조회는 {@link PublicTermApi}로
 * 분리한다 — public/보호 method를 한 interface에 섞지 않는다.
 *
 * <p>회원 account 도메인이라 콘텐츠 subject 변환 없이 JWT 인증 principal인 raw {@code Long userId}를
 * 그대로 받는다({@code UserApi} 선례) — 클라이언트 입력이 아니므로 OpenAPI에는 노출하지 않는다.
 *
 * <p>두 operation 모두 {@link LoginTermsExempt}다 — 동의를 완료하는 경로 자체가 LOGIN gate에 막히면
 * 미동의 상태를 벗어날 수 없다. bearer 인증(401)은 그대로 요구된다.
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "Term Agreement", description = "약관 동의 — 동의 일괄 등록과 내 동의 이력 조회")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/terms/agreements")
public interface TermAgreementApi {

    @Operation(summary = "약관 동의 일괄 등록",
            description = "현재 유효 약관 조회 응답의 (termType, version)을 그대로 회신해 동의를 기록한다. "
                    + "수락 시각은 서버가 기록한다(클라이언트 입력 아님). all-or-nothing이다 — 하나라도 "
                    + "현재 버전이 아니면 아무것도 기록하지 않고 409를 반환하므로 현재 약관을 다시 조회해 "
                    + "재시도한다. 같은 버전 재동의는 멱등 성공이며 최초 수락 시각을 덮어쓰지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "등록 성공(body 없음 — 이미 동의한 버전 재전송도 200)", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — agreements 누락/빈 배열, 항목의 termType/version 누락, "
                            + "동일 (termType, version) 중복, 미지원 termType literal"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-3002` — 존재하지 않거나 현재 유효 버전이 아닌 (termType, version) 포함 "
                            + "(개정 직후의 stale 버전 포함 — 전체 미기록, 현재 약관 재조회 필요)")
    })
    @PostMapping
    @LoginTermsExempt
    ResponseEntity<ApiResponse<Void>> agreeToTerms(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId,
            @RequestBody TermAgreementCreateRequest request);

    @Operation(summary = "내 동의 이력 조회",
            description = "인증 회원에게 남아 있는 전체 동의 이력을 최신 수락 순으로 반환한다. 약관 문서 "
                    + "버전이 불변이므로 각 항목이 \"언제 어떤 버전에 동의했는지\"의 권위 기록이다. "
                    + "동의 당시 원문은 항목의 contentUrl(그 버전의 불변 page)이 재현한다. "
                    + "이력이 없으면 404가 아니라 빈 배열이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공 — 이력이 없으면 `body.agreements`는 빈 배열", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @GetMapping
    @LoginTermsExempt
    ResponseEntity<ApiResponse<TermAgreementHistoryResponse>> getMyAgreements(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType = true) Long userId);
}
