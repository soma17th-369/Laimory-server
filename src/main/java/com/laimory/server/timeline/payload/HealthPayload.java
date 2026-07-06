package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laimory.server.timeline.HealthMetric;

/**
 * 건강 아이템 payload. 지표당 아이템 하나 — 측정 구간은 envelope의 startAt/endAt이 담는다.
 * value는 단위가 포함된 텍스트다(예: "100보", "140분") — 서버는 파싱하지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthPayload(
        HealthMetric metric,
        String value
) implements TimelineItemPayload {
}
