package com.laimory.server.inquiry.repository;

import com.laimory.server.inquiry.entity.Inquiry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 조회는 선언 query method만 쓴다 — {@code findById}류는 읽기 경로에 transaction을 남긴다(#499). */
public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    /** 관리자 목록 — 전체, 최신(큰 PK) 순. */
    List<Inquiry> findAllByOrderByInquiryIdDesc();

    Optional<Inquiry> findByInquiryId(Long inquiryId);

    /**
     * 계정 삭제(#302)의 owner 문의 전량 제거 — email PII를 포함한 행째 지운다. 첨부 행이 먼저 지워져
     * 있어야 한다(FK RESTRICT). 미존재는 0행(멱등)이며 호출자 transaction에 합류한다.
     */
    @Modifying
    @Transactional
    @Query("delete from Inquiry i where i.subjectId = :subjectId")
    int deleteAllBySubjectId(@Param("subjectId") UUID subjectId);
}
