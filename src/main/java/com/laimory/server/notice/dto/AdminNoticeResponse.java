package com.laimory.server.notice.dto;

import com.laimory.server.notice.entity.Notice;
import java.time.LocalDateTime;

/** 관리자 공지 목록 항목 — 공개 목록과 달리 노출 상태와 수정 시각을 함께 보여준다. */
public record AdminNoticeResponse(Long noticeId, String title, String contentUrl, boolean hidden,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static AdminNoticeResponse from(Notice notice) {
        return new AdminNoticeResponse(notice.getNoticeId(), notice.getTitle(), notice.getContentUrl(),
                notice.isHidden(), notice.getCreatedAt(), notice.getUpdatedAt());
    }
}
