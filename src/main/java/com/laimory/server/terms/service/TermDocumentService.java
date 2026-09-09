package com.laimory.server.terms.service;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentRepository;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 약관 문서 조회 — 종류별 canonical {@code major.minor} semantic maximum 계산의 단일 지점.
 *
 * <p>공개 조회 정렬은 클라이언트가 보낸 {@code termTypes} 순서가 권위다. DB의 {@code IN} 조회 결과
 * 순서는 보장되지 않으므로 종류별로 매핑한 뒤 요청 순서대로 재구성한다.
 * current 문서가 없는 종류는 결과에서 빠진다 — rollout 준비 상태의 정상 부분 결과이며
 * 공개 조회를 500으로 만들지 않는다(누락 경보는 {@code TermCatalogReadiness} 소유).
 */
@Service
@RequiredArgsConstructor
public class TermDocumentService {

    private final TermDocumentRepository termDocumentRepository;

    /** 요청 종류의 현재 문서(요청 순서) — 공개 조회용. */
    public List<TermDocument> findCurrentDocuments(String applicationVersion, List<TermType> termTypes) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return findCurrentDocuments(termTypes);
    }

    public List<TermDocument> findCurrentDocuments(Collection<TermType> termTypes) {
        if (termTypes.isEmpty()) {
            return List.of();
        }
        Map<TermType, TermDocument> documentsByType = new EnumMap<>(TermType.class);
        for (TermDocument candidate : termDocumentRepository.findDocumentCandidates(termTypes)) {
            documentsByType.merge(candidate.getTermType(), candidate,
                    (current, next) -> next.isNewerThan(current) ? next : current);
        }
        return termTypes.stream()
                .map(documentsByType::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 관리자용 전체 이력: 종류 순서, 같은 종류에서는 숫자 버전 내림차순. */
    public List<TermDocument> findAllDocuments() {
        return termDocumentRepository.findDocumentCandidates(List.of(TermType.values())).stream()
                .sorted((left, right) -> {
                    int typeOrder = left.getTermType().compareTo(right.getTermType());
                    if (typeOrder != 0) return typeOrder;
                    if (left.getVersion().equals(right.getVersion())) return 0;
                    return left.isNewerThan(right) ? -1 : 1;
                }).toList();
    }

    /**
     * 지정 종류들의 현재 문서 식별 요약 — 같은 엔티티 조회·최신 선택 후 필요한 key만 변환한다.
     */
    public List<TermDocumentSummary> findCurrentSummaries(Collection<TermType> termTypes) {
        return findCurrentDocuments(termTypes).stream()
                .map(document -> new TermDocumentSummary(document.getTermType(), document.getVersion()))
                .toList();
    }
}
