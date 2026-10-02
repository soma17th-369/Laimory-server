package com.laimory.server.notice.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.notice.dto.AdminNoticeResponse;
import com.laimory.server.notice.dto.NoticeResponse;
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
 * 관리자 쓰기는 결과를 반환하지 않는다 — 관리자 웹이 쓰기 직후 목록을 다시 조회한다(조회가 단일 원천, #528).
 */
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;

    /** 공개 목록 — 노출 중 공지 전체, 최신 순. 없으면 빈 목록이다. */
    public List<NoticeResponse> findVisibleNotices(String applicationVersion) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return noticeRepository.findByHiddenFalseOrderByNoticeIdDesc().stream()
                .map(NoticeResponse::from)
                .toList();
    }

    /** 관리자 목록 — 숨김 포함 전체, 최신 순. */
    public List<AdminNoticeResponse> findAllNotices() {
        return noticeRepository.findAllByOrderByNoticeIdDesc().stream()
                .map(AdminNoticeResponse::from)
                .toList();
    }

    @Transactional
    public void register(String title, String contentUrl) {
        noticeRepository.save(Notice.of(title, contentUrl));
    }

    @Transactional
    public void edit(long noticeId, String title, String contentUrl) {
        requireNotice(noticeId).edit(title, contentUrl);
    }

    @Transactional
    public void changeVisibility(long noticeId, boolean hidden) {
        requireNotice(noticeId).changeVisibility(hidden);
    }

    private Notice requireNotice(long noticeId) {
        return noticeRepository.findByNoticeId(noticeId)
                .orElseThrow(() -> new BusinessException(ExceptionType.RESOURCE_NOT_FOUND));
    }
}
