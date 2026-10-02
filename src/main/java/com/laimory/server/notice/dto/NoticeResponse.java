package com.laimory.server.notice.dto;

import com.laimory.server.notice.entity.Notice;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 공지 한 건 — 원문은 응답에 담지 않고 {@code contentUrl}이 가리키는 게시된 page가 소유한다.
 * 클라이언트는 이 URL을 WebView로 연다(약관 조회와 같은 구조, 이미지·서식은 page가 담당).
 */
@Schema(description = "공지")
public record NoticeResponse(
        @Schema(description = "공지 ID", example = "12",
                requiredMode = Schema.RequiredMode.REQUIRED) Long noticeId,
        @Schema(description = "제목", example = "서비스 점검 안내",
                requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(description = "공지 원문 WebView HTTPS URL",
                example = "https://www.laimory.app/notices/12", format = "uri",
                requiredMode = Schema.RequiredMode.REQUIRED) String contentUrl,
        @Schema(description = "게시 시각(Asia/Seoul 벽시계, offset 없음)", example = "2026-09-24T10:00:00",
                requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime publishedAt
) {

    public static NoticeResponse from(Notice notice) {
        return new NoticeResponse(notice.getNoticeId(), notice.getTitle(), notice.getContentUrl(),
                notice.getCreatedAt());
    }
}
