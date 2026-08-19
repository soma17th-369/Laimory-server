package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.laimory.server.push.entity.NotificationConsent;
import com.laimory.server.push.entity.NotificationConsentEvent;
import com.laimory.server.push.repository.NotificationConsentEventRepository;
import com.laimory.server.push.repository.NotificationConsentRepository;
import com.laimory.server.push.service.NotificationConsentTransactionService.ConsentCommand;
import com.laimory.server.testsupport.TestSubjects;
import java.time.LocalDateTime;
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
 * 동의 전이 transaction 검증 — 처리 결과가 <b>조건부 UPDATE의 영향 행 수</b>에서 나오는지, 철회가 야간
 * 동의를 함께 내리고 그 사실을 별도 증적으로 남기는지 확인한다.
 *
 * <p>여기가 "읽고 나서 판단"을 없앤 자리다. 직전 상태를 미리 읽어 결정하면 읽기와 쓰기 사이에 낀 동시
 * 요청 때문에 철회가 {@code ALREADY_IN_STATE}로 기록되고 실제 상태는 ON으로 남을 수 있다. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class NotificationConsentTransactionServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(22L);
    private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 7, 21, 14, 0);
    private static final ConsentCommand COMMAND = new ConsentCommand(SUBJECT_ID, NOW_KST,
            "라이모리 주식회사", NotificationConsentSource.PUSH_SETTINGS);

    @Mock
    private NotificationConsentRepository consentRepository;
    @Mock
    private NotificationConsentEventRepository eventRepository;

    @Captor
    private ArgumentCaptor<List<NotificationConsentEvent>> eventsCaptor;

    private NotificationConsentTransactionService service() {
        return new NotificationConsentTransactionService(consentRepository, eventRepository);
    }

    private static NotificationConsent snapshot(boolean advertising, Long adDoc, boolean night, Long nightDoc) {
        NotificationConsent consent = new NotificationConsent() {
        };
        ReflectionTestUtils.setField(consent, "subjectId", SUBJECT_ID);
        ReflectionTestUtils.setField(consent, "advertisingPushConsented", advertising);
        ReflectionTestUtils.setField(consent, "advertisingTermDocumentId", adDoc);
        ReflectionTestUtils.setField(consent, "nightAdvertisingPushConsented", night);
        ReflectionTestUtils.setField(consent, "nightTermDocumentId", nightDoc);
        return consent;
    }

    private void givenSnapshot(NotificationConsent consent) {
        when(consentRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(consent));
    }

    private List<NotificationConsentEvent> capturedEvents() {
        verify(eventRepository).saveAll(eventsCaptor.capture());
        return eventsCaptor.getValue();
    }

    // --- 결과는 영향 행 수가 정한다 ---

    @Test
    void consentAdvertising_affectedRow_isApplied() {
        givenSnapshot(snapshot(false, null, false, null));
        when(consentRepository.consentAdvertising(SUBJECT_ID, 100L, NOW_KST)).thenReturn(1);

        service().consentAdvertising(COMMAND, 100L);

        assertThat(capturedEvents()).singleElement().satisfies(event -> {
            assertThat(event.getConsentType()).isEqualTo(NotificationConsentType.ADVERTISING_PUSH);
            assertThat(event.getAction()).isEqualTo(NotificationConsentAction.CONSENT);
            assertThat(event.getTermDocumentId()).isEqualTo(100L);
            assertThat(event.getProcessingResult()).isEqualTo(NotificationConsentProcessingResult.APPLIED);
            assertThat(event.getSenderName()).isEqualTo("라이모리 주식회사");
            assertThat(event.getOccurredAt()).isEqualTo(NOW_KST);
        });
    }

    @Test
    void consentAdvertising_noAffectedRow_isAlreadyInState() {
        // 조건부 UPDATE가 0행 = 그 순간 이미 같은 문서로 동의 상태였다.
        givenSnapshot(snapshot(true, 100L, false, null));
        when(consentRepository.consentAdvertising(SUBJECT_ID, 100L, NOW_KST)).thenReturn(0);

        service().consentAdvertising(COMMAND, 100L);

        assertThat(capturedEvents()).singleElement().satisfies(event ->
                assertThat(event.getProcessingResult())
                        .isEqualTo(NotificationConsentProcessingResult.ALREADY_IN_STATE));
    }

    @Test
    void withdrawAdvertising_affectedRow_isAppliedEvenWhenPriorReadWouldSayOff() {
        // 핵심 회귀: 미리 읽은 상태가 아니라 UPDATE 영향 행 수가 결과를 정한다. 읽기 시점에 OFF로 보여도
        // UPDATE가 1행을 바꿨다면 이 요청이 실제로 껐다는 뜻이므로 APPLIED여야 한다.
        givenSnapshot(snapshot(false, 100L, false, null));
        when(consentRepository.withdrawAdvertising(SUBJECT_ID, NOW_KST)).thenReturn(1);
        when(consentRepository.withdrawNight(SUBJECT_ID, NOW_KST)).thenReturn(0);

        service().withdrawAdvertising(COMMAND);

        assertThat(capturedEvents()).singleElement().satisfies(event -> {
            assertThat(event.getAction()).isEqualTo(NotificationConsentAction.WITHDRAW);
            assertThat(event.getProcessingResult()).isEqualTo(NotificationConsentProcessingResult.APPLIED);
        });
    }

    @Test
    void withdrawAdvertising_noAffectedRow_isAlreadyInState() {
        givenSnapshot(snapshot(false, null, false, null));
        when(consentRepository.withdrawAdvertising(SUBJECT_ID, NOW_KST)).thenReturn(0);
        when(consentRepository.withdrawNight(SUBJECT_ID, NOW_KST)).thenReturn(0);

        service().withdrawAdvertising(COMMAND);

        assertThat(capturedEvents()).singleElement().satisfies(event ->
                assertThat(event.getProcessingResult())
                        .isEqualTo(NotificationConsentProcessingResult.ALREADY_IN_STATE));
    }

    // --- 야간 동반 철회 ---

    @Test
    void withdrawAdvertising_alsoWithdrawsNight_andRecordsBothEvents() {
        givenSnapshot(snapshot(true, 100L, true, 200L));
        when(consentRepository.withdrawNight(SUBJECT_ID, NOW_KST)).thenReturn(1);
        when(consentRepository.withdrawAdvertising(SUBJECT_ID, NOW_KST)).thenReturn(1);

        service().withdrawAdvertising(COMMAND);

        assertThat(capturedEvents()).hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.getAction()).isEqualTo(NotificationConsentAction.WITHDRAW);
                    assertThat(event.getProcessingResult())
                            .isEqualTo(NotificationConsentProcessingResult.APPLIED);
                })
                .extracting(NotificationConsentEvent::getConsentType)
                .containsExactly(NotificationConsentType.ADVERTISING_PUSH,
                        NotificationConsentType.NIGHT_ADVERTISING_PUSH);
        // 철회 증적은 마지막으로 동의한 문서를 가리켜 어떤 문구에 동의했었는지를 남긴다.
        assertThat(capturedEvents()).extracting(NotificationConsentEvent::getTermDocumentId)
                .containsExactly(100L, 200L);
    }

    @Test
    void withdrawAdvertising_whenNightWasAlreadyOff_recordsOnlyOneEvent() {
        givenSnapshot(snapshot(true, 100L, false, null));
        when(consentRepository.withdrawNight(SUBJECT_ID, NOW_KST)).thenReturn(0);
        when(consentRepository.withdrawAdvertising(SUBJECT_ID, NOW_KST)).thenReturn(1);

        service().withdrawAdvertising(COMMAND);

        assertThat(capturedEvents()).singleElement().satisfies(event ->
                assertThat(event.getConsentType()).isEqualTo(NotificationConsentType.ADVERTISING_PUSH));
    }

    // --- 야간 동의 전제 ---

    @Test
    void consentNight_withoutAdvertisingConsent_isRejected() {
        when(consentRepository.consentNight(SUBJECT_ID, 200L, NOW_KST)).thenReturn(0);
        when(consentRepository.findById(SUBJECT_ID))
                .thenReturn(Optional.of(snapshot(false, null, false, null)));

        assertThatThrownBy(() -> service().consentNight(COMMAND, 200L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType())
                                .isEqualTo(ExceptionType.NOTIFICATION_CONSENT_REQUIRED));
        verify(eventRepository, never()).saveAll(any());
    }

    @Test
    void consentNight_alreadyConsentedWithSameDocument_isAlreadyInState() {
        // 같은 0행이라도 일반 동의가 살아 있으면 전제 불충족이 아니라 "이미 같은 상태"다.
        when(consentRepository.consentNight(SUBJECT_ID, 200L, NOW_KST)).thenReturn(0);
        when(consentRepository.findById(SUBJECT_ID))
                .thenReturn(Optional.of(snapshot(true, 100L, true, 200L)));

        service().consentNight(COMMAND, 200L);

        assertThat(capturedEvents()).singleElement().satisfies(event ->
                assertThat(event.getProcessingResult())
                        .isEqualTo(NotificationConsentProcessingResult.ALREADY_IN_STATE));
    }

    @Test
    void missingRow_isCreatedBeforeTheTransition() {
        when(consentRepository.findById(SUBJECT_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(snapshot(false, null, false, null)));
        when(consentRepository.withdrawAdvertising(any(), any())).thenReturn(0);
        when(consentRepository.withdrawNight(any(), any())).thenReturn(0);

        service().withdrawAdvertising(COMMAND);

        // 행이 없으면 기본 OFF 행을 만들어 조건부 UPDATE가 대상 행을 갖게 한다(감사 시각은 요청 시각).
        verify(consentRepository).insertIfAbsent(eq(SUBJECT_ID.toString()), eq(NOW_KST));
    }

    @Test
    void existingRow_isNotRewritten() {
        // 있는 행에 INSERT IGNORE를 날리면 S락이 잡혀, 이어지는 UPDATE의 X락과 얽혀 동시 요청이
        // deadlock에 빠진다(실 MySQL 테스트로 확인). 정상 경로에서는 읽기만 한다.
        givenSnapshot(snapshot(true, 100L, false, null));
        when(consentRepository.withdrawAdvertising(any(), any())).thenReturn(1);
        when(consentRepository.withdrawNight(any(), any())).thenReturn(0);

        service().withdrawAdvertising(COMMAND);

        verify(consentRepository, never()).insertIfAbsent(anyString(), any());
    }
}
