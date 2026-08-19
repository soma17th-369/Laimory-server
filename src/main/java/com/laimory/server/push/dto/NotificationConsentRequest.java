package com.laimory.server.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 광고성·야간 광고성 수신 동의/철회 요청.
 *
 * <p>재시도해도 상태가 어긋나지 않는다 — 전이가 조건부 UPDATE라 이미 그 상태면 아무것도 바꾸지 않고
 * {@code ALREADY_IN_STATE}로 응답한다. 별도 멱등 키는 두지 않는다.
 */
@Schema(description = "알림 수신 동의/철회 요청")
public record NotificationConsentRequest(
        @Schema(description = "동의 여부 — true는 동의, false는 철회", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean consented,

        @Schema(description = "동의할 약관 문서 버전. 동의(true)일 때만 사용하며 현재 유효 버전과 "
                + "정확히 일치해야 한다(불일치·미존재는 409). 철회에는 보내지 않는다.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        String termVersion
) {
}
