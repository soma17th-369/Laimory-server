package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * AI 이벤트 생성 범위의 HTTP 표현. 클라이언트가 계산한 범위를 서버가 계산·보정 없이 Redis task로
 * pass-through한다(값의 권위는 클라 요청).
 *
 * <p>Redis entity({@code TimelineDraftTask.TimelineWindow})와 같은 field name을 쓰지만 타입은 분리한다 —
 * entity의 compact {@code @JsonFormat}(AI 계약 포맷 {@code yyyyMMdd'T'HHmmss})이 HTTP 파싱에
 * 적용되면 안 되기 때문이다. HTTP는 다른 시각 필드와 같은 offset 없는 ISO local datetime을 받는다.
 */
public record TimelineWindowDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07-08T00:00",
                description = "범위 시작(타임존 없는 로컬 datetime). 클라 선택 날짜 창의 시작 — zone 규칙에 따라 "
                        + "자정이 없는 날은 그날의 가장 이른 유효 시각일 수 있다(서버는 자정 여부를 재검증하지 않는다).")
        LocalDateTime startTime,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07-09T00:00",
                description = "범위 종료(타임존 없는 로컬 datetime). startTime보다 뒤여야 한다 — 서버는 순서만 "
                        + "검증하고 하루 길이·달력 경계는 재검증하지 않는다.")
        LocalDateTime endTime
) {
}
