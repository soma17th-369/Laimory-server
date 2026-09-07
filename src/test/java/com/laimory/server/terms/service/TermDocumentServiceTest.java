package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 후보 전체를 한 번 읽어 semantic current를 고르고 요청 순서를 복원하는 계약을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TermDocumentServiceTest {

    @Mock
    private TermDocumentRepository termDocumentRepository;

    private TermDocumentService service;

    @BeforeEach
    void setUp() {
        service = new TermDocumentService(termDocumentRepository);
    }

    @Test
    void currentSelection_comparesMajorAndMinorNumerically_andPreservesRequestedOrder() {
        TermDocument terms19 = document(TermType.TERMS_OF_SERVICE, "1.9");
        TermDocument terms110 = document(TermType.TERMS_OF_SERVICE, "1.10");
        TermDocument terms20 = document(TermType.TERMS_OF_SERVICE, "2.0");
        TermDocument privacy199 = document(TermType.PRIVACY_POLICY, "1.99");
        when(termDocumentRepository.findDocumentCandidates(anyCollection()))
                .thenReturn(List.of(terms110, privacy199, terms20, terms19));

        List<TermDocument> result = service.findCurrentDocuments("v1", List.of(
                TermType.PRIVACY_POLICY, TermType.TERMS_OF_SERVICE));

        assertThat(result).containsExactly(privacy199, terms20);
        verify(termDocumentRepository).findDocumentCandidates(
                List.of(TermType.PRIVACY_POLICY, TermType.TERMS_OF_SERVICE));
    }

    @Test
    void summarySelection_mapsTheSameEntitySelectionAfterOneQuery() {
        List<TermType> requested = List.of(
                TermType.THIRD_PARTY_PROVISION_CONSENT,
                TermType.SENSITIVE_INFORMATION_CONSENT);
        when(termDocumentRepository.findDocumentCandidates(requested)).thenReturn(List.of(
                document(TermType.SENSITIVE_INFORMATION_CONSENT, "1.9"),
                document(TermType.THIRD_PARTY_PROVISION_CONSENT, "1.0"),
                document(TermType.SENSITIVE_INFORMATION_CONSENT, "1.10")));

        assertThat(service.findCurrentSummaries(requested)).containsExactly(
                new TermDocumentSummary(TermType.THIRD_PARTY_PROVISION_CONSENT, "1.0"),
                new TermDocumentSummary(TermType.SENSITIVE_INFORMATION_CONSENT, "1.10"));
        verify(termDocumentRepository).findDocumentCandidates(requested);
    }

    @Test
    void missingCandidates_areSkippedWithoutChangingRelativeRequestOrder() {
        when(termDocumentRepository.findDocumentCandidates(anyCollection())).thenReturn(List.of(
                document(TermType.TERMS_OF_SERVICE, "1.0"),
                document(TermType.PRIVACY_POLICY, "1.0")));

        assertThat(service.findCurrentDocuments("v1", List.of(
                TermType.PRIVACY_POLICY,
                TermType.LOCATION_BASED_SERVICE_TERMS,
                TermType.TERMS_OF_SERVICE)))
                .extracting(TermDocument::getTermType)
                .containsExactly(TermType.PRIVACY_POLICY, TermType.TERMS_OF_SERVICE);
    }

    @Test
    void emptyTypeList_shortCircuitsWithoutQuery() {
        assertThat(service.findCurrentDocuments("v1", List.of())).isEmpty();
        assertThat(service.findCurrentSummaries(List.of())).isEmpty();
        verifyNoInteractions(termDocumentRepository);
    }

    private static TermDocument document(TermType type, String version) {
        return TermDocument.of(type, version, type.name(), "https://www.laimory.app/terms/page/" + version);
    }
}
