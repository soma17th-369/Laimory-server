package com.laimory.server.notice.dto;

import com.laimory.server.notice.entity.Notice;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 공지 상세 — 본문 텍스트를 서버가 직접 내려준다(WebView URL 아님). */
@Schema(description = "공지 상세")
public record NoticeResponse(
        @Schema(description = "공지 ID", example = "12",
                requiredMode = Schema.RequiredMode.REQUIRED) Long noticeId,
        @Schema(description = "제목", example = "서비스 점검 안내",
                requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(description = "본문 텍스트(줄바꿈 포함 원문)",
                requiredMode = Schema.RequiredMode.REQUIRED) String body,
        @Schema(description = "게시 시각(Asia/Seoul 벽시계, offset 없음)", example = "2026-09-24T10:00:00",
                requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime publishedAt
) {

    public static NoticeResponse from(Notice notice) {
        return new NoticeResponse(notice.getNoticeId(), notice.getTitle(), notice.getBody(),
                notice.getCreatedAt());
    }
}
