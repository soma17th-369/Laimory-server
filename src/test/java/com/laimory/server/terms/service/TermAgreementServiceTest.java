package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocumentId;
import com.laimory.server.terms.repository.TermAgreementRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 동의 current 검증·복합 key 기록과 initializer의 미동의 current 차집합 계약을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TermAgreementServiceTest {

    private static final long USER_ID = 7L;
    private static final Clock UTC_CLOCK = Clock.fixed(Instant.parse("2026-08-15T20:30:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime EXPECTED_KST = LocalDateTime.parse("2026-08-16T05:30:00");

    @Mock
    private TermDocumentService termDocumentService;
    @Mock
    private TermAgreementTransactionService termAgreementTransactionService;
    @Mock
    private TermAgreementRepository termAgreementRepository;

    private TermAgreementService service;

    @BeforeEach
    void setUp() {
        service = new TermAgreementService(termDocumentService, termAgreementTransactionService,
                termAgreementRepository, UTC_CLOCK);
    }

    @Test
    void agree_recordsAllCurrentCompositeKeys_withSingleKstAcceptedAt() {
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of(
                summary(TermType.TERMS_OF_SERVICE, "1.0"),
                summary(TermType.SENSITIVE_INFORMATION_CONSENT, "1.10")));

        service.agreeToTerms("v1", USER_ID, List.of(
                new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.0"),
                new TermAgreementCommand(TermType.SENSITIVE_INFORMATION_CONSENT, "1.10")));

        verify(termAgreementTransactionService).recordAgreements(USER_ID, List.of(
                new TermDocumentId(TermType.TERMS_OF_SERVICE, "1.0"),
                new TermDocumentId(TermType.SENSITIVE_INFORMATION_CONSENT, "1.10")), EXPECTED_KST);
    }

    @Test
    void agree_staleOrMissingDocument_rejects409WithoutRecordingAnything() {
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of(
                summary(TermType.TERMS_OF_SERVICE, "1.1")));

        assertThatThrownBy(() -> service.agreeToTerms("v1", USER_ID, List.of(
                new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.0"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getExceptionType())
                .isEqualTo(ExceptionType.STALE_TERM_VERSION);
        verifyNoInteractions(termAgreementTransactionService);

        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of());
        assertThatThrownBy(() -> service.agreeToTerms("v1", USER_ID, List.of(
                new TermAgreementCommand(TermType.SENSITIVE_INFORMATION_CONSENT, "1.0"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getExceptionType())
                .isEqualTo(ExceptionType.STALE_TERM_VERSION);
        verifyNoInteractions(termAgreementTransactionService);
    }

    @Test
    void agree_rejectsInvalidShapeAndNonCanonicalVersionsBeforeQuery() {
        List<List<TermAgreementCommand>> invalidRequests = List.of(
                List.of(),
                List.of(new TermAgreementCommand(null, "1.0")),
                List.of(new TermAgreementCommand(TermType.TERMS_OF_SERVICE, null)),
                List.of(new TermAgreementCommand(TermType.TERMS_OF_SERVICE, " ")),
                List.of(new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1")),
                List.of(new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "01.0")),
                List.of(new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.01")),
                List.of(new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.0.0")),
                List.of(new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.0"),
                        new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.0")));

        assertThatThrownBy(() -> service.agreeToTerms("v1", USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        for (List<TermAgreementCommand> request : invalidRequests) {
            assertThatThrownBy(() -> service.agreeToTerms("v1", USER_ID, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(termDocumentService, termAgreementTransactionService);
    }

    @Test
    void agree_sameTypeDifferentCanonicalVersions_isNotDuplicate_butStaleOneRejects() {
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of(
                summary(TermType.TERMS_OF_SERVICE, "1.10")));

        assertThatThrownBy(() -> service.agreeToTerms("v1", USER_ID, List.of(
                new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.10"),
                new TermAgreementCommand(TermType.TERMS_OF_SERVICE, "1.9"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getExceptionType())
                .isEqualTo(ExceptionType.STALE_TERM_VERSION);
    }

    @Test
    void history_delegatesToOwnerScopedQuery() {
        when(termAgreementRepository.findHistoryByUserId(USER_ID)).thenReturn(List.of());

        assertThat(service.getHistory("v1", USER_ID)).isEmpty();
        verify(termAgreementRepository).findHistoryByUserId(USER_ID);
    }

    @Test
    void agreementRequired_targetsAllConsentTypesExceptPrivacyPolicy() {
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of());

        assertThat(service.findAgreementRequiredTerms(USER_ID)).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<TermType>> types = ArgumentCaptor.forClass(Collection.class);
        verify(termDocumentService).findCurrentSummaries(types.capture());
        assertThat(types.getValue()).containsExactlyInAnyOrder(TermType.TERMS_OF_SERVICE,
                TermType.SENSITIVE_INFORMATION_CONSENT, TermType.THIRD_PARTY_PROVISION_CONSENT,
                TermType.CROSS_BORDER_TRANSFER_CONSENT, TermType.LOCATION_BASED_SERVICE_TERMS);
        verifyNoInteractions(termAgreementRepository);
    }

    @Test
    void agreementRequired_subtractsAgreedCurrentKeys_andSortsByEnum() {
        TermDocumentSummary terms = summary(TermType.TERMS_OF_SERVICE, "1.1");
        TermDocumentSummary sensitive = summary(TermType.SENSITIVE_INFORMATION_CONSENT, "1.0");
        TermDocumentSummary location = summary(TermType.LOCATION_BASED_SERVICE_TERMS, "1.0");
        when(termDocumentService.findCurrentSummaries(anyCollection()))
                .thenReturn(List.of(location, terms, sensitive));
        when(termAgreementRepository.findAgreedDocumentKeys(eq(USER_ID), anyCollection()))
                .thenReturn(List.of(sensitive));

        assertThat(service.findAgreementRequiredTerms(USER_ID)).containsExactly(terms, location);
    }

    @Test
    void agreementRequired_missingCurrentTypes_areSkippedAndPreparedTypeRemains() {
        TermDocumentSummary onlyReady = summary(TermType.TERMS_OF_SERVICE, "1.0");
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of(onlyReady));
        when(termAgreementRepository.findAgreedDocumentKeys(eq(USER_ID), anyCollection())).thenReturn(List.of());

        assertThat(service.findAgreementRequiredTerms(USER_ID)).containsExactly(onlyReady);
    }

    private static TermDocumentSummary summary(TermType type, String version) {
        return new TermDocumentSummary(type, version);
    }
}
