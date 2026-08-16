package com.laimory.server.terms.service;

import com.laimory.server.terms.TermStage;
import com.laimory.server.terms.TermTimes;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 약관 문서 조회 — 종류별 현재 버전({@code effectiveAt <= now(KST)} 최신 행) 계산의 단일 지점.
 *
 * <p>단계 소속·정렬은 DB {@code stage}/{@code display_order}가 아니라 {@link TermType} mapping을 쓴다.
 * 현재 유효 문서가 없는 종류는 결과에서 빠진다 — rollout/activation 준비 상태의 정상 부분 결과이며
 * 공개 조회를 500으로 만들지 않는다(누락 경보는 {@code TermCatalogReadiness} 소유).
 */
@Service
@RequiredArgsConstructor
public class TermDocumentService {

    private final TermDocumentRepository termDocumentRepository;
    private final Clock clock;

    /** 단계의 현재 문서(화면 순서 정렬) — 공개 조회용. 판정 시각은 지금 캡처한 instant의 KST 벽시계다. */
    public List<TermDocument> findCurrentDocuments(String applicationVersion, TermStage stage) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return findCurrentDocuments(TermType.typesOf(stage), TermTimes.kstWallClock(clock.instant()));
    }

    /** 지정 종류들의 현재 문서(화면 순서 정렬) — 호출자가 한 번 캡처한 판정 시각을 그대로 쓴다. */
    public List<TermDocument> findCurrentDocuments(Collection<TermType> termTypes, LocalDateTime nowKst) {
        if (termTypes.isEmpty()) {
            return List.of();
        }
        return termDocumentRepository.findCurrentDocuments(termTypes, nowKst).stream()
                .sorted(Comparator.comparingInt(document -> document.getTermType().displayOrder()))
                .toList();
    }
}
