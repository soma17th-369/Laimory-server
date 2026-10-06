package com.laimory.server.notice.dto;

import com.laimory.server.notice.entity.Notice;
import java.time.LocalDateTime;

/** 관리자 공지 목록 항목 — 공개 목록과 달리 노출 상태·팝업 지정과 수정 시각을 함께 보여준다. */
public record AdminNoticeResponse(Long noticeId, String title, String contentUrl, boolean hidden, boolean popup,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static AdminNoticeResponse from(Notice notice) {
        return new AdminNoticeResponse(notice.getNoticeId(), notice.getTitle(), notice.getContentUrl(),
                notice.isHidden(), notice.isPopup(), notice.getCreatedAt(), notice.getUpdatedAt());
    }
}
