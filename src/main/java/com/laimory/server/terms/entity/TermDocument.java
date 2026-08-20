package com.laimory.server.terms.entity;

import com.laimory.server.common.BaseEntity;
import com.laimory.server.terms.TermType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 약관 문서 한 버전 — 불변(immutable) 행이다. 개정은 기존 행 UPDATE가 아니라 새 행 INSERT이며,
 * 게시된 행의 본문·버전·효력일을 수정·삭제하는 API는 없다(운영 seed는 수동 DDL/INSERT).
 *
 * <p>현재 문서는 별도 active flag 없이 "{@code effective_at <= now(KST)}인 종류별 최신 행"으로 계산한다 —
 * future version 사전 등록과 cutover를 효력 시각 한 축으로 관리한다. {@code effective_at}은
 * {@code Asia/Seoul} 벽시계 {@code LocalDateTime} 계약이다(offset 없음, {@code Instant} 매핑 금지).
 *
 * <p>이 행은 원문을 담지 않는다 — 약관 원문은 {@code laimory.app}에 게시된 버전별 정적 page가
 * 소유하고 응답은 {@code TermContentUrlFactory}가 만든 URL만 내려준다(#320). 단계·필수 여부·화면
 * 순서도 {@link TermType} mapping이 단일 권위라 컬럼으로 복제하지 않는다.
 */
@Entity
@Table(name = "term_documents")
@Getter
public class TermDocument extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "term_document_id")
    private Long termDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "term_type", nullable = false, length = 64)
    private TermType termType;

    @Column(name = "version", nullable = false, length = 64)
    private String version;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** 효력 시작 시각 — KST 벽시계. 같은 종류 안에서 UNIQUE(동시 최신 모호성 차단). */
    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    protected TermDocument() {
    }

    private TermDocument(TermType termType, String version, String title, LocalDateTime effectiveAt) {
        this.termType = termType;
        this.version = version;
        this.title = title;
        this.effectiveAt = effectiveAt;
    }

    /** 새 버전 행을 만든다(테스트·초기화 도구용). */
    public static TermDocument of(TermType termType, String version, String title,
                                  LocalDateTime effectiveAt) {
        return new TermDocument(termType, version, title, effectiveAt);
    }
}
