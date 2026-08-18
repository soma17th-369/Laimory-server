package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.NotificationConsentAction;
import com.laimory.server.push.NotificationConsentProcessingResult;
import com.laimory.server.push.NotificationConsentSource;
import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.PushSenderProperties;
import com.laimory.server.push.entity.NotificationConsent;
import com.laimory.server.push.entity.NotificationConsentEvent;
import com.laimory.server.push.repository.NotificationConsentEventRepository;
import com.laimory.server.push.repository.NotificationConsentRepository;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.service.TermDocumentService;
import com.laimory.server.terms.service.TermDocumentSummary;
import com.laimory.server.testsupport.TestSubjects;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 수신 동의 leaf 검증 — 문서 버전 검증, 상태 전이와 처리결과 분류, 야간 동의의 전제 조건,
 * 일반 철회의 야간 동반 철회, {@code clientRequestId} 멱등과 payload 불일치 거절. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class NotificationConsentServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(21L);
    private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
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
    private ArgumentCaptor<List<NotificationConsentEvent>> eventsCaptor;

    private final PushSenderProperties sender = new PushSenderProperties("라이모리 주식회사", "help@laimory.app");

    private NotificationConsentService service() {
        return new NotificationConsentService(consentRepository, eventRepository, transactionService,
                termDocumentService, sender, CLOCK);
    }

    private static NotificationConsent snapshot(boolean advertising, Long adDocId,
                                                boolean night, Long nightDocId) {
        NotificationConsent consent = new NotificationConsent() {
        };
        ReflectionTestUtils.setField(consent, "subjectId", SUBJECT_ID);
        ReflectionTestUtils.setField(consent, "advertisingPushConsented", advertising);
        ReflectionTestUtils.setField(consent, "advertisingTermDocumentId", adDocId);
        ReflectionTestUtils.setField(consent, "nightAdvertisingPushConsented", night);
        ReflectionTestUtils.setField(consent, "nightTermDocumentId", nightDocId);
        return consent;
    }

    private void givenSnapshot(NotificationConsent consent) {
        when(consentRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(consent));
    }

    private void givenNoPriorEvents() {
        when(eventRepository.findAllBySubjectIdAndClientRequestId(SUBJECT_ID, REQUEST_ID)).thenReturn(List.of());
    }

    private void givenCurrentDocument(TermDocumentSummary document) {
        when(termDocumentService.findCurrentSummaries(List.of(document.termType()), NOW_KST))
                .thenReturn(List.of(document));
    }

    // --- 동의 ---

    @Test
    void consentAdvertising_appliesAndRecordsEventWithCurrentDocument() {
        givenNoPriorEvents();
        givenSnapshot(snapshot(false, null, false, null));
        givenCurrentDocument(AD_DOC);

        service().apply(SUBJECT_ID, REQUEST_ID, NotificationConsentType.ADVERTISING_PUSH, true, "v1",
                NotificationConsentSource.PUSH_SETTINGS);

        verify(transactionService).consentAdvertising(eq(SUBJECT_ID), eq(100L), eq(NOW_KST),
                eventsCaptor.capture());
        assertThat(eventsCaptor.getValue()).singleElement().satisfies(event -> {
            assertThat(event.getConsentType()).isEqualTo(NotificationConsentType.ADVERTISING_PUSH);
            assertThat(event.getAction()).isEqualTo(NotificationConsentAction.CONSENT);
            assertThat(event.getTermDocumentId()).isEqualTo(100L);
            assertThat(event.getProcessingResult())
                    .isEqualTo(NotificationConsentProcessingResult.APPLIED);
            assertThat(event.getSenderName()).isEqualTo("라이모리 주식회사");
            assertThat(event.getOccurredAt()).isEqualTo(NOW_KST);
        });
    }

    @Test
    void consentAdvertising_sameDocumentAgain_recordsAlreadyInStateWithoutTouchingSnapshot() {
        // 새 의사 표시라 증적은 남기되 동의 시각은 덮어쓰지 않는다.
        givenNoPriorEvents();
        givenSnapshot(snapshot(true, 100L, false, null));
        givenCurrentDocument(AD_DOC);

        service().apply(SUBJECT_ID, REQUEST_ID, NotificationConsentType.ADVERTISING_PUSH, true, "v1",
                NotificationConsentSource.PUSH_SETTINGS);

        verify(transactionService).recordEventsOnly(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue()).singleElement().satisfies(event ->
                assertThat(event.getProcessingResult())
                        .isEqualTo(NotificationConsentProcessingResult.ALREADY_IN_STATE));
        verify(transactionService, never()).consentAdvertising(any(), any(), any(), anyList());
    }

    @Test
    void consentAdvertising_newerDocumentVersion_reappliesWithUpdatedDocument() {
        givenNoPriorEvents();
        givenSnapshot(snapshot(true, 100L, false, null));
        TermDocumentSummary v2 =
                new TermDocumentSummary(101L, TermType.ADVERTISING_PUSH_CONSENT, "PUSH_SETTINGS", false, "v2");
        givenCurrentDocument(v2);

        service().apply(SUBJECT_ID, REQUEST_ID, NotificationConsentType.ADVERTISING_PUSH, true, "v2",
                NotificationConsentSource.PUSH_SETTINGS);

        verify(transactionService).consentAdvertising(eq(SUBJECT_ID), eq(101L), eq(NOW_KST), anyList());
    }

    @Test
    void consent_staleVersion_isRejectedBeforeAnyWrite() {
        givenNoPriorEvents();
        givenSnapshot(snapshot(false, null, false, null));
        givenCurrentDocument(AD_DOC);

        assertThatThrownBy(() -> service().apply(SUBJECT_ID, REQUEST_ID,
                NotificationConsentType.ADVERTISING_PUSH, true, "v0", NotificationConsentSource.PUSH_SETTINGS))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType()).isEqualTo(ExceptionType.STALE_TERM_VERSION));
        verify(transactionService, never()).consentAdvertising(any(), any(), any(), anyList());
        verify(transactionService, never()).recordEventsOnly(anyList());
    }

    @Test
    void consentNight_withoutAdvertisingConsent_isRejected() {
        givenNoPriorEvents();
        givenSnapshot(snapshot(false, null, false, null));

        assertThatThrownBy(() -> service().apply(SUBJECT_ID, REQUEST_ID,
                NotificationConsentType.NIGHT_ADVERTISING_PUSH, true, "v1",
                NotificationConsentSource.PUSH_SETTINGS))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType())
                                .isEqualTo(ExceptionType.NOTIFICATION_CONSENT_REQUIRED));
        verify(transactionService, never()).consentNight(any(), any(), any(), anyList());
    }

    @Test
    void consentNight_withAdvertisingConsent_applies() {
        givenNoPriorEvents();
        givenSnapshot(snapshot(true, 100L, false, null));
        givenCurrentDocument(NIGHT_DOC);

        service().apply(SUBJECT_ID, REQUEST_ID, NotificationConsentType.NIGHT_ADVERTISING_PUSH, true, "v1",
                NotificationConsentSource.PUSH_SETTINGS);

        verify(transactionService).consentNight(eq(SUBJECT_ID), eq(200L), eq(NOW_KST), anyList());
    }

    // --- 철회 ---

    @Test
    void withdrawAdvertising_alsoWithdrawsNightAndRecordsBothEvents() {
        givenNoPriorEvents();
        givenSnapshot(snapshot(true, 100L, true, 200L));

        service().apply(SUBJECT_ID, REQUEST_ID, NotificationConsentType.ADVERTISING_PUSH, false, null,
                NotificationConsentSource.PUSH_SETTINGS);

        verify(transactionService).withdrawAdvertising(eq(SUBJECT_ID), eq(NOW_KST), eventsCaptor.capture());
        assertThat(eventsCaptor.getValue()).hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.getAction()).isEqualTo(NotificationConsentAction.WITHDRAW);
                    assertThat(event.getProcessingResult())
                            .isEqualTo(NotificationConsentProcessingResult.APPLIED);
                })
                .extracting(NotificationConsentEvent::getConsentType)
                .containsExactly(NotificationConsentType.ADVERTISING_PUSH,
                        NotificationConsentType.NIGHT_ADVERTISING_PUSH);
        // 철회 증적은 마지막으로 동의한 문서를 가리켜 어떤 문구에 동의했었는지를 남긴다.
        assertThat(eventsCaptor.getValue()).extracting(NotificationConsentEvent::getTermDocumentId)
                .containsExactly(100L, 200L);
    }

    @Test
    void withdrawAdvertising_whenAlreadyOff_recordsAlreadyInStateOnly() {
        givenNoPriorEvents();
        givenSnapshot(snapshot(false, null, false, null));

        service().apply(SUBJECT_ID, REQUEST_ID, NotificationConsentType.ADVERTISING_PUSH, false, null,
                NotificationConsentSource.INSTALLATION_OPT_OUT);

        verify(transactionService).recordEventsOnly(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue()).singleElement().satisfies(event -> {
            assertThat(event.getProcessingResult())
                    .isEqualTo(NotificationConsentProcessingResult.ALREADY_IN_STATE);
            assertThat(event.getSource()).isEqualTo(NotificationConsentSource.INSTALLATION_OPT_OUT);
        });
        verify(transactionService, never()).withdrawAdvertising(any(), any(), anyList());
    }

    @Test
    void withdrawNight_leavesAdvertisingConsentIntact() {
        givenNoPriorEvents();
        givenSnapshot(snapshot(true, 100L, true, 200L));

        service().apply(SUBJECT_ID, REQUEST_ID, NotificationConsentType.NIGHT_ADVERTISING_PUSH, false, null,
                NotificationConsentSource.PUSH_SETTINGS);

        verify(transactionService).withdrawNight(eq(SUBJECT_ID), eq(NOW_KST), eventsCaptor.capture());
        assertThat(eventsCaptor.getValue()).singleElement().satisfies(event ->
                assertThat(event.getConsentType())
                        .isEqualTo(NotificationConsentType.NIGHT_ADVERTISING_PUSH));
        verify(transactionService, never()).withdrawAdvertising(any(), any(), anyList());
    }

    // --- 멱등 ---

    @Test
    void sameClientRequestId_returnsOriginalEventsWithoutRepeatingStateChange() {
        NotificationConsentEvent existing = NotificationConsentEvent.of(SUBJECT_ID, REQUEST_ID,
                NotificationConsentType.ADVERTISING_PUSH, NotificationConsentAction.CONSENT, 100L, NOW_KST,
                "라이모리 주식회사", NotificationConsentProcessingResult.APPLIED,
                NotificationConsentSource.PUSH_SETTINGS);
        ReflectionTestUtils.setField(existing, "notificationConsentEventId", 7L);
        when(eventRepository.findAllBySubjectIdAndClientRequestId(SUBJECT_ID, REQUEST_ID))
                .thenReturn(List.of(existing));

        List<NotificationConsentEvent> result = service().apply(SUBJECT_ID, REQUEST_ID,
                NotificationConsentType.ADVERTISING_PUSH, true, "v1", NotificationConsentSource.PUSH_SETTINGS);

        assertThat(result).containsExactly(existing);
        verify(transactionService, never()).consentAdvertising(any(), any(), any(), anyList());
        verify(transactionService, never()).recordEventsOnly(anyList());
    }

    @Test
    void sameClientRequestId_withDifferentIntent_isRejected() {
        NotificationConsentEvent existing = NotificationConsentEvent.of(SUBJECT_ID, REQUEST_ID,
                NotificationConsentType.ADVERTISING_PUSH, NotificationConsentAction.CONSENT, 100L, NOW_KST,
                "라이모리 주식회사", NotificationConsentProcessingResult.APPLIED,
                NotificationConsentSource.PUSH_SETTINGS);
        when(eventRepository.findAllBySubjectIdAndClientRequestId(SUBJECT_ID, REQUEST_ID))
                .thenReturn(List.of(existing));

        // 같은 멱등 키로 반대 의사가 오면 어느 쪽도 적용하지 않는다(앱은 새 request ID로 다시 보낸다).
        assertThatThrownBy(() -> service().apply(SUBJECT_ID, REQUEST_ID,
                NotificationConsentType.ADVERTISING_PUSH, false, null, NotificationConsentSource.PUSH_SETTINGS))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType()).isEqualTo(ExceptionType.CONSENT_REQUEST_MISMATCH));
    }

    // --- 조회 ---

    @Test
    void findState_missingRowMeansNoConsent() {
        when(consentRepository.findById(SUBJECT_ID)).thenReturn(Optional.empty());

        NotificationConsentService.ConsentState state = service().findState(SUBJECT_ID);

        assertThat(state.advertisingConsented()).isFalse();
        assertThat(state.nightAdvertisingConsented()).isFalse();
    }

    @Test
    void findStatesBySubjectIds_emptyInput_skipsQuery() {
        assertThat(service().findStatesBySubjectIds(List.of())).isEmpty();
        verify(consentRepository, never()).findAllBySubjectIdIn(any());
    }
}
