package com.laimory.server.push.dto;

import com.laimory.server.push.NotificationConsentAction;
import com.laimory.server.push.NotificationConsentProcessingResult;
import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.entity.NotificationConsentEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 동의·철회 의사 표시 하나의 처리결과. 앱이 사용자에게 즉시 표시하고, 응답이 유실되면 설정 조회의 최근
 * 처리결과로 다시 받는다({@code eventId}로 로컬 dedupe).
 */
@Schema(description = "알림 수신 동의 처리결과")
public record NotificationConsentResultResponse(
        @Schema(description = "서버가 남긴 증적 ID(로컬 dedupe 키)", requiredMode = Schema.RequiredMode.REQUIRED)
        Long eventId,

        @Schema(description = "요청 멱등 키", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID clientRequestId,

        @Schema(description = "동의 종류", requiredMode = Schema.RequiredMode.REQUIRED)
        NotificationConsentType consentType,

        @Schema(description = "의사 표시", requiredMode = Schema.RequiredMode.REQUIRED)
        NotificationConsentAction action,

        @Schema(description = "서버 처리 시각(Asia/Seoul 벽시계)", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime occurredAt,

        @Schema(description = "전송자 법인명", requiredMode = Schema.RequiredMode.REQUIRED)
        String senderName,

        @Schema(description = "처리 결과 — APPLIED는 상태 변경, ALREADY_IN_STATE는 이미 같은 상태였음",
                requiredMode = Schema.RequiredMode.REQUIRED)
        NotificationConsentProcessingResult processingResult
) {

    public static NotificationConsentResultResponse from(NotificationConsentEvent event) {
        return new NotificationConsentResultResponse(
                event.getNotificationConsentEventId(),
                event.getClientRequestId(),
                event.getConsentType(),
                event.getAction(),
                event.getOccurredAt(),
                event.getSenderName(),
                event.getProcessingResult());
    }

    public static List<NotificationConsentResultResponse> from(List<NotificationConsentEvent> events) {
        return events.stream().map(NotificationConsentResultResponse::from).toList();
    }
}
