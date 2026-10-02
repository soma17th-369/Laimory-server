package com.laimory.server.inquiry.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** "내 문의" 목록({@code GET /a/api/{version}/inquiries}) — 최신 순 최대 50건. 문의가 없으면 404가 아니라 빈 배열이다. */
@Schema(description = "내 문의 목록 응답")
public record InquiryListResponse(
        @Schema(description = "내 문의(최신 순, 최대 50건) — 없으면 빈 배열") List<InquirySummaryResponse> inquiries
) {
}
