package com.laimory.server.terms.service;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.TermVersion;
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
        if (termTypes.isEmpty()) {
            return List.of();
        }
        Map<TermType, TermDocument> documentsByType = new EnumMap<>(TermType.class);
        for (TermDocument candidate : termDocumentRepository.findDocumentCandidates(termTypes)) {
            if (TermVersion.isCanonical(candidate.getVersion())) {
                documentsByType.merge(candidate.getTermType(), candidate, TermDocumentService::newerDocument);
            }
        }
        return termTypes.stream()
                .map(documentsByType::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 지정 종류들의 현재 문서 식별 요약 — 기동 catalog 검증(readiness)·동의 버전 검증용.
     */
    public List<TermDocumentSummary> findCurrentSummaries(Collection<TermType> termTypes) {
        if (termTypes.isEmpty()) {
            return List.of();
        }
        Map<TermType, TermDocumentSummary> documentsByType = new EnumMap<>(TermType.class);
        for (TermDocumentSummary candidate : termDocumentRepository.findDocumentSummaryCandidates(termTypes)) {
            if (TermVersion.isCanonical(candidate.version())) {
                documentsByType.merge(candidate.termType(), candidate, TermDocumentService::newerSummary);
            }
        }
        return termTypes.stream()
                .map(documentsByType::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private static TermDocument newerDocument(TermDocument left, TermDocument right) {
        return compare(left.getVersion(), right.getVersion()) >= 0 ? left : right;
    }

    private static TermDocumentSummary newerSummary(TermDocumentSummary left, TermDocumentSummary right) {
        return compare(left.version(), right.version()) >= 0 ? left : right;
    }

    private static int compare(String left, String right) {
        return TermVersion.parse(left).compareTo(TermVersion.parse(right));
    }
}
