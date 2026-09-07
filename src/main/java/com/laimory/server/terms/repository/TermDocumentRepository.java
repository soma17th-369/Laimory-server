package com.laimory.server.terms.repository;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.entity.TermDocumentId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TermDocumentRepository extends JpaRepository<TermDocument, TermDocumentId> {

    /**
     * 요청 종류의 모든 후보 문서. VARCHAR 정렬로 current를 잘못 고르지 않도록 repository는 후보 조회만
     * 담당하고 {@code TermDocumentService}가 엔티티의 버전 비교로 current를 선택한다.
     */
    @Query("""
            SELECT d FROM TermDocument d
            WHERE d.id.termType IN :termTypes
            """)
    List<TermDocument> findDocumentCandidates(@Param("termTypes") Collection<TermType> termTypes);

    /**
     * 정합성 검사용 raw catalog 행 — 엔티티 hydration을 거치지 않아 미지 {@code term_type}
     * literal(오타 seed)도 예외 없이 관측된다. 검사자는 이 문자열을 enum 기대 종류와 대조하고
     * {@code content_url}이 https 절대 URI 형식인지 확인한다.
     */
    @Query(value = "SELECT term_type AS termType, content_url AS contentUrl FROM term_documents",
            nativeQuery = true)
    List<TermCatalogRow> findCatalogRows();

    /** 정합성 검사용 raw projection — 잘못된 값을 깨지 않고 나르는 문자열 view다. */
    interface TermCatalogRow {
        String getTermType();

        String getContentUrl();
    }
}
