package com.laimory.server.terms.repository;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermAgreement;
import com.laimory.server.terms.entity.TermAgreementId;
import com.laimory.server.terms.service.TermAgreementHistoryEntry;
import com.laimory.server.terms.service.TermDocumentSummary;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TermAgreementRepository extends JpaRepository<TermAgreement, TermAgreementId> {

    /**
     * 동의 insert-if-absent — 복합 PK {@code (user_id, term_type, version)} 중복은 원자적으로 no-op(0 반환)
     * 한다. save 반복 중 unique 예외를 멱등성으로 catch하지 않기 위한 native 문장이다(동시 동일 batch
     * 재전송이 transaction을 rollback-only로 오염시키지 않음 — push/photo job 선례). 기존 행의
     * {@code accepted_at}은 절대 갱신하지 않는다(최초 수락 시각 보존). JPA auditing을 우회하므로 감사
     * 컬럼은 호출자가 캡처한 app 시각으로 직접 채운다({@code modified_by} NULL).
     */
    @Modifying
    @Transactional // REQUIRED — batch transaction 경계(TermAgreementTransactionService)에 합류한다
    @Query(value = """
            INSERT IGNORE INTO term_agreements
                (user_id, term_type, version, accepted_at, created_at, updated_at)
            VALUES (:userId, :termType, :version, :acceptedAt, :auditNow, :auditNow)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("termType") String termType,
                       @Param("version") String version,
                       @Param("acceptedAt") LocalDateTime acceptedAt,
                       @Param("auditNow") LocalDateTime auditNow);

    /**
     * 계정 삭제(#302)의 owner 동의 이력 전량 제거 — 완전 소거 확정이라 탈퇴 회원의 동의 증적은
     * 보존하지 않는다(계획 §3.2).
     *
     * <p>이 테이블은 {@code users} FK가 없어(#303이 보존 정책 확정 전까지 유보) mapping 삭제의 FK
     * fence가 적용되지 않는다 — finalization transaction이 같은 삭제를 한 번 더 수행해 그 사이 완료된
     * 지연 동의를 흡수한다(멱등, 평시 0행). 문서 행({@code term_documents})은 건드리지 않는다.
     */
    @Modifying
    @Transactional
    @Query("delete from TermAgreement a where a.id.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    /**
     * 회원에게 남아 있는 전체 동의 이력 + 불변 문서 행. 같은 acceptedAt은 복합 PK의 종류·버전으로
     * 전순서를 만든다. 연관 매핑 없이 복합 FK 값으로 join한다.
     */
    @Query("""
            SELECT new com.laimory.server.terms.service.TermAgreementHistoryEntry(a, d)
            FROM TermAgreement a, TermDocument d
            WHERE d.id.termType = a.id.termType
              AND d.id.version = a.id.version
              AND a.id.userId = :userId
            ORDER BY a.acceptedAt DESC, a.id.termType DESC, a.id.version DESC
            """)
    List<TermAgreementHistoryEntry> findHistoryByUserId(@Param("userId") Long userId);

    /**
     * 요청 종류에서 이 회원이 동의한 문서 key 집합 — initializer가 current key 집합에서 빼는 용도다.
     * JPQL tuple-list IN에 의존하지 않고 user/type 후보를 읽어 Java record set으로 비교한다.
     */
    @Query("""
            SELECT new com.laimory.server.terms.service.TermDocumentSummary(a.id.termType, a.id.version)
            FROM TermAgreement a
            WHERE a.id.userId = :userId AND a.id.termType IN :termTypes
            """)
    List<TermDocumentSummary> findAgreedDocumentKeys(@Param("userId") Long userId,
                                                     @Param("termTypes") Collection<TermType> termTypes);
}
