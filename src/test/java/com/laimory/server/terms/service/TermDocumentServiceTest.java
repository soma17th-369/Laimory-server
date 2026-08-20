package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.terms.TermStage;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 현재 문서 조회의 시각 축(UTC Clock → KST 벽시계)과 화면 순서 정렬(enum displayOrder — DB 순서 무시)
 * 검증.
 */
@ExtendWith(MockitoExtension.class)
class TermDocumentServiceTest {

    @Mock
    private TermDocumentRepository termDocumentRepository;

    @Test
    void currentSelection_usesKstWallClock_evenWithUtcClock() {
        // UTC 2026-08-15T20:00 = KST 2026-08-16T05:00 — 판정 기준은 KST 벽시계다.
        Clock utcClock = Clock.fixed(Instant.parse("2026-08-15T20:00:00Z"), ZoneOffset.UTC);
        TermDocumentService service = new TermDocumentService(termDocumentRepository, utcClock);
        when(termDocumentRepository.findCurrentDocuments(anyCollection(), any())).thenReturn(List.of());

        service.findCurrentDocuments("v1", TermStage.LOGIN);

        ArgumentCaptor<LocalDateTime> nowKst = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(termDocumentRepository).findCurrentDocuments(
                eq(TermType.typesOf(TermStage.LOGIN)), nowKst.capture());
        assertThat(nowKst.getValue()).isEqualTo(LocalDateTime.parse("2026-08-16T05:00:00"));
    }

    @Test
    void results_areSortedByEnumDisplayOrder_notDbOrder() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC);
        TermDocumentService service = new TermDocumentService(termDocumentRepository, clock);
        TermDocument privacy = TermDocument.of(TermType.PRIVACY_POLICY, "1.0", "개인정보 처리방침",
                LocalDateTime.parse("2026-08-01T00:00:00"));
        TermDocument terms = TermDocument.of(TermType.TERMS_OF_SERVICE, "1.0", "이용약관",
                LocalDateTime.parse("2026-08-01T00:00:00"));
        when(termDocumentRepository.findCurrentDocuments(anyCollection(), any()))
                .thenReturn(List.of(privacy, terms)); // DB가 역순으로 줘도

        List<TermDocument> result = service.findCurrentDocuments("v1", TermStage.LOGIN);

        assertThat(result).containsExactly(terms, privacy);
    }

    @Test
    void summaries_useIdentityProjection_sortedByEnumDisplayOrder() {
        // enforcement/readiness 경로 — 판정에 쓰는 식별 요약 쿼리에 위임하고 화면 순서로 정렬한다.
        TermDocumentService service = new TermDocumentService(termDocumentRepository,
                Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));
        TermDocumentSummary privacy = new TermDocumentSummary(12L, TermType.PRIVACY_POLICY, "1.0");
        TermDocumentSummary terms = new TermDocumentSummary(11L, TermType.TERMS_OF_SERVICE, "1.0");
        LocalDateTime nowKst = LocalDateTime.parse("2026-08-15T09:00:00");
        when(termDocumentRepository.findCurrentDocumentSummaries(TermType.typesOf(TermStage.LOGIN), nowKst))
                .thenReturn(List.of(privacy, terms)); // DB가 역순으로 줘도

        assertThat(service.findCurrentSummaries(TermType.typesOf(TermStage.LOGIN), nowKst))
                .containsExactly(terms, privacy);
    }

    @Test
    void emptyTypeSet_shortCircuitsWithoutQuery() {
        TermDocumentService service = new TermDocumentService(termDocumentRepository,
                Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));

        assertThat(service.findCurrentDocuments(List.of(), LocalDateTime.parse("2026-08-15T00:00:00")))
                .isEmpty();
        assertThat(service.findCurrentSummaries(List.of(), LocalDateTime.parse("2026-08-15T00:00:00")))
                .isEmpty();
        verifyNoInteractions(termDocumentRepository);
    }
}
