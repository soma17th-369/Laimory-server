package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laimory.server.timeline.HealthMetric;

/**
 * 건강 아이템 payload. 지표당 아이템 하나 — 측정 구간은 envelope의 startAt/endAt이 담는다.
 *
 * <p>값 필드는 지표에 따라 갈린다(AI input 규격): {@code SLEEP}은 {@code durationMinutes}(분),
 * 그 외({@code STEPS} 보/{@code DISTANCE} 미터)는 {@code value}. 반대 필드는 null(NON_NULL 키 생략).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthPayload(
        HealthMetric metric,
        Double value,
        Double durationMinutes
) implements TimelineItemPayload {
}
