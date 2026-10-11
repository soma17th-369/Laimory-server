package com.laimory.server.credit.repository;

import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.CreditHistoryStatus;
import com.laimory.server.credit.CreditRefundReason;
import com.laimory.server.credit.entity.CreditHistory;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * credit_histories 레포(#565). 신규 행은 native INSERT, 상태 전이는 JPQL 일괄 UPDATE라 JPA auditing을 우회한다 —
 * 감사 timestamp는 호출자가 앱 {@code Clock}의 Seoul 벽시계로 넘긴다.
 */
public interface CreditHistoryRepository extends JpaRepository<CreditHistory, Long> {

    @Modifying
    @Transactional
    @Query(value = "insert into credit_histories "
            + "(subject_id, cost_type, reference_id, amount, status, created_at, updated_at) "
            + "values (:subjectId, :costType, :referenceId, :amount, 'CHARGED', :now, :now)",
            nativeQuery = true)
    int insertCharged(@Param("subjectId") String subjectId,
                      @Param("costType") String costType,
                      @Param("referenceId") String referenceId,
                      @Param("amount") int amount,
                      @Param("now") LocalDateTime now);

    /** 대상 단건 조회 — 인터페이스 선언이라 호출자 transaction 밖에서는 transaction 없이 실행된다(#499). */
    Optional<CreditHistory> findByCostTypeAndReferenceId(CreditCostType costType, String referenceId);

    /**
     * 결과 저장으로 차감을 확정한다. 상태를 조건으로 보지 않는다 — 유실 환불 뒤 늦게 저장된 결과도 마지막에 쓴
     * {@code CONSUMED}가 남는다(그 생성 1회 무료, 수용한 경합). 행이 없으면(기록 도입 전 task) 0행이다.
     */
    @Modifying
    @Transactional
    @Query("update CreditHistory h set h.status = :consumed, h.refundReason = null, h.updatedAt = :now "
            + "where h.costType = :costType and h.referenceId = :referenceId")
    int markConsumed(@Param("costType") CreditCostType costType,
                     @Param("referenceId") String referenceId,
                     @Param("consumed") CreditHistoryStatus consumed,
                     @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("update CreditHistory h set h.status = :refunded, h.refundReason = :reason, h.updatedAt = :now "
            + "where h.creditHistoryId = :creditHistoryId")
    int markRefunded(@Param("creditHistoryId") long creditHistoryId,
                     @Param("refunded") CreditHistoryStatus refunded,
                     @Param("reason") CreditRefundReason reason,
                     @Param("now") LocalDateTime now);

    /** 계정 삭제(#302)의 기록 행 제거. 미존재는 0행(멱등). */
    @Modifying
    @Transactional
    @Query("delete from CreditHistory h where h.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") UUID subjectId);
}
