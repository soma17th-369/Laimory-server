package com.laimory.server.inquiry.repository;

import com.laimory.server.inquiry.entity.InquiryAttachment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface InquiryAttachmentRepository extends JpaRepository<InquiryAttachment, Long> {

    List<InquiryAttachment> findByInquiryIdOrderByPositionAsc(Long inquiryId);

    /** 관리자 목록의 첨부 수 — 문의별로 한 query로 읽는다. */
    List<InquiryAttachment> findByInquiryIdIn(Collection<Long> inquiryIds);

    /** 계정 삭제(#302) — 문의 행보다 먼저 지운다(FK RESTRICT). 호출자 transaction에 합류한다. */
    @Modifying
    @Transactional
    @Query("""
            delete from InquiryAttachment a
            where a.inquiryId in (select i.inquiryId from Inquiry i where i.subjectId = :subjectId)
            """)
    int deleteAllBySubjectId(@Param("subjectId") UUID subjectId);
}
