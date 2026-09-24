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
 * 공지사항 leaf service — 공개 조회(숨김 제외)와 관리자 등록·수정·노출 전환을 소유한다.
 *
 * <p>읽기 경로는 SELECT 하나라 Spring transaction 없이 autocommit으로 실행한다(#499). 쓰기는
 * 행 하나의 dirty checking이라 service 메서드 transaction 하나가 경계다.
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

    /** 공개 상세 — 없음과 숨김을 구분 없이 404로 은닉한다. */
    public Notice getVisibleNotice(String applicationVersion, long noticeId) {
        return noticeRepository.findByNoticeIdAndHiddenFalse(noticeId)
                .orElseThrow(() -> new BusinessException(ExceptionType.RESOURCE_NOT_FOUND));
    }

    /** 관리자 목록 — 숨김 포함 전체, 최신 순. */
    public List<Notice> findAllNotices() {
        return noticeRepository.findAllByOrderByNoticeIdDesc();
    }

    @Transactional
    public Notice register(String title, String body) {
        return noticeRepository.save(Notice.of(title, body));
    }

    @Transactional
    public Notice edit(long noticeId, String title, String body) {
        Notice notice = requireNotice(noticeId);
        notice.edit(title, body);
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
