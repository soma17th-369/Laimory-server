package com.laimory.server.terms.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 회원의 약관 버전 동의 이력 한 건 — {@code (user_id, term_document_id)}당 1행이다.
 *
 * <p>문서 버전이 불변이므로 이 행이 "언제 어떤 버전에 동의했는지"의 권위 기록이다 — 그 버전의 원문은
 * 불변 URL로 게시된 page가 재현한다(#320). 같은 버전 재동의는
 * 새 행을 만들지도 {@code accepted_at}을 덮어쓰지도 않는다(멱등 — native insert-if-absent).
 *
 * <p>쓰기는 repository의 native {@code INSERT IGNORE}뿐이라 JPA auditing이 돌지 않고 감사 컬럼은 insert
 * SQL이 직접 채운다({@code modified_by} NULL). 이 엔티티는 조회·{@code ddl-auto=validate} 검증용 read
 * model이다. {@code accepted_at}은 서버가 transaction 시작에 캡처한 {@code Asia/Seoul} 벽시계다
 * (클라이언트 입력 아님).
 *
 * <p>owner는 인증 회원의 raw {@code user_id}다(회원 account 도메인 — 콘텐츠 subject 아님). 탈퇴 후
 * 보존 정책(#302/#305)이 확정되기 전이라 {@code users} FK는 두지 않는다({@code refresh_tokens} 선례).
 */
@Entity
@Table(name = "term_agreements")
@Getter
public class TermAgreement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "term_agreement_id")
    private Long termAgreementId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "term_document_id", nullable = false)
    private Long termDocumentId;

    /** 서버 수락 시각 — KST 벽시계(batch 전체 동일 값, 재동의에도 불변). */
    @Column(name = "accepted_at", nullable = false)
    private LocalDateTime acceptedAt;

    protected TermAgreement() {
    }
}
