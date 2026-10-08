package com.laimory.server.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 앱 시작 팝업 한 건(#560) — 앱이 웹뷰 없이 네이티브로 그릴 제목과 썸네일만 담는다. 원문 URL은 담지 않는다:
 * 팝업을 탭하면 앱이 공지 단건 조회로 받는다. 이니셜라이저 응답이자 공유 Redis 캐시 값이다.
 */
@Schema(description = "앱 시작 팝업 공지")
public record PopupNoticeResponse(
        @Schema(description = "공지 ID — 탭 시 단건 조회와 재노출 방지 기억에 쓴다", example = "15",
                requiredMode = Schema.RequiredMode.REQUIRED) Long noticeId,
        @Schema(description = "제목", example = "서비스 점검 안내",
                requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(description = "썸네일 이미지 HTTPS URL(무서명 CDN) — 팝업 공지는 항상 썸네일이 있다",
                example = "https://cdn.example/notices/0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.webp", format = "uri",
                requiredMode = Schema.RequiredMode.REQUIRED) String thumbnailUrl
) {
}
