package com.laimory.server.inquiry.dto;

import com.laimory.server.inquiry.InquiryStatus;
import com.laimory.server.inquiry.entity.Inquiry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** "내 문의" 목록의 한 건 — 제목으로 훑는다. 내용·답장 이메일·첨부는 상세에서만 싣는다. */
@Schema(description = "내 문의 목록 항목")
public record InquirySummaryResponse(
        @Schema(description = "문의 ID", example = "12",
                requiredMode = Schema.RequiredMode.REQUIRED) Long inquiryId,
        @Schema(description = "제목", example = "사진이 안 올라가요",
                requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(description = "처리 상태 — RECEIVED(확인 중)·ANSWERED(답변 완료, 입력한 이메일 확인). "
                + "관리자가 처리됨을 해제하면 RECEIVED로 돌아갈 수 있다",
                requiredMode = Schema.RequiredMode.REQUIRED) InquiryStatus status,
        @Schema(description = "접수 시각(Asia/Seoul 벽시계, offset 없음)", example = "2026-09-29T10:00:00",
                requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt,
        @Schema(description = "답변 완료 표시 시각(Asia/Seoul 벽시계, offset 없음) — RECEIVED면 null",
                example = "2026-09-30T14:00:00", nullable = true) LocalDateTime answeredAt
) {

    public static InquirySummaryResponse from(Inquiry inquiry) {
        return new InquirySummaryResponse(inquiry.getInquiryId(), inquiry.getTitle(),
                InquiryStatus.of(inquiry.isAnswered()), inquiry.getCreatedAt(), inquiry.getAnsweredAt());
    }
}
