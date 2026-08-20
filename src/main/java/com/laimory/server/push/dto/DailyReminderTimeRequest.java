package com.laimory.server.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 일일 리마인더 시각 설정 요청 — 분 단위 {@code HH:mm}, 기준 timezone은 서버 고정 {@code Asia/Seoul}이다. */
@Schema(description = "일일 리마인더 시각 설정 요청")
public record DailyReminderTimeRequest(
        @Schema(description = "알림 시각(Asia/Seoul 기준 24시간제 HH:mm, 분 단위)", example = "21:00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String time
) {
}
