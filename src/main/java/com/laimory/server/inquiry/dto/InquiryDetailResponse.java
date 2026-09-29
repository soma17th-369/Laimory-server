package com.laimory.server.inquiry.dto;

import com.laimory.server.inquiry.InquiryStatus;
import com.laimory.server.inquiry.entity.Inquiry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** "내 문의" 상세 — 목록 항목의 평면 field에 답장 이메일·내용·첨부 URL을 더한다. */
@Schema(description = "내 문의 상세")
public record InquiryDetailResponse(
        @Schema(description = "문의 ID", example = "12",
                requiredMode = Schema.RequiredMode.REQUIRED) Long inquiryId,
        @Schema(description = "제목", example = "사진이 안 올라가요",
                requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(description = "처리 상태 — RECEIVED(확인 중)·ANSWERED(답변 완료, 입력한 이메일 확인). "
                + "관리자가 처리됨을 해제하면 RECEIVED로 돌아갈 수 있다",
                requiredMode = Schema.RequiredMode.REQUIRED) InquiryStatus status,
        @Schema(description = "답장 받을 이메일(접수 때 입력한 값)", example = "user@example.com",
                requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(description = "내용(줄바꿈 포함 원문)", requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(description = "첨부 사진 URL(접수 요청 순서) — 무서명 CDN URL이라 만료가 없다. 첨부가 없으면 빈 배열",
                example = "[\"https://cdn.example/{subjectHash}/inquiries/0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.jpg\"]")
        List<String> attachmentUrls,
        @Schema(description = "접수 시각(Asia/Seoul 벽시계, offset 없음)", example = "2026-09-29T10:00:00",
                requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt,
        @Schema(description = "답변 완료 표시 시각(Asia/Seoul 벽시계, offset 없음) — RECEIVED면 null",
                example = "2026-09-30T14:00:00", nullable = true) LocalDateTime answeredAt
) {

    public static InquiryDetailResponse of(Inquiry inquiry, List<String> attachmentUrls) {
        return new InquiryDetailResponse(inquiry.getInquiryId(), inquiry.getTitle(),
                InquiryStatus.of(inquiry.getAnsweredAt()), inquiry.getEmail(), inquiry.getDescription(),
                attachmentUrls, inquiry.getCreatedAt(), inquiry.getAnsweredAt());
    }
}
