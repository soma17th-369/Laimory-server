package com.laimory.server.terms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 내 동의 이력 응답({@code GET /a/api/{version}/terms/agreements}) — 최신 수락 순
 * ({@code acceptedAt DESC}, 안정 tie-breaker). 이력이 없으면 404가 아니라 빈 배열이다.
 */
@Schema(description = "약관 동의 이력 응답")
public record TermAgreementHistoryResponse(
        @Schema(description = "회원에게 남아 있는 전체 동의 이력(최신 수락 순) — 없으면 빈 배열")
        List<TermAgreementResponse> agreements
) {
}
