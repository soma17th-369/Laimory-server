package com.laimory.server.initializer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.initializer.dto.AgreementRequiredTermResponse;
import com.laimory.server.initializer.dto.InitializerResponse;
import com.laimory.server.push.service.SubjectPreferenceService;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.service.TermAgreementService;
import com.laimory.server.terms.service.TermDocumentSummary;
import com.laimory.server.testsupport.TestSubjects;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 앱 초기화 orchestration 검증 — leaf 결과를 그대로 응답으로 옮기고(subjectId는 온보딩, userId는 약관 —
 * 두 principal을 섞지 않는다), leaf 예외를 기본값으로 삼키지 않는 계약을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AppInitializerServiceTest {

    private static final long USER_ID = 91L;
    private static final UUID SUBJECT_ID = TestSubjects.id(USER_ID);

    @Mock
    private SubjectPreferenceService subjectPreferenceService;

    @Mock
    private TermAgreementService termAgreementService;

    private AppInitializerService service() {
        return new AppInitializerService(subjectPreferenceService, termAgreementService);
    }

    @Test
    void getInitialState_composesOnboardingBySubject_andTermsByUserId() {
        when(subjectPreferenceService.findOnboardingCompleted(SUBJECT_ID)).thenReturn(true);
        when(termAgreementService.findAgreementRequiredTerms(USER_ID)).thenReturn(List.of(
                new TermDocumentSummary(TermType.TERMS_OF_SERVICE, "1.1"),
                new TermDocumentSummary(TermType.LOCATION_BASED_SERVICE_TERMS, "2.0")));

        InitializerResponse response = service().getInitialState("v1", USER_ID, SUBJECT_ID);

        assertThat(response.onboardingCompleted()).isTrue();
        assertThat(response.terms().agreementRequired()).containsExactly(
                new AgreementRequiredTermResponse(TermType.TERMS_OF_SERVICE, "1.1"),
                new AgreementRequiredTermResponse(TermType.LOCATION_BASED_SERVICE_TERMS, "2.0"));
        verify(subjectPreferenceService).findOnboardingCompleted(SUBJECT_ID);
        verify(termAgreementService).findAgreementRequiredTerms(USER_ID);
    }

    @Test
    void getInitialState_noAgreementRequired_returnsEmptyListNotNull() {
        when(subjectPreferenceService.findOnboardingCompleted(SUBJECT_ID)).thenReturn(false);
        when(termAgreementService.findAgreementRequiredTerms(USER_ID)).thenReturn(List.of());

        InitializerResponse response = service().getInitialState("v1", USER_ID, SUBJECT_ID);

        assertThat(response.onboardingCompleted()).isFalse();
        assertThat(response.terms().agreementRequired()).isEmpty();
    }

    @Test
    void getInitialState_doesNotSwallowMissingRowIntoDefault() {
        // 행 부재를 false로 바꿔 답하면 앱은 온보딩을 다시 태우고, 그 완료 요청은 다시 실패한다.
        when(subjectPreferenceService.findOnboardingCompleted(SUBJECT_ID))
                .thenThrow(new IllegalStateException("subject preference row is missing"));

        assertThatThrownBy(() -> service().getInitialState("v1", USER_ID, SUBJECT_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getInitialState_doesNotSwallowTermsLeafFailureIntoEmptyList() {
        when(subjectPreferenceService.findOnboardingCompleted(SUBJECT_ID)).thenReturn(true);
        when(termAgreementService.findAgreementRequiredTerms(USER_ID))
                .thenThrow(new IllegalStateException("terms judgment failed"));

        assertThatThrownBy(() -> service().getInitialState("v1", USER_ID, SUBJECT_ID))
                .isInstanceOf(IllegalStateException.class);
    }
}
