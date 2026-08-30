package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

/** 현재 문서 조회의 KST 시각 축과 공개 응답의 요청 순서 보존을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TermDocumentServiceTest {

    @Mock
    private TermDocumentRepository termDocumentRepository;

    @Test
    void currentSelection_usesKstWallClock_evenWithUtcClock() {
        Clock utcClock = Clock.fixed(Instant.parse("2026-08-15T20:00:00Z"), ZoneOffset.UTC);
        TermDocumentService service = new TermDocumentService(termDocumentRepository, utcClock);
        when(termDocumentRepository.findCurrentDocuments(anyCollection(), any())).thenReturn(List.of());

        service.findCurrentDocuments("v1", List.of(TermType.TERMS_OF_SERVICE));

        ArgumentCaptor<LocalDateTime> nowKst = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(termDocumentRepository).findCurrentDocuments(
                eq(List.of(TermType.TERMS_OF_SERVICE)), nowKst.capture());
        assertThat(nowKst.getValue()).isEqualTo(LocalDateTime.parse("2026-08-16T05:00:00"));
    }

    @Test
    void results_followRequestedOrder_notDbInQueryOrder() {
        TermDocumentService service = service();
        TermDocument location = document(TermType.LOCATION_BASED_SERVICE_TERMS, "위치약관");
        TermDocument terms = document(TermType.TERMS_OF_SERVICE, "이용약관");
        when(termDocumentRepository.findCurrentDocuments(anyCollection(), any()))
                .thenReturn(List.of(terms, location));

        List<TermDocument> result = service.findCurrentDocuments("v1", List.of(
                TermType.LOCATION_BASED_SERVICE_TERMS,
                TermType.TERMS_OF_SERVICE));

        assertThat(result).containsExactly(location, terms);
    }

    @Test
    void missingCurrentDocument_isSkippedWithoutChangingRelativeRequestOrder() {
        TermDocumentService service = service();
        TermDocument terms = document(TermType.TERMS_OF_SERVICE, "이용약관");
        TermDocument privacy = document(TermType.PRIVACY_POLICY, "개인정보 처리방침");
        when(termDocumentRepository.findCurrentDocuments(anyCollection(), any()))
                .thenReturn(List.of(terms, privacy));

        List<TermDocument> result = service.findCurrentDocuments("v1", List.of(
                TermType.PRIVACY_POLICY,
                TermType.LOCATION_BASED_SERVICE_TERMS,
                TermType.TERMS_OF_SERVICE));

        assertThat(result).containsExactly(privacy, terms);
    }

    @Test
    void summaries_delegateRepositoryResultWithoutDisplayOrder() {
        TermDocumentService service = service();
        List<TermType> requested = List.of(
                TermType.THIRD_PARTY_PROVISION_CONSENT,
                TermType.SENSITIVE_INFORMATION_CONSENT);
        List<TermDocumentSummary> repositoryResult = List.of(
                new TermDocumentSummary(12L, TermType.SENSITIVE_INFORMATION_CONSENT, "1.0"),
                new TermDocumentSummary(13L, TermType.THIRD_PARTY_PROVISION_CONSENT, "1.0"));
        LocalDateTime nowKst = LocalDateTime.parse("2026-08-15T09:00:00");
        when(termDocumentRepository.findCurrentDocumentSummaries(requested, nowKst))
                .thenReturn(repositoryResult);

        assertThat(service.findCurrentSummaries(requested, nowKst)).isSameAs(repositoryResult);
    }

    @Test
    void emptyTypeList_shortCircuitsWithoutQuery() {
        TermDocumentService service = service();

        assertThat(service.findCurrentDocuments(List.of(), LocalDateTime.parse("2026-08-15T00:00:00")))
                .isEmpty();
        assertThat(service.findCurrentSummaries(List.of(), LocalDateTime.parse("2026-08-15T00:00:00")))
                .isEmpty();
        verifyNoInteractions(termDocumentRepository);
    }

    private TermDocumentService service() {
        return new TermDocumentService(termDocumentRepository,
                Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));
    }

    private static TermDocument document(TermType type, String title) {
        return TermDocument.of(type, "1.0", title, "https://laimory.app/terms/page/1.0",
                LocalDateTime.parse("2026-08-01T00:00:00"));
    }
}
