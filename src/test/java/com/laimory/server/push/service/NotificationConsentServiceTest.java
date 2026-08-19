package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.NotificationConsentSource;
import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.PushSenderProperties;
import com.laimory.server.push.repository.NotificationConsentEventRepository;
import com.laimory.server.push.repository.NotificationConsentRepository;
import com.laimory.server.push.service.NotificationConsentTransactionService.ConsentCommand;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.service.TermDocumentService;
import com.laimory.server.terms.service.TermDocumentSummary;
import com.laimory.server.testsupport.TestSubjects;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 동의 orchestration 검증 — 문서 버전 검증과 종류별 위임. 상태 판정(APPLIED/ALREADY_IN_STATE)은
 * 조건부 UPDATE의 영향 행 수가 소유하므로 {@link NotificationConsentTransactionServiceTest}가 검증한다.
 * 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class NotificationConsentServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(21L);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T05:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 7, 21, 14, 0);
    private static final TermDocumentSummary AD_DOC =
            new TermDocumentSummary(100L, TermType.ADVERTISING_PUSH_CONSENT, "PUSH_SETTINGS", false, "v1");
    private static final TermDocumentSummary NIGHT_DOC =
            new TermDocumentSummary(200L, TermType.NIGHT_ADVERTISING_PUSH_CONSENT, "PUSH_SETTINGS", false, "v1");

    @Mock
    private NotificationConsentRepository consentRepository;
    @Mock
    private NotificationConsentEventRepository eventRepository;
    @Mock
    private NotificationConsentTransactionService transactionService;
    @Mock
    private TermDocumentService termDocumentService;

    @Captor
    private ArgumentCaptor<ConsentCommand> commandCaptor;

    private final PushSenderProperties sender = new PushSenderProperties("라이모리 주식회사", "help@laimory.app");

    private NotificationConsentService service() {
        return new NotificationConsentService(consentRepository, eventRepository, transactionService,
                termDocumentService, sender, CLOCK);
    }

    private void givenCurrentDocument(TermDocumentSummary document) {
        when(termDocumentService.findCurrentSummaries(List.of(document.termType()), NOW_KST))
                .thenReturn(List.of(document));
    }

    // --- 위임과 command 조립 ---

    @Test
    void consentAdvertising_delegatesWithResolvedDocumentAndServerCapturedTime() {
        givenCurrentDocument(AD_DOC);

        service().apply(SUBJECT_ID, NotificationConsentType.ADVERTISING_PUSH, true, "v1",
                NotificationConsentSource.PUSH_SETTINGS);

        verify(transactionService).consentAdvertising(commandCaptor.capture(), eqLong(100L));
        ConsentCommand command = commandCaptor.getValue();
        assertThat(command.subjectId()).isEqualTo(SUBJECT_ID);
        assertThat(command.occurredAt()).isEqualTo(NOW_KST);
        assertThat(command.senderName()).isEqualTo("라이모리 주식회사");
        assertThat(command.source()).isEqualTo(NotificationConsentSource.PUSH_SETTINGS);
    }

    @Test
    void consentNight_delegatesToNightTransition() {
        givenCurrentDocument(NIGHT_DOC);

        service().apply(SUBJECT_ID, NotificationConsentType.NIGHT_ADVERTISING_PUSH, true, "v1",
                NotificationConsentSource.PUSH_SETTINGS);

        verify(transactionService).consentNight(any(), eqLong(200L));
        verify(transactionService, never()).consentAdvertising(any(), any());
    }

    @Test
    void withdrawAdvertising_delegatesToCascadingWithdrawal() {

        service().apply(SUBJECT_ID, NotificationConsentType.ADVERTISING_PUSH, false, null,
                NotificationConsentSource.INSTALLATION_OPT_OUT);

        verify(transactionService).withdrawAdvertising(commandCaptor.capture());
        assertThat(commandCaptor.getValue().source())
                .isEqualTo(NotificationConsentSource.INSTALLATION_OPT_OUT);
        // 철회는 문서 버전을 요구하지 않는다 — 현재 문서 조회 자체가 없다.
        verify(termDocumentService, never()).findCurrentSummaries(any(), any());
    }

    @Test
    void withdrawNight_leavesAdvertisingTransitionUntouched() {

        service().apply(SUBJECT_ID, NotificationConsentType.NIGHT_ADVERTISING_PUSH, false, null,
                NotificationConsentSource.PUSH_SETTINGS);

        verify(transactionService).withdrawNight(any());
        verify(transactionService, never()).withdrawAdvertising(any());
    }

    // --- 문서 버전 검증 ---

    @Test
    void consent_staleVersion_isRejectedBeforeAnyTransition() {
        givenCurrentDocument(AD_DOC);

        assertThatThrownBy(() -> service().apply(SUBJECT_ID, NotificationConsentType.ADVERTISING_PUSH, true, "v0", NotificationConsentSource.PUSH_SETTINGS))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType()).isEqualTo(ExceptionType.STALE_TERM_VERSION));
        verify(transactionService, never()).consentAdvertising(any(), any());
    }

    @Test
    void consent_missingVersion_isRejected() {

        assertThatThrownBy(() -> service().apply(SUBJECT_ID, NotificationConsentType.ADVERTISING_PUSH, true, null, NotificationConsentSource.PUSH_SETTINGS))
                .isInstanceOf(IllegalArgumentException.class);
        verify(transactionService, never()).consentAdvertising(any(), any());
    }

    // --- 조회 ---

    @Test
    void findState_missingRowMeansNoConsent() {
        when(consentRepository.findById(SUBJECT_ID)).thenReturn(java.util.Optional.empty());

        NotificationConsentService.ConsentState state = service().findState(SUBJECT_ID);

        assertThat(state.advertisingConsented()).isFalse();
        assertThat(state.nightAdvertisingConsented()).isFalse();
    }

    @Test
    void findStatesBySubjectIds_emptyInput_skipsQuery() {
        assertThat(service().findStatesBySubjectIds(List.of())).isEmpty();
        verify(consentRepository, never()).findAllBySubjectIdIn(any());
    }

    private static Long eqLong(long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
