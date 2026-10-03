package com.laimory.server.credit.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * subject의 크레딧 잔액(#548). 타임라인 전용이 아닌 범용 재화이며 subject당 한 행이다.
 *
 * <p>쓰기는 repository의 native insert-if-absent와 조건부 차감 UPDATE로 수행하므로 이 엔티티는 조회와
 * {@code ddl-auto=validate} 검증용 read model이다.
 */
@Entity
@Table(name = "subject_credits")
@Getter
public class SubjectCredit extends BaseEntity {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", nullable = false, length = 36)
    private UUID subjectId;

    /** 남은 크레딧. 차감은 {@code remaining > 0} 조건부라 음수가 되지 않는다(DB CHECK 동반). */
    @Column(nullable = false)
    private int remaining;

    protected SubjectCredit() {
    }
}
