package com.laimory.server.notice.dto;

import com.laimory.server.notice.entity.Notice;
import java.time.LocalDateTime;

/**
 * 관리자 공지 목록 항목 — 공개 목록과 달리 노출 상태·팝업 지정·썸네일(없으면 null)과 수정 시각을 함께 보여준다.
 */
public record AdminNoticeResponse(Long noticeId, String title, String contentUrl, boolean hidden, boolean popup,
                                  String thumbnailUrl, LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static AdminNoticeResponse of(Notice notice, String thumbnailUrl) {
        return new AdminNoticeResponse(notice.getNoticeId(), notice.getTitle(), notice.getContentUrl(),
                notice.isHidden(), notice.isPopup(), thumbnailUrl, notice.getCreatedAt(), notice.getUpdatedAt());
    }
}
