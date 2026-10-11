package com.laimory.server.credit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 크레딧 비용 조회 응답({@code GET /api/{version}/credit/costs}). 앱은 비용을 하드코딩하지 않고 이 값을 고지한다.
 * 크레딧을 소비하는 기능이 늘면 같은 응답에 평면 필드를 추가한다.
 */
@Schema(description = "크레딧 비용 조회 응답")
public record CreditCostsResponse(
        @Schema(description = "타임라인 1회 생성에 드는 크레딧", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int timelineCreation) {
}
