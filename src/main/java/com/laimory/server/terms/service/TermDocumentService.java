package com.laimory.server.terms.service;

import com.laimory.server.terms.TermTimes;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 약관 문서 조회 — 종류별 현재 버전({@code effectiveAt <= now(KST)} 최신 행) 계산의 단일 지점.
 *
 * <p>공개 조회 정렬은 클라이언트가 보낸 {@code termTypes} 순서가 권위다. DB의 {@code IN} 조회 결과
 * 순서는 보장되지 않으므로 종류별로 매핑한 뒤 요청 순서대로 재구성한다.
 * 현재 유효 문서가 없는 종류는 결과에서 빠진다 — rollout/activation 준비 상태의 정상 부분 결과이며
 * 공개 조회를 500으로 만들지 않는다(누락 경보는 {@code TermCatalogReadiness} 소유).
 */
@Service
@RequiredArgsConstructor
public class TermDocumentService {

    private final TermDocumentRepository termDocumentRepository;
    private final Clock clock;

    /** 요청 종류의 현재 문서(요청 순서) — 공개 조회용. 판정 시각은 지금 캡처한 instant의 KST 벽시계다. */
    public List<TermDocument> findCurrentDocuments(String applicationVersion, List<TermType> termTypes) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return findCurrentDocuments(termTypes, TermTimes.kstWallClock(clock.instant()));
    }

    /** 지정 종류들의 현재 문서(요청 순서) — 호출자가 한 번 캡처한 판정 시각을 그대로 쓴다. */
    public List<TermDocument> findCurrentDocuments(List<TermType> termTypes, LocalDateTime nowKst) {
        if (termTypes.isEmpty()) {
            return List.of();
        }
        Map<TermType, TermDocument> documentsByType = new EnumMap<>(TermType.class);
        termDocumentRepository.findCurrentDocuments(termTypes, nowKst)
                .forEach(document -> documentsByType.put(document.getTermType(), document));
        return termTypes.stream()
                .map(documentsByType::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 지정 종류들의 현재 문서 식별 요약 — enforcement/readiness/동의 버전 검증용.
     * gate가 모든 비면제 {@code /a/api} 요청에서 호출되므로 판정에 쓰는 컬럼만 투영한다.
     */
    public List<TermDocumentSummary> findCurrentSummaries(Collection<TermType> termTypes, LocalDateTime nowKst) {
        if (termTypes.isEmpty()) {
            return List.of();
        }
        return termDocumentRepository.findCurrentDocumentSummaries(termTypes, nowKst);
    }
}
