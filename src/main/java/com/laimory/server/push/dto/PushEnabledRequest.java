package com.laimory.server.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 수신 ON/OFF 요청 — 전체 푸시 마스터와 일일 리마인더가 같은 body shape를 공유한다.
 * 어느 대상을 바꾸는지는 경로가 정한다. 같은 값 재요청은 멱등 성공이다.
 */
@Schema(description = "수신 ON/OFF 요청")
public record PushEnabledRequest(
        @Schema(description = "수신 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean enabled
) {
}
