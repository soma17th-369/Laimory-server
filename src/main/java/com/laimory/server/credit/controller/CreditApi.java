package com.laimory.server.credit.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.credit.dto.CreditResponse;
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
 * 크레딧 API의 문서·계약(구현은 {@link CreditController}).
 *
 * <p>잔액의 owner는 {@code @CurrentSubject}가 JWT principal에서 해석한 subject이며 클라이언트 입력이 아니다.
 */
@Tag(name = "Credit", description = "크레딧 잔액")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping(ApiUrls.AUTHENTICATED_API_URL + "/credit")
public interface CreditApi {

    @Operation(summary = "내 크레딧 조회",
            description = "남은 크레딧을 반환한다. 가입 시 60을 한 번 지급하며 충전 경로는 없다. 타임라인은 AI "
                    + "결과가 저장될 때 1 차감된다(결과가 저장되지 않은 생성은 차감 없음 — draft 응답이 502였거나 "
                    + "폴링이 끝나기 전에 작업이 만료돼도 결과가 저장됐다면 차감된다). 잔액이 0이면 타임라인 draft "
                    + "생성이 403 `-1021`로 거절된다. 행 생성은 가입 transaction과 rollout backfill이 소유하며, "
                    + "행이 없는 사용자는 기본값으로 가리지 않고 500으로 실패한다(운영 신호).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-2001` — 인증 필요(Bearer access token 부재/무효/만료)")
    })
    @GetMapping
    ResponseEntity<ApiResponse<CreditResponse>> getCredit(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(hidden = true) @CurrentSubject UUID subjectId);
}
