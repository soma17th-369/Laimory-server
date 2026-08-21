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
 * <p>이 행은 원문을 담지 않는다 — 약관 원문은 게시된 버전별 page가 소유하고 이 행은 그 주소
 * ({@code contentUrl})만 들고 있다(#320). URL은 게시 시점에 확정된 사실이라 코드에서 역산하지 않는다:
 * 게시 host·경로 규칙이 바뀌어도 과거 버전 행이 조용히 다른 주소를 가리키지 않는다. 단계·필수 여부·화면
 * 순서는 {@link TermType} mapping이 단일 권위라 컬럼으로 복제하지 않는다.
 *
 * <p>{@code contentUrl}을 {@code URI}가 아닌 {@code String}으로 매핑하는 이유: 이 값은 운영 seed가 넣는
 * 문자열이고, 타입 변환을 걸면 오타 seed 행 하나가 공개 조회 hydration 자체를 500으로 깨뜨린다. 형식
 * 위반은 {@code TermCatalogReadiness}가 경보하고 조회는 계속된다(term_type binary collation과 같은 이유).
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

    /** 게시된 이 버전 원문 page의 절대 https URL — 서버는 조회·검증만 하고 HTTP로 열지 않는다. */
    @Column(name = "content_url", nullable = false, length = 512)
    private String contentUrl;

    /** 효력 시작 시각 — KST 벽시계. 같은 종류 안에서 UNIQUE(동시 최신 모호성 차단). */
    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    protected TermDocument() {
    }

    private TermDocument(TermType termType, String version, String title, String contentUrl,
                         LocalDateTime effectiveAt) {
        this.termType = termType;
        this.version = version;
        this.title = title;
        this.contentUrl = contentUrl;
        this.effectiveAt = effectiveAt;
    }

    /** 새 버전 행을 만든다(테스트·초기화 도구용). */
    public static TermDocument of(TermType termType, String version, String title, String contentUrl,
                                  LocalDateTime effectiveAt) {
        return new TermDocument(termType, version, title, contentUrl, effectiveAt);
    }
}
