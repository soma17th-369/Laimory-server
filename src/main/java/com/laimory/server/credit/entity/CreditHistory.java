package com.laimory.server.credit.entity;

import com.laimory.server.common.BaseEntity;
import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.CreditHistoryStatus;
import com.laimory.server.credit.CreditRefundReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 크레딧 사용 1건의 기록(#565) — 요청 시 차감({@code CHARGED})이 결과 저장({@code CONSUMED}) 또는
 * 환불({@code REFUNDED})로 한 번 전이한다. 환불 근거와 금액({@link #amount})의 단일 기준이다.
 *
 * <p>쓰기는 repository의 native INSERT와 JPQL 전이 UPDATE로 수행하므로 이 엔티티는 조회와
 * {@code ddl-auto=validate} 검증용 read model이다.
 */
@Entity
@Table(name = "credit_histories")
@Getter
public class CreditHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "credit_history_id")
    private Long creditHistoryId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", nullable = false, length = 36)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_type", nullable = false, length = 64)
    private CreditCostType costType;

    /** 쓴 대상의 id — {@code TIMELINE_CREATION}이면 taskId다. */
    @Column(name = "reference_id", nullable = false, length = 36)
    private String referenceId;

    /** 차감한 양. 환불은 이 값으로 한다(비용 변경 migration과 무관). */
    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CreditHistoryStatus status;

    /** {@code REFUNDED}일 때만 채워진다(DB CHECK). */
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_reason", length = 32)
    private CreditRefundReason refundReason;

    protected CreditHistory() {
    }
}
