package com.laimory.server.terms.repository;

import com.laimory.server.terms.entity.TermAgreement;
import com.laimory.server.terms.service.TermAgreementHistoryEntry;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TermAgreementRepository extends JpaRepository<TermAgreement, Long> {

    /**
     * 동의 insert-if-absent — {@code (user_id, term_document_id)} UNIQUE 중복은 원자적으로 no-op(0 반환)
     * 한다. save 반복 중 unique 예외를 멱등성으로 catch하지 않기 위한 native 문장이다(동시 동일 batch
     * 재전송이 transaction을 rollback-only로 오염시키지 않음 — push/photo job 선례). 기존 행의
     * {@code accepted_at}은 절대 갱신하지 않는다(최초 수락 시각 보존). JPA auditing을 우회하므로 감사
     * 컬럼은 호출자가 캡처한 app 시각으로 직접 채운다({@code modified_by} NULL).
     */
    @Modifying
    @Transactional // REQUIRED — batch transaction 경계(TermAgreementTransactionService)에 합류한다
    @Query(value = """
            INSERT IGNORE INTO term_agreements (user_id, term_document_id, accepted_at, created_at, updated_at)
            VALUES (:userId, :termDocumentId, :acceptedAt, :auditNow, :auditNow)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("termDocumentId") Long termDocumentId,
                       @Param("acceptedAt") LocalDateTime acceptedAt,
                       @Param("auditNow") LocalDateTime auditNow);

    /** 필수 동의 existence 판정용 단일 count — {@code (user_id, term_document_id)} UNIQUE 인덱스를 탄다. */
    long countByUserIdAndTermDocumentIdIn(Long userId, Collection<Long> termDocumentIds);

    /**
     * 회원에게 남아 있는 전체 동의 이력 + 불변 문서 행({@code acceptedAt DESC}, PK DESC 안정
     * tie-breaker). 연관 매핑 없이 FK 값으로 join한다(저장소 방침 — JPA 연관 매핑 금지).
     */
    @Query("""
            SELECT new com.laimory.server.terms.service.TermAgreementHistoryEntry(a, d)
            FROM TermAgreement a, TermDocument d
            WHERE d.termDocumentId = a.termDocumentId AND a.userId = :userId
            ORDER BY a.acceptedAt DESC, a.termAgreementId DESC
            """)
    List<TermAgreementHistoryEntry> findHistoryByUserId(@Param("userId") Long userId);
}
