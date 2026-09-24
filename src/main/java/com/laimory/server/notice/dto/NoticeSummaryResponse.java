package com.laimory.server.notice.dto;

import com.laimory.server.notice.entity.Notice;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 공지 목록 한 항목 — 본문은 싣지 않는다(상세 조회가 소유). */
@Schema(description = "공지 목록 항목")
public record NoticeSummaryResponse(
        @Schema(description = "공지 ID — 상세 조회 path에 그대로 쓴다", example = "12",
                requiredMode = Schema.RequiredMode.REQUIRED) Long noticeId,
        @Schema(description = "제목", example = "서비스 점검 안내",
                requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(description = "게시 시각(Asia/Seoul 벽시계, offset 없음)", example = "2026-09-24T10:00:00",
                requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime publishedAt
) {

    public static NoticeSummaryResponse from(Notice notice) {
        return new NoticeSummaryResponse(notice.getNoticeId(), notice.getTitle(), notice.getCreatedAt());
    }
}
