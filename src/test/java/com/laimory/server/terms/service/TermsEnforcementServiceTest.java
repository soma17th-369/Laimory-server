package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.terms.TermType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 단일 필수 약관 gate의 fail-open, 미동의 거절, 전체 동의 통과를 검증한다. */
@ExtendWith(MockitoExtension.class)
class TermsEnforcementServiceTest {

    private static final long USER_ID = 7L;

    @Mock
    private TermCatalogReadiness termCatalogReadiness;
    @Mock
    private TermAgreementService termAgreementService;

    @InjectMocks
    private TermsEnforcementService service;

    @Test
    void catalogNotReady_failsOpenWithoutAgreementQuery() {
        when(termCatalogReadiness.check())
                .thenReturn(new TermCatalogReadiness.Catalog(false, List.of()));

        assertThatCode(() -> service.requireAgreements(USER_ID)).doesNotThrowAnyException();

        verify(termCatalogReadiness).recordFailOpen();
        verifyNoInteractions(termAgreementService);
    }

    @Test
    void missingAnyRequiredAgreement_throws403TermsAgreementRequired() {
        List<TermDocumentSummary> documents = enforcedDocuments();
        when(termCatalogReadiness.check()).thenReturn(new TermCatalogReadiness.Catalog(true, documents));
        when(termAgreementService.hasAgreedToAll(USER_ID, List.of(11L, 12L, 13L, 14L, 15L)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.requireAgreements(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getExceptionType())
                .isEqualTo(ExceptionType.TERMS_AGREEMENT_REQUIRED);
    }

    @Test
    void allCurrentRequiredAgreed_passes() {
        List<TermDocumentSummary> documents = enforcedDocuments();
        when(termCatalogReadiness.check()).thenReturn(new TermCatalogReadiness.Catalog(true, documents));
        when(termAgreementService.hasAgreedToAll(USER_ID, List.of(11L, 12L, 13L, 14L, 15L)))
                .thenReturn(true);

        assertThatCode(() -> service.requireAgreements(USER_ID)).doesNotThrowAnyException();
    }

    private static List<TermDocumentSummary> enforcedDocuments() {
        return List.of(
                new TermDocumentSummary(11L, TermType.TERMS_OF_SERVICE, "1.0"),
                new TermDocumentSummary(12L, TermType.SENSITIVE_INFORMATION_CONSENT, "1.0"),
                new TermDocumentSummary(13L, TermType.THIRD_PARTY_PROVISION_CONSENT, "1.0"),
                new TermDocumentSummary(14L, TermType.CROSS_BORDER_TRANSFER_CONSENT, "1.0"),
                new TermDocumentSummary(15L, TermType.LOCATION_BASED_SERVICE_TERMS, "1.0"));
    }
}
