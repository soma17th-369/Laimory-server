package com.laimory.server.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * 광고성·야간 광고성 수신 동의/철회 요청.
 *
 * <p>{@code clientRequestId}는 앱의 durable outbox가 붙이는 멱등 키다 — 응답이 유실돼 재시도해도 같은 ID면
 * 상태가 다시 바뀌지 않고 원래 처리결과를 돌려받는다. 새 의사 표시에는 새 ID를 쓴다.
 */
@Schema(description = "알림 수신 동의/철회 요청")
public record NotificationConsentRequest(
        @Schema(description = "앱이 생성한 요청 멱등 키(UUID). 재시도는 같은 값, 새 의사 표시는 새 값.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID clientRequestId,

        @Schema(description = "동의 여부 — true는 동의, false는 철회", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean consented,

        @Schema(description = "동의할 약관 문서 버전. 동의(true)일 때만 사용하며 현재 유효 버전과 "
                + "정확히 일치해야 한다(불일치·미존재는 409). 철회에는 보내지 않는다.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        String termVersion
) {
}
