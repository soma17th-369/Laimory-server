package com.laimory.server.terms.service;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.dto.AdminTermDocumentResponse;
import com.laimory.server.terms.dto.AdminTermGroupResponse;
import com.laimory.server.terms.dto.TermResponse;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentRepository;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 약관 문서 조회 — 공개 조회·동의 검증·initializer가 쓰는 current와 관리자 전체 이력.
 *
 * <p>요청 경로의 current({@link #findCurrentTerms}·{@link #findCurrentSummaries})는 {@link TermCatalogService}의
 * 캐시된 전 종류 catalog에서 메모리로 재구성한다(#491). 공개 조회 정렬은 클라이언트가 보낸 {@code termTypes} 순서가
 * 권위다. current 문서가 없는 종류는 결과에서 빠진다 — rollout 준비 상태의 정상 부분 결과이며 공개 조회를 500으로
 * 만들지 않는다(누락 경보는 {@code TermCatalogReadiness} 소유). 관리자 등록의 상위 버전 검사
 * ({@link #findCurrentDocuments})와 관리자 이력은 캐시를 거치지 않고 DB를 직접 읽는다.
 */
@Service
@RequiredArgsConstructor
public class TermDocumentService {

    private final TermDocumentRepository termDocumentRepository;
    private final TermCatalogService termCatalogService;

    /** 요청 종류의 현재 문서(요청 순서) — 공개 조회용. */
    public List<TermResponse> findCurrentTerms(String applicationVersion, List<TermType> termTypes) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return findCachedCurrent(termTypes);
    }

    /**
     * 요청 종류의 현재 문서를 DB에서 직접 고른다(요청 순서) — 관리자 등록의 상위 버전 검사용. 캐시를 거치면 stale
     * current로 검사를 통과해 낮은 버전이 등록될 수 있다(PK가 (type, version)이라 DB가 막지 못한다).
     */
    public List<TermDocument> findCurrentDocuments(Collection<TermType> termTypes) {
        if (termTypes.isEmpty()) {
            return List.of();
        }
        Map<TermType, TermDocument> documentsByType =
                TermDocument.selectCurrent(termDocumentRepository.findDocumentCandidates(termTypes));
        return termTypes.stream()
                .map(documentsByType::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 관리자용 전체 이력 — 선언된 모든 종류를 enum 순서로, 종류 안에서는 숫자 버전 내림차순으로 묶는다.
     * 그룹의 첫 문서가 current이며 문서가 없는 종류는 current가 null인 빈 그룹이다.
     */
    public List<AdminTermGroupResponse> findAllTermGroups() {
        List<TermDocument> all = termDocumentRepository.findDocumentCandidates(List.of(TermType.values())).stream()
                .sorted((left, right) -> {
                    int typeOrder = left.getTermType().compareTo(right.getTermType());
                    if (typeOrder != 0) return typeOrder;
                    if (left.getVersion().equals(right.getVersion())) return 0;
                    return left.isNewerThan(right) ? -1 : 1;
                }).toList();
        return Arrays.stream(TermType.values()).map(type -> {
            List<AdminTermDocumentResponse> history = all.stream().filter(doc -> doc.getTermType() == type)
                    .map(AdminTermDocumentResponse::from).toList();
            return new AdminTermGroupResponse(type, history.isEmpty() ? null : history.getFirst(), history);
        }).toList();
    }

    /**
     * 지정 종류들의 현재 문서 식별 요약 — 공개 조회와 같은 캐시된 current에서 필요한 key만 변환한다.
     */
    public List<TermDocumentSummary> findCurrentSummaries(Collection<TermType> termTypes) {
        return findCachedCurrent(termTypes).stream()
                .map(document -> new TermDocumentSummary(document.termType(), document.version()))
                .toList();
    }

    private List<TermResponse> findCachedCurrent(Collection<TermType> termTypes) {
        if (termTypes.isEmpty()) {
            return List.of();
        }
        Map<TermType, TermResponse> currentByType = new EnumMap<>(TermType.class);
        for (TermResponse current : termCatalogService.findAllCurrentTerms()) {
            currentByType.put(current.termType(), current);
        }
        return termTypes.stream()
                .map(currentByType::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
