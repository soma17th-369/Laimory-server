package com.laimory.server.terms.repository;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.service.TermDocumentSummary;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TermDocumentRepository extends JpaRepository<TermDocument, Long> {

    /**
     * 종류별 현재 문서 — {@code effectiveAt <= nowKst}인 문서 중 종류별 최신 버전 한 건씩.
     * {@code (term_type, effective_at)} UNIQUE가 "같은 시각 동시 최신" 모호성을 차단하므로 결정적이다.
     * 아직 유효한 문서가 없는 종류는 결과에서 빠진다(부분 결과 허용 — 호출자가 활용).
     */
    @Query("""
            SELECT d FROM TermDocument d
            WHERE d.termType IN :termTypes
              AND d.effectiveAt = (SELECT MAX(d2.effectiveAt) FROM TermDocument d2
                                   WHERE d2.termType = d.termType AND d2.effectiveAt <= :nowKst)
            """)
    List<TermDocument> findCurrentDocuments(@Param("termTypes") Collection<TermType> termTypes,
                                            @Param("nowKst") LocalDateTime nowKst);

    /**
     * 현재 문서의 content 제외 요약 — enforcement/readiness/동의 버전 검증용. 위 전체 조회와 같은
     * current selection이지만 {@code LONGTEXT content}를 요청마다 전송하지 않는다(원문은 공개 조회·이력
     * 조회에서만 읽는다).
     */
    @Query("""
            SELECT new com.laimory.server.terms.service.TermDocumentSummary(
                    d.termDocumentId, d.termType, d.stage, d.required, d.version)
            FROM TermDocument d
            WHERE d.termType IN :termTypes
              AND d.effectiveAt = (SELECT MAX(d2.effectiveAt) FROM TermDocument d2
                                   WHERE d2.termType = d.termType AND d2.effectiveAt <= :nowKst)
            """)
    List<TermDocumentSummary> findCurrentDocumentSummaries(@Param("termTypes") Collection<TermType> termTypes,
                                                           @Param("nowKst") LocalDateTime nowKst);

    /**
     * 정합성 검사용 raw catalog 행 — 엔티티 hydration을 거치지 않아 미지 {@code term_type}·{@code stage}
     * literal(오타 seed)도 예외 없이 관측된다. 검사자는 이 문자열을 enum 기대 mapping과 대조한다.
     */
    @Query(value = "SELECT term_type AS termType, stage AS stage, required AS required FROM term_documents",
            nativeQuery = true)
    List<TermCatalogRow> findCatalogRows();

    /** 정합성 검사용 raw projection — 미지 literal을 깨지 않고 나르는 문자열 view다. */
    interface TermCatalogRow {
        String getTermType();

        String getStage();

        Boolean getRequired();
    }
}
