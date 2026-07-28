package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI 결과 저장 응답. 다음 단계(콜백) 인증 토큰만 담는다 — 저장된 graph는 돌려주지 않는다.
 *
 * <p>같은 결과 토큰으로 몇 번 재시도해도 같은 값이 나온다(결정적 파생). access log는 이름에
 * {@code token}이 들어간 필드를 마스킹한다.
 */
public record AiTimelineResultResponse(
        @Schema(description = "콜백 단계 인증 토큰") String callbackToken
) {
}
