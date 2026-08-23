package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 알림 아이템 payload. 단일 시점 아이템 — 수신 시각은 envelope의 startAt이 담는다(endAt 없음).
 * title/text 중 최소 하나는 있어야 한다(입력 경계에서 검증).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationPayload(
        @Schema(example = "카카오톡", description = "앱 이름. 선택.")
        String appName,
        @Schema(description = "알림 제목. title/text 중 최소 하나는 필수.")
        String title,
        @Schema(description = "알림 본문. title/text 중 최소 하나는 필수.")
        String text
) implements TimelineItemPayload {
}
