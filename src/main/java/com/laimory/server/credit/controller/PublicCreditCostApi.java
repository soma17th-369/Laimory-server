package com.laimory.server.credit.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.credit.dto.CreditCostsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 공개 크레딧 비용 조회 API의 문서·계약(구현은 {@link PublicCreditCostController}, #555).
 *
 * <p>비용은 사용자와 무관한 서버 상수라 인증 없이 공개한다 — 앱은 비용을 하드코딩하지 않고 이 응답으로 "크레딧 N개
 * 사용"을 고지하므로, 비용을 바꿀 때 앱 강제 업데이트가 필요 없다. 잔액 조회는 인증 API {@link CreditApi}가 맡는다.
 */
@Tag(name = "Credit", description = "크레딧 잔액·비용")
@RequestMapping(ApiUrls.API_URL + "/credit")
public interface PublicCreditCostApi {

    @Operation(summary = "크레딧 비용 조회",
            description = "크레딧을 소비하는 작업별 비용을 반환한다. `timelineCreation`은 타임라인 1회 생성(AI 결과 "
                    + "저장 1회)에 드는 크레딧이며, 타임라인 draft 생성의 잔액 사전 검사(403 `-1021`)와 결과 저장 시 "
                    + "차감이 같은 값을 쓴다. 값은 서버 배포로만 바뀐다. 크레딧을 소비하는 기능이 늘면 필드가 추가된다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공", useReturnTypeSchema = true)
    })
    @GetMapping("/costs")
    ResponseEntity<ApiResponse<CreditCostsResponse>> getCosts(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion);
}
