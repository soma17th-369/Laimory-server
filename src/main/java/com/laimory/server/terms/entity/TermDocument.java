package com.laimory.server.terms.entity;

import com.laimory.server.common.BaseEntity;
import com.laimory.server.terms.TermStage;
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
 * <p>{@code stage}/{@code required}/{@code display_order}는 {@link TermType} mapping의 denormalized
 * 사본이다 — 판정·응답은 enum을 쓰고, 불일치는 {@code TermCatalogReadiness}가 잘못된 seed로 경보한다.
 * {@code stage}를 {@link TermStage} enum이 아닌 String으로 매핑하는 이유: 이 컬럼의 소비자는 정합성
 * 검사뿐인데, enum 매핑이면 오타 seed 행 하나가 공개 조회 hydration 자체를 500으로 깨뜨린다.
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

    /** denormalized 단계 literal — 정합성 검사 전용({@link TermType#stage()}가 판정 권위). */
    @Column(name = "stage", nullable = false, length = 32)
    private String stage;

    @Column(name = "version", nullable = false, length = 64)
    private String version;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    /** denormalized 필수 여부 — 정합성 검사 전용({@link TermType#required()}가 판정 권위). */
    @Column(name = "required", nullable = false)
    private Boolean required;

    /** denormalized 화면 순서 — 정합성 확인용({@link TermType#displayOrder()}가 정렬 권위). */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    /** 효력 시작 시각 — KST 벽시계. 같은 종류 안에서 UNIQUE(동시 최신 모호성 차단). */
    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    protected TermDocument() {
    }

    private TermDocument(TermType termType, String version, String title, String content,
                         LocalDateTime effectiveAt) {
        this.termType = termType;
        this.stage = termType.stage().name();
        this.version = version;
        this.title = title;
        this.content = content;
        this.required = termType.required();
        this.displayOrder = termType.displayOrder();
        this.effectiveAt = effectiveAt;
    }

    /** enum mapping과 일치하는 denormalized 값으로 새 버전 행을 만든다(테스트·초기화 도구용). */
    public static TermDocument of(TermType termType, String version, String title, String content,
                                  LocalDateTime effectiveAt) {
        return new TermDocument(termType, version, title, content, effectiveAt);
    }
}
