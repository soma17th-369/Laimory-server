package com.laimory.server.credit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 내 크레딧 조회 응답({@code GET /a/api/{version}/credit}). 잔액의 권위는 서버다 — 앱은 로컬 추정값이 아니라
 * 이 응답을 표시한다.
 */
@Schema(description = "크레딧 조회 응답")
public record CreditResponse(
        @Schema(description = "남은 크레딧", example = "60", requiredMode = Schema.RequiredMode.REQUIRED)
        int remainingCredits) {
}
