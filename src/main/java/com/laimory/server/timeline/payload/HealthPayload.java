package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laimory.server.timeline.HealthMetric;

/**
 * 건강 아이템 payload. 지표당 아이템 하나 — 측정 구간은 envelope의 startAt/endAt이 담는다.
 * value의 단위는 {@link HealthMetric}이 결정한다(보/미터/분).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthPayload(
        HealthMetric metric,
        Double value
) implements TimelineItemPayload {
}
