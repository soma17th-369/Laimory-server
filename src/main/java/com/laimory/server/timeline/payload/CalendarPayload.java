package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 일정 아이템 payload. 모든 필드 선택 — 별도 필드 검증 없이 그대로 저장한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CalendarPayload(
        @Schema(description = "일정 제목. 선택.")
        String title,
        @Schema(description = "장소 텍스트. 선택.")
        String locationText,
        @Schema(description = "일정 설명. 선택.")
        String description,
        @Schema(description = "종일 일정 여부. 선택.")
        Boolean allDay
) implements TimelineItemPayload {
}
