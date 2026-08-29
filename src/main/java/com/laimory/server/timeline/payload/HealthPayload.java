package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laimory.server.timeline.HealthMetric;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 걸음 수 HEALTH 아이템 payload. 측정 구간은 envelope의 startAt/endAt이 담는다.
 * value는 단위가 포함된 텍스트다(예: "8500보") — 서버는 파싱하지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthPayload(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "건강 지표 종류. STEPS만 허용합니다.")
        HealthMetric metric,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "8500보",
                description = "단위가 포함된 걸음 수 텍스트 값(서버 미파싱). 예: \"8500보\".")
        String value
) implements TimelineItemPayload {
}
