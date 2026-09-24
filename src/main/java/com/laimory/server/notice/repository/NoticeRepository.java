package com.laimory.server.notice.repository;

import com.laimory.server.notice.entity.Notice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회는 선언 query method만 쓴다 — {@code findById}류 CrudRepository 상속 조회는
 * {@code SimpleJpaRepository}의 클래스 {@code @Transactional(readOnly = true)}가 남아 읽기 경로에
 * transaction을 만든다(#499).
 */
public interface NoticeRepository extends JpaRepository<Notice, Long> {

    /** 공개 목록 — 숨김이 아닌 공지, 최신(큰 PK) 순. */
    List<Notice> findByHiddenFalseOrderByNoticeIdDesc();

    /** 관리자 목록 — 숨김 포함 전체, 최신 순. */
    List<Notice> findAllByOrderByNoticeIdDesc();

    Optional<Notice> findByNoticeId(Long noticeId);
}
