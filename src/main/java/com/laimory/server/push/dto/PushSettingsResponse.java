package com.laimory.server.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 푸시 설정 화면의 서버 권위 상태. 재설치·다중 기기에서도 로컬 추정값이 아니라 이 응답을 표시한다.
 */
@Schema(description = "푸시 수신 설정 조회 응답")
public record PushSettingsResponse(
        @Schema(description = "전체 푸시 수신 여부(모든 알림의 최상위 스위치)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean pushEnabled,

        @Schema(description = "일일 리마인더 설정", requiredMode = Schema.RequiredMode.REQUIRED)
        DailyReminder dailyReminder
) {

    /** 일일 리마인더의 수신 여부와 시각. */
    @Schema(description = "일일 리마인더 설정")
    public record DailyReminder(
            @Schema(description = "일일 리마인더 수신 여부", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean enabled,

            @Schema(description = "알림 시각(Asia/Seoul 기준 HH:mm)", example = "21:00",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String time
    ) {
    }
}
