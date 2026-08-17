package com.laimory.server.user;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * 회원 탈퇴가 durable하게 접수한 계정 데이터 삭제 작업(#305) — 미래 #302 worker가 소비한다.
 *
 * <p>{@code user_id}만 저장한다. {@code subject_id}를 같은 row/table에 저장하지 않아 DB만으로
 * raw identity와 content subject를 평문 join할 수 없다는 {@code user_subject_links}의 보안 속성을
 * 유지한다(#302는 착수 시 {@code SubjectMappingService#getRequired}로 해석). user FK는
 * {@code ON DELETE RESTRICT}라 job이 남은 user 행을 지울 수 없다.
 *
 * <p>쓰기는 repository의 native {@code INSERT IGNORE}(insert-if-absent)뿐이라 JPA auditing이 돌지
 * 않고 감사 컬럼은 insert SQL이 직접 채운다({@code modified_by} NULL — term_agreements 선례).
 * 이 엔티티는 조회·{@code ddl-auto=validate} 검증용 read model이다. {@code created_at}이 접수 감사
 * 시각이자 oldest-age 관측 기준이다.
 */
@Entity
@Table(name = "account_erasure_jobs",
        uniqueConstraints = @UniqueConstraint(name = "uq_account_erasure_jobs_user", columnNames = "user_id"))
@Getter
public class AccountErasureJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_erasure_job_id")
    private Long accountErasureJobId;

    /** 삭제 대상 회원 raw userId — subjectId는 의도적으로 저장하지 않는다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** #305에서는 항상 {@code PENDING}이다(DB default). claim/stage 확장은 #302 몫. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32, insertable = false)
    private AccountErasureJobStatus status;

    protected AccountErasureJob() {
    }
}
