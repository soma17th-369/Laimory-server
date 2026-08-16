package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.terms.TermStage;
import com.laimory.server.terms.TermType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 필수 약관 gate의 세 갈래 고정 — 미준비 catalog fail-open(부분 강제·5xx 없음), 미동의 403,
 * 전부 동의 통과.
 */
@ExtendWith(MockitoExtension.class)
class TermsEnforcementServiceTest {

    private static final long USER_ID = 7L;

    @Mock
    private TermCatalogReadiness termCatalogReadiness;
    @Mock
    private TermAgreementService termAgreementService;

    @InjectMocks
    private TermsEnforcementService service;

    private List<TermDocumentSummary> requiredDocuments;

    @BeforeEach
    void setUp() {
        requiredDocuments = List.of(
                new TermDocumentSummary(11L, TermType.TERMS_OF_SERVICE, "LOGIN", true, "2026-08-15"),
                new TermDocumentSummary(12L, TermType.PRIVACY_POLICY, "LOGIN", true, "2026-08-15"));
    }

    @Test
    void catalogNotReady_failsOpenWithoutAgreementQuery() {
        // seed/activation 누락·mapping 불일치 stage는 사용자 흐름을 막지 않고 metric으로 경보한다.
        when(termCatalogReadiness.checkStage(TermStage.LOGIN))
                .thenReturn(new TermCatalogReadiness.StageCatalog(false, List.of()));

        assertThatCode(() -> service.requireAgreements(TermStage.LOGIN, USER_ID)).doesNotThrowAnyException();

        verify(termCatalogReadiness).recordFailOpen(TermStage.LOGIN);
        verifyNoInteractions(termAgreementService);
    }

    @Test
    void missingRequiredAgreement_throws403TermsAgreementRequired() {
        when(termCatalogReadiness.checkStage(TermStage.LOGIN))
                .thenReturn(new TermCatalogReadiness.StageCatalog(true, requiredDocuments));
        when(termAgreementService.hasAgreedToAll(USER_ID, List.of(11L, 12L))).thenReturn(false);

        assertThatThrownBy(() -> service.requireAgreements(TermStage.LOGIN, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getExceptionType())
                .isEqualTo(ExceptionType.TERMS_AGREEMENT_REQUIRED);
    }

    @Test
    void allCurrentRequiredAgreed_passes() {
        when(termCatalogReadiness.checkStage(TermStage.TIMELINE_FIRST_CREATE))
                .thenReturn(new TermCatalogReadiness.StageCatalog(true, requiredDocuments));
        when(termAgreementService.hasAgreedToAll(USER_ID, List.of(11L, 12L))).thenReturn(true);

        assertThatCode(() -> service.requireAgreements(TermStage.TIMELINE_FIRST_CREATE, USER_ID))
                .doesNotThrowAnyException();
    }
}
