package com.laimory.server.notice.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.notice.entity.Notice;
import com.laimory.server.notice.repository.NoticeRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지사항 leaf service — 공개 목록(숨김 제외)과 관리자 등록·수정·노출 전환을 소유한다.
 *
 * <p>공개 계약은 목록 하나다 — 원문은 각 항목의 {@code contentUrl} page가 소유하므로 상세 조회가 없다
 * (약관 공개 조회와 같은 형태). 읽기 경로는 SELECT 하나라 Spring transaction 없이 autocommit으로
 * 실행한다(#499). 쓰기는 행 하나의 dirty checking이라 service 메서드 transaction 하나가 경계다.
 */
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;

    /** 공개 목록 — 노출 중 공지 전체, 최신 순. 없으면 빈 목록이다. */
    public List<Notice> findVisibleNotices(String applicationVersion) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return noticeRepository.findByHiddenFalseOrderByNoticeIdDesc();
    }

    /** 관리자 목록 — 숨김 포함 전체, 최신 순. */
    public List<Notice> findAllNotices() {
        return noticeRepository.findAllByOrderByNoticeIdDesc();
    }

    @Transactional
    public Notice register(String title, String contentUrl) {
        return noticeRepository.save(Notice.of(title, contentUrl));
    }

    @Transactional
    public Notice edit(long noticeId, String title, String contentUrl) {
        Notice notice = requireNotice(noticeId);
        notice.edit(title, contentUrl);
        return notice;
    }

    @Transactional
    public Notice changeVisibility(long noticeId, boolean hidden) {
        Notice notice = requireNotice(noticeId);
        notice.changeVisibility(hidden);
        return notice;
    }

    private Notice requireNotice(long noticeId) {
        return noticeRepository.findByNoticeId(noticeId)
                .orElseThrow(() -> new BusinessException(ExceptionType.RESOURCE_NOT_FOUND));
    }
}
