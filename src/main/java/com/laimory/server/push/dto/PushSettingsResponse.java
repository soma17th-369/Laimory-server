package com.laimory.server.push.dto;

import com.laimory.server.push.PushComplianceClass;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 푸시 설정 화면의 서버 권위 상태. 재설치·다중 기기에서도 로컬 추정값이 아니라 이 응답을 표시한다.
 *
 * <p>{@code classification}은 제품 책임자가 배포 전에 코드로 확정한 값이라 항상 non-null이다 —
 * 앱이 문구를 보고 광고성 여부를 추정하지 않게 서버가 알려준다.
 */
@Schema(description = "푸시 수신 설정 조회 응답")
public record PushSettingsResponse(
        @Schema(description = "전체 푸시 수신 여부(모든 알림의 최상위 스위치)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean pushEnabled,

        @Schema(description = "일일 리마인더 설정", requiredMode = Schema.RequiredMode.REQUIRED)
        DailyReminder dailyReminder,

        @Schema(description = "광고성 푸시 수신 동의 상태", requiredMode = Schema.RequiredMode.REQUIRED)
        ConsentStatus advertisingPushConsent,

        @Schema(description = "야간(21:00~08:00) 광고성 푸시 수신 동의 상태",
                requiredMode = Schema.RequiredMode.REQUIRED)
        ConsentStatus nightAdvertisingPushConsent,

        @Schema(description = "최근 14일 동의·철회 처리결과(최신순)", requiredMode = Schema.RequiredMode.REQUIRED)
        List<NotificationConsentResultResponse> recentConsentResults
) {

    /** 일일 리마인더의 수신 여부·시각과 제품이 확정한 법적 분류. */
    @Schema(description = "일일 리마인더 설정")
    public record DailyReminder(
            @Schema(description = "일일 리마인더 수신 여부", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean enabled,

            @Schema(description = "알림 시각(Asia/Seoul 기준 HH:mm)", example = "21:00",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String time,

            @Schema(description = "법적 분류 — ADVERTISING이면 켜기 전에 광고 수신 동의가 필요하다",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            PushComplianceClass classification
    ) {
    }

    /**
     * 선택 동의 하나의 현재 상태. {@code version}은 동의한 문서 버전이며 미동의이거나 문서를 찾지 못하면
     * key 생략 없이 명시적 {@code null}이다.
     */
    @Schema(description = "선택 동의 상태")
    public record ConsentStatus(
            @Schema(description = "동의 여부", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean consented,

            @Schema(description = "동의한 문서 버전(미동의면 null)", nullable = true,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String version
    ) {
    }
}
